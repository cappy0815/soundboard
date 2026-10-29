package de.justus.soundboard;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.audiofx.LoudnessEnhancer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Audio-Engine: beliebig viele Kacheln gleichzeitig, jede mit eigenem MediaPlayer.
 *
 * Lautstaerke eines Sounds = Titel-Gain + Kachel-Gain (dB)
 *   - unter 0 dB: ueber MediaPlayer.setVolume (abschwaechen)
 *   - ueber 0 dB: ueber LoudnessEnhancer (Android-Standard-Effekt, verstaerken)
 * dazu multipliziert: Fade-Faktor (Ein-/Ausblenden), Ducking-Faktor (Durchsage), Mute.
 * Alle Faktoren werden alle 30 ms weich nachgefuehrt.
 */
final class Engine {

    interface Listener {
        /** Start/Stopp/Titelwechsel -> Oberflaeche neu zeichnen, Board speichern */
        void onEngineChanged();

        void onEngineError(String msg);
    }

    private static final long TICK_MS = 30;
    private static final float DUCK_RAMP_SEC = 0.35f;
    private static final float MUTE_RAMP_SEC = 0.15f;

    private final Context ctx;
    private final Board board;
    private final Listener listener;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    final List<Voice> voices = new ArrayList<>();
    private boolean muted;
    private float muteF = 1f;
    private long lastTick;
    private Voice preview;
    /** Warteschlange: vorgemerkte Kacheln {preset, slot} in Startreihenfolge */
    final List<int[]> queue = new ArrayList<>();
    /** Fade-Taste gedrueckt -> Vorgemerktes startet, sobald die Musik ausgeblendet ist */
    private boolean queueOnFadeEnd;
    /** Ziel des Track-Gain-Reglers (zuletzt gestartete Musik, per Antippen umschaltbar) */
    private Voice gainVoice;

    // ------------------------------------------------------------------ ein laufender Sound
    final class Voice {
        final Board.Btn btn;
        final int preset, slot;
        MediaPlayer mp;
        LoudnessEnhancer le;
        boolean prepared, paused, stopping, ducker;
        float base = 1f;          // Gain-Anteil ueber setVolume (<= 1)
        float fade = 1f, fadeTarget = 1f, fadeRate = 0f; // Rate = Aenderung pro Sekunde
        float duck = 1f;
        List<Board.Track> order = new ArrayList<>();
        int idx = -1;
        Board.Track cur;

        /** Titel/Playlist ist von selbst zu Ende gelaufen (nicht gestoppt) */
        boolean ended;
        /** Weiterschalten mit kurzem Ausblenden: Ziel-Index, -1 = keins */
        int skipTo = -1;
        /** Endpunkt dieses Titels auf dieser Kachel (ms, 0 = bis zum Dateiende) */
        long endMs;
        boolean endFade;
        /** per LEISER-Taste abgesenkt (nur Sounds, die beim Druecken liefen) */
        boolean manualDuck;

        Voice(Board.Btn b, int preset, int slot) {
            this.btn = b;
            this.preset = preset;
            this.slot = slot;
        }

        long position() {
            try {
                return prepared ? mp.getCurrentPosition() : 0;
            } catch (Exception e) {
                return 0;
            }
        }

        long duration() {
            try {
                return prepared ? mp.getDuration() : 0;
            } catch (Exception e) {
                return 0;
            }
        }

        boolean isFading() {
            return stopping || fade < fadeTarget - 0.001f;
        }

        String trackName() {
            return cur == null ? "" : cur.display();
        }
    }

    Engine(Context c, Board b, Listener l) {
        ctx = c.getApplicationContext();
        board = b;
        listener = l;
    }

    // ------------------------------------------------------------------ Abfragen
    Voice voice(int preset, int slot) {
        for (Voice v : voices) if (v.preset == preset && v.slot == slot) return v;
        return null;
    }

    boolean isMuted() {
        return muted;
    }

    boolean anyPlaying() {
        return !voices.isEmpty();
    }

    // ------------------------------------------------------------------ Bedienung
    /** Kachel angetippt: laeuft sie, wird gestoppt (Fade oder hart je nach Einstellung), sonst gestartet */
    void press(int preset, int slot) {
        Voice v = voice(preset, slot);
        if (v != null) {
            // laeuft schon ein Ausblenden -> nochmal tippen = sofort aus
            if (v.stopping) finish(v);
            else stop(v, stopSec(v.btn));
            return;
        }
        int q = queuePos(preset, slot);
        if (q >= 0) { // vorgemerkt -> nochmal tippen = Vormerkung aufheben
            queue.remove(q);
            changed();
            return;
        }
        Board.Btn b = board.presets.get(preset).btns[slot];
        if (board.queueOn && !b.startsImmediately() && blocking()) {
            queue.add(new int[]{preset, slot});
            changed();
            return;
        }
        start(preset, slot);
    }

    /**
     * Laeuft irgendetwas, das nicht gerade ausblendet? Dann wird eine normale Kachel vorgemerkt.
     * Gilt fuer ALLE Sounds (auch Hymnen, Zamboni, Hupen mit ⚡) - ⚡ bestimmt nur, ob eine Kachel
     * selbst sofort startet, nicht ob andere auf sie warten muessen.
     */
    boolean blocking() {
        for (Voice v : voices) if (!v.stopping) return true;
        return false;
    }

    /** Position in der Warteschlange (0 = als Naechstes) oder -1 */
    int queuePos(int preset, int slot) {
        for (int i = 0; i < queue.size(); i++) if (queue.get(i)[0] == preset && queue.get(i)[1] == slot) return i;
        return -1;
    }

    void unqueue(int i) {
        if (i >= 0 && i < queue.size()) queue.remove(i);
        changed();
    }

    /** naechste vorgemerkte Kachel starten (falls es sie noch gibt) */
    private void startQueued() {
        while (!queue.isEmpty()) {
            int[] q = queue.remove(0);
            if (q[0] >= board.presets.size()) continue;
            if (board.presets.get(q[0]).btns[q[1]].isEmpty() || voice(q[0], q[1]) != null) continue;
            start(q[0], q[1]);
            return;
        }
    }

    // ------------------------------------------------------------------ Track-Gain (laufender Titel)
    /** welcher laufende Sound gerade am Track-Gain-Regler haengt (null = nichts laeuft) */
    Voice gainTarget() {
        if (gainVoice != null && voices.contains(gainVoice) && gainVoice.cur != null) return gainVoice;
        for (int i = voices.size() - 1; i >= 0; i--) {
            Voice v = voices.get(i);
            if (v.cur != null && !v.btn.startsImmediately()) return v;
        }
        return voices.isEmpty() ? null : voices.get(voices.size() - 1);
    }

    /** naechsten laufenden Sound an den Regler haengen */
    void cycleGainTarget() {
        if (voices.isEmpty()) return;
        Voice cur = gainTarget();
        int i = voices.indexOf(cur);
        gainVoice = voices.get((i + 1) % voices.size());
        changed();
    }

    /** Titel-Gain des laufenden Titels setzen - wirkt sofort und wird dauerhaft fuer diesen Titel gespeichert */
    void setTrackGain(Voice v, float db) {
        if (v == null || v.cur == null) return;
        board.setGain(v.cur, db);
        regain();
    }

    /** Titel einer laufenden Playlist direkt anspringen (harter Wechsel) */
    void jumpTo(Voice v, int i) {
        if (v == null || v.stopping || !voices.contains(v) || i < 0 || i >= v.order.size()) return;
        beginSkip(v, i);
    }

    /** Titelwechsel innerhalb der Playlist: kurz ausblenden (Einstellung), dann den Ziel-Titel starten */
    private void beginSkip(Voice v, int i) {
        float sec = board.skipFadeSec;
        if (sec <= 0.01f || v.paused || !v.prepared) {
            v.skipTo = -1;
            v.fade = 1f;
            v.fadeTarget = 1f;
            if (!playIndex(v, i, v.order.size())) {
                finish(v);
                listener.onEngineError("Titel nicht abspielbar");
                return;
            }
            changed();
            return;
        }
        v.skipTo = i;
        v.fadeTarget = 0f;
        v.fadeRate = Math.max(v.fade, 0.01f) / sec;
        kick();
        changed();
    }

    /** Ausblenden fertig -> Ziel-Titel starten und kurz einblenden */
    private void doSkip(Voice v) {
        int k = v.skipTo;
        v.skipTo = -1;
        v.fade = 0f;
        v.fadeTarget = 1f;
        v.fadeRate = 1f / 0.15f;
        if (!playIndex(v, k, v.order.size())) {
            finish(v);
            listener.onEngineError("Titel nicht abspielbar");
            return;
        }
        changed();
    }

    /** Langer Druck: Pause / weiter */
    void togglePause(int preset, int slot) {
        Voice v = voice(preset, slot);
        if (v == null || !v.prepared) return;
        try {
            if (v.paused) {
                v.mp.start();
                v.paused = false;
            } else {
                v.mp.pause();
                v.paused = true;
            }
        } catch (Exception ignored) {
        }
        changed();
    }

    /** FADE-Taste: alles mit der eingestellten Fade-Zeit ausblenden */
    void fadeAll() {
        boolean any = false;
        for (Voice v : new ArrayList<>(voices)) {
            if (!v.stopping) {
                stop(v, board.fadeButtonSec);
                any = true;
            }
        }
        if (queue.isEmpty()) return;
        boolean fading = false;
        for (Voice v : voices) if (v.stopping) fading = true;
        if (board.queueCrossfade || !any || !fading) {
            startQueued(); // ueberblenden bzw. es lief gar nichts (mehr)
        } else {
            queueOnFadeEnd = true; // startet, sobald ausgeblendet ist (siehe finish)
        }
    }

    /** Steuer-Kachel STOPP: jeder Sound stoppt so, wie auf seiner Kachel eingestellt (hart oder Fade) */
    void stopAllAsSet() {
        boolean any = false;
        for (Voice v : new ArrayList<>(voices)) {
            if (!v.stopping) {
                stop(v, stopSec(v.btn));
                any = true;
            }
        }
        if (queue.isEmpty()) return;
        boolean fading = false;
        for (Voice v : voices) if (v.stopping) fading = true;
        if (board.queueCrossfade || !any || !fading) startQueued();
        else queueOnFadeEnd = true;
    }

    /** Steuer-Kachel WEITER: naechster Titel der zuletzt gestarteten laufenden Playlist */
    boolean skipLatestPlaylist() {
        for (int i = voices.size() - 1; i >= 0; i--) {
            Voice v = voices.get(i);
            if (!v.stopping && v.order.size() > 1) {
                skip(v);
                return true;
            }
        }
        return false;
    }

    /**
     * LEISER-Taste: alle gerade laufenden Sounds um den eingestellten Wert absenken (weich, wie bei einer Durchsage);
     * nochmal druecken = zurueck auf die alte Lautstaerke. Sounds, die spaeter starten (Torhupe ...), bleiben normal laut.
     * Liefert false, wenn nichts laeuft.
     */
    boolean toggleManualDuck() {
        if (manualDucked()) {
            for (Voice v : voices) v.manualDuck = false;
        } else {
            boolean any = false;
            for (Voice v : voices) {
                if (!v.stopping) {
                    v.manualDuck = true;
                    any = true;
                }
            }
            if (!any) return false;
        }
        kick();
        changed();
        return true;
    }

    boolean manualDucked() {
        for (Voice v : voices) if (v.manualDuck) return true;
        return false;
    }

    boolean anyPlaylistPlaying() {
        for (Voice v : voices) if (!v.stopping && v.order.size() > 1) return true;
        return false;
    }

    /** STOP-Taste: alles sofort aus */
    void stopAll() {
        queueOnFadeEnd = false;
        for (Voice v : new ArrayList<>(voices)) finish(v);
    }

    void toggleMute() {
        muted = !muted;
        kick();
        changed();
    }

    /** naechster Titel einer Playlist-Kachel */
    void skip(Voice v) {
        if (v == null || v.stopping || !voices.contains(v)) return;
        int base = v.skipTo >= 0 ? v.skipTo : v.idx; // mehrfach tippen = mehrere Titel weiter
        int n = base + 1;
        if (n >= v.order.size()) {
            if (!v.btn.loop) {
                stop(v, Math.max(board.skipFadeSec, 0.01f)); // letzter Titel -> Kachel ausblenden
                return;
            }
            n = 0;
            if (v.btn.shuffle && v.btn.tracks().size() > 1) newShuffleRun(v);
        }
        beginSkip(v, n);
    }

    void stop(Voice v, float sec) {
        if (v == null) return;
        if (sec <= 0.01f || v.paused || !v.prepared) {
            finish(v);
            return;
        }
        v.stopping = true;
        v.skipTo = -1;
        v.fadeTarget = 0f;
        v.fadeRate = Math.max(v.fade, 0.01f) / sec;
        kick();
        changed();
    }

    float stopSec(Board.Btn b) {
        return b.stopFade < 0 ? board.stopFadeSec : b.stopFade;
    }

    /** Zufall + Wiederholen: Liste durch -> neuer Durchgang, neu gemischt (letzter Titel nicht direkt wieder vorn) */
    private void newShuffleRun(Voice v) {
        v.btn.runDone.clear();
        List<Board.Track> all = new ArrayList<>(v.btn.tracks());
        Collections.shuffle(all, rnd);
        if (all.size() > 1 && v.cur != null && all.get(0).uri.equals(v.cur.uri)) Collections.swap(all, 0, all.size() - 1);
        v.order = all;
    }

    /** Titel-Gain wurde geaendert -> laufende Sounds mit diesem Titel sofort anpassen */
    void regain() {
        for (Voice v : voices) applyGain(v);
        if (preview != null) applyGain(preview);
    }

    void release() {
        h.removeCallbacksAndMessages(null);
        queue.clear();
        stopPreview();
        for (Voice v : new ArrayList<>(voices)) finish(v);
    }

    // ------------------------------------------------------------------ Start
    private void start(int preset, int slot) {
        Board.Btn b = board.presets.get(preset).btns[slot];
        List<Board.Track> tracks = b.tracks();
        if (tracks.isEmpty()) {
            listener.onEngineError(!b.hasFolders() ? "Kachel ist leer" : "In „" + b.foldersLabel() + "“ sind keine Titel");
            return;
        }
        // andere Sounds: weiterlaufen / leiser / ausblenden
        if (b.others == Board.OTHERS_FADE) {
            for (Voice o : new ArrayList<>(voices)) if (!o.stopping) stop(o, board.switchFadeSec);
        }
        Voice v = new Voice(b, preset, slot);
        v.ducker = b.others == Board.OTHERS_DUCK;
        v.order = new ArrayList<>(tracks);
        // Starttitel = der auf der Kachel angezeigte "naechste" Titel (ggf. vorher weitergeschaltet)
        Board.Track nt = b.nextTrack();
        int first = Math.max(0, v.order.indexOf(nt));
        if (b.shuffle && v.order.size() > 1) {
            // Zufall: erst der angekuendigte Titel, dann die restlichen noch offenen Titel dieses Durchgangs
            List<Board.Track> rest = b.undone();
            rest.remove(nt);
            Collections.shuffle(rest, rnd);
            v.order = new ArrayList<>();
            if (nt != null) v.order.add(nt);
            v.order.addAll(rest);
            if (v.order.isEmpty()) v.order.addAll(tracks);
            first = 0;
        }
        if (b.fadeIn > 0.01f) {
            v.fade = 0f;
            v.fadeTarget = 1f;
            v.fadeRate = 1f / b.fadeIn;
        }
        voices.add(v);
        if (!playIndex(v, first, v.order.size())) {
            voices.remove(v);
            listener.onEngineError("Titel nicht abspielbar – Datei verschoben oder Zugriff verloren?");
            changed();
            return;
        }
        if (board.markPlayed) b.played = true;
        if (!b.startsImmediately() || gainTarget() == null) gainVoice = v; // Torhupe & Co. klauen den Regler nicht
        kick();
        changed();
    }

    /**
     * Spielt order[i]; klappt es nicht, wird der naechste versucht (hoechstens "tries" Titel).
     * Liefert false, wenn gar nichts abspielbar war.
     */
    private boolean playIndex(Voice v, int i, int tries) {
        for (int n = 0; n < tries; n++) {
            int k = (i + n) % v.order.size();
            if (openTrack(v, v.order.get(k))) {
                v.idx = k;
                // "weiter mit naechstem Titel": beim naechsten Start kommt der folgende Titel
                if (!v.btn.shuffle) v.btn.pos = k + 1;
                v.btn.lastUri = v.order.get(k).uri;
                v.btn.markRun(v.order.get(k));
                v.btn.nextUri = ""; // naechster Titel wird neu bestimmt
                return true;
            }
        }
        return false;
    }

    private boolean openTrack(Voice v, Board.Track t) {
        releasePlayer(v);
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            mp.setDataSource(ctx, Uri.parse(t.uri));
            mp.prepare();
        } catch (Exception e) {
            mp.release();
            return false;
        }
        v.mp = mp;
        v.cur = t;
        v.prepared = true;
        v.paused = false;
        // Einzeltitel mit "Wiederholen" laeuft nahtlos in Schleife
        float st = v.btn.trimStart(t), en = v.btn.trimEnd(t);
        v.endMs = en > st + 0.5f ? Math.round(en * 1000) : 0;
        v.endFade = false;
        // nahtlose Schleife nur ohne Start-/Endpunkt, sonst wiederholt next() ab dem Startpunkt
        mp.setLooping(v.btn.loop && v.order.size() == 1 && st < 0.01f && v.endMs == 0);
        final Voice fv = v;
        mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer p) {
                if (fv.mp == p) next(fv);
            }
        });
        mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer p, int what, int extra) {
                if (fv.mp == p) next(fv);
                return true;
            }
        });
        applyGain(v);
        if (st > 0.01f) {
            try {
                if (st * 1000 < mp.getDuration()) mp.seekTo(Math.round(st * 1000));
            } catch (Exception ignored) {
            }
        }
        applyVolume(v);
        mp.start();
        if (v.endMs > 0) watchEnds();
        return true;
    }

    /** Titel-Gain + Kachel-Gain auf setVolume bzw. LoudnessEnhancer verteilen */
    private void applyGain(Voice v) {
        if (v.mp == null || v.cur == null) return;
        // automatische Angleichung + eigene Titel-Korrektur + Kachel-Gain
        float db = board.autoGainOf(v.cur) + board.gainOf(v.cur) + (v == preview ? previewBtnDb : v.btn.gainDb);
        if (db > 0.05f) {
            v.base = 1f;
            try {
                if (v.le == null) v.le = new LoudnessEnhancer(v.mp.getAudioSessionId());
                v.le.setTargetGain(Math.round(db * 100)); // Millibel
                v.le.setEnabled(true);
            } catch (Exception e) {
                v.le = null; // Effekt nicht verfuegbar -> wenigstens 0 dB
            }
        } else {
            v.base = Util.dbToLin(db);
            if (v.le != null) {
                try {
                    v.le.setEnabled(false);
                } catch (Exception ignored) {
                }
            }
        }
        applyVolume(v);
    }

    private void applyVolume(Voice v) {
        if (v.mp == null) return;
        float vol = v.base * curve(v.fade) * v.duck * muteF;
        try {
            v.mp.setVolume(vol, vol);
        } catch (Exception ignored) {
        }
    }

    /** Fade-Verlauf: quadratisch klingt gleichmaessiger als linear */
    private static float curve(float f) {
        f = Math.max(0f, Math.min(1f, f));
        return f * f;
    }

    /** Titel zu Ende (oder Fehler): naechster Playlist-Titel oder Kachel beenden */
    private void next(Voice v) {
        if (v == preview) { // Vorhoeren: Titel zu Ende
            stopPreview();
            return;
        }
        if (v.skipTo >= 0 && voices.contains(v) && !v.stopping) { // Titel endete waehrend des Weiterschaltens
            doSkip(v);
            return;
        }
        if (!voices.contains(v) || v.stopping) {
            finish(v);
            return;
        }
        int n = v.idx + 1;
        if (n >= v.order.size()) {
            if (!v.btn.loop) {
                v.ended = true;
                finish(v);
                return;
            }
            n = 0;
            if (v.btn.shuffle && v.btn.tracks().size() > 1) newShuffleRun(v);
        }
        if (!playIndex(v, n, v.order.size())) {
            finish(v);
            listener.onEngineError("Kein weiterer Titel abspielbar");
            return;
        }
        changed();
    }

    private void releasePlayer(Voice v) {
        v.prepared = false;
        if (v.le != null) {
            try {
                v.le.release();
            } catch (Exception ignored) {
            }
            v.le = null;
        }
        if (v.mp != null) {
            try {
                v.mp.setOnCompletionListener(null);
                v.mp.setOnErrorListener(null);
                v.mp.stop();
            } catch (Exception ignored) {
            }
            try {
                v.mp.release();
            } catch (Exception ignored) {
            }
            v.mp = null;
        }
    }

    private void finish(Voice v) {
        releasePlayer(v);
        boolean was = voices.remove(v);
        if (!was) return;
        // Vorgemerktes starten: nach der Fade-Taste oder wenn die Musik von selbst zu Ende ist
        boolean musicGone = !blocking(); // nichts laeuft mehr (auch keine Hupe)
        boolean fadeStillRunning = false;
        for (Voice o : voices) if (o.stopping) fadeStillRunning = true;
        if (!queue.isEmpty() && musicGone && !fadeStillRunning && (queueOnFadeEnd || v.ended)) {
            queueOnFadeEnd = false;
            startQueued();
        } else if (queue.isEmpty()) {
            queueOnFadeEnd = false;
        }
        changed();
    }

    // ------------------------------------------------------------------ Vorhoeren (Titel-Lautstaerke einstellen)
    private float previewBtnDb = 0f;

    /** spielt einen Titel einzeln ab, um seine Lautstaerke einzustellen (btnDb = Kachel-Gain dazu) */
    void preview(Board.Track t, float btnDb) {
        stopPreview();
        previewBtnDb = btnDb;
        Voice v = new Voice(new Board.Btn(), -1, -1);
        v.order.add(t);
        if (openTrack(v, t)) {
            preview = v;
            kick();
        } else {
            listener.onEngineError("Titel nicht abspielbar");
        }
    }

    void setPreviewBtnDb(float db) {
        previewBtnDb = db;
        if (preview != null) applyGain(preview);
    }

    boolean previewing() {
        return preview != null;
    }

    void stopPreview() {
        if (preview != null) {
            releasePlayer(preview);
            preview = null;
        }
    }

    // ------------------------------------------------------------------ Endpunkte je Titel
    private static final long END_FADE_MS = 400;
    private boolean watching;
    private final Runnable endWatch = new Runnable() {
        @Override
        public void run() {
            boolean any = false;
            for (Voice v : new ArrayList<>(voices)) {
                if (v.endMs <= 0 || !v.prepared) continue;
                any = true;
                if (v.paused || v.stopping || v.skipTo >= 0) continue;
                long pos = v.position();
                if (!v.endFade && pos >= v.endMs - END_FADE_MS) { // kurz vor dem Endpunkt ausblenden
                    v.endFade = true;
                    v.fadeTarget = 0f;
                    v.fadeRate = Math.max(v.fade, 0.01f) / (END_FADE_MS / 1000f);
                    kick();
                }
                if (pos >= v.endMs) { // Endpunkt erreicht = Titel zu Ende
                    v.endMs = 0;
                    v.fade = 0f;
                    v.fadeTarget = 1f;
                    v.fadeRate = 1f / 0.15f;
                    next(v);
                }
            }
            if (any) h.postDelayed(this, 80);
            else watching = false;
        }
    };

    private void watchEnds() {
        if (watching) return;
        watching = true;
        h.postDelayed(endWatch, 80);
    }

    // ------------------------------------------------------------------ Vorhoeren: Position fuer das Setzen von Start/Ende
    long previewPos() {
        return preview == null ? 0 : preview.position();
    }

    long previewDur() {
        return preview == null ? 0 : preview.duration();
    }

    void previewSeek(long ms) {
        if (preview == null || preview.mp == null) return;
        try {
            preview.mp.seekTo((int) Math.max(0, ms));
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ Takt: Fades, Ducking, Mute
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.uptimeMillis();
            float dt = Math.min(0.2f, (now - lastTick) / 1000f);
            lastTick = now;

            float muteTarget = muted ? 0f : 1f;
            muteF = approach(muteF, muteTarget, dt / MUTE_RAMP_SEC);

            boolean duckActive = false;
            for (Voice v : voices) if (v.ducker && !v.stopping) duckActive = true;
            float duckLin = Util.dbToLin(board.duckDb);

            boolean busy = Math.abs(muteF - muteTarget) > 0.001f;
            for (Voice v : new ArrayList<>(voices)) {
                if (!v.paused) v.fade = approach(v.fade, v.fadeTarget, v.fadeRate * dt);
                if (v.skipTo >= 0 && !v.stopping && v.fade <= 0.001f) {
                    doSkip(v);
                    busy = true;
                    continue;
                }
                float dTarget = (duckActive && !v.ducker) ? duckLin : 1f;
                if (v.manualDuck) dTarget = Math.min(dTarget, Util.dbToLin(board.manualDuckDb));
                v.duck = approach(v.duck, dTarget, dt / DUCK_RAMP_SEC);
                applyVolume(v);
                if (v.stopping && v.fade <= 0.001f) {
                    finish(v);
                    continue;
                }
                if (Math.abs(v.fade - v.fadeTarget) > 0.001f || Math.abs(v.duck - dTarget) > 0.001f) busy = true;
            }
            if (preview != null) applyVolume(preview);
            if (busy) h.postDelayed(this, TICK_MS);
            else running = false;
        }
    };
    private boolean running;

    /** Takt anwerfen, falls gerade etwas zu blenden ist */
    private void kick() {
        if (running) return;
        running = true;
        lastTick = SystemClock.uptimeMillis();
        h.postDelayed(tick, TICK_MS);
    }

    private static float approach(float cur, float target, float step) {
        if (cur < target) return Math.min(target, cur + step);
        if (cur > target) return Math.max(target, cur - step);
        return cur;
    }

    private void changed() {
        // Ducking-Ende (Durchsage vorbei) braucht den Takt zum Hochblenden
        kick();
        listener.onEngineChanged();
    }
}
