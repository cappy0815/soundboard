package de.justus.soundboard;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Datenmodell: Presets mit je 40 Kacheln, Lautstaerke je Titel, Einstellungen.
 * Alles liegt als JSON in files/board.json (atomar ueber eine .tmp-Datei geschrieben).
 */
final class Board {

    static final int COLS = 8, ROWS = 5, SLOTS = COLS * ROWS;

    /** Verhalten der anderen Sounds, wenn diese Kachel startet */
    static final int OTHERS_KEEP = 0, OTHERS_DUCK = 1, OTHERS_FADE = 2;

    /** Funktion einer Kachel: Sound abspielen oder Steuer-Taste */
    static final int ACT_SOUND = 0, ACT_STOP = 1, ACT_FADE = 2, ACT_KILL = 3, ACT_NEXT = 4, ACT_DUCK = 5;
    static final String[] ACT_NAMES = {"Sound abspielen", "STOPP", "FADE", "ALLES AUS", "WEITER", "LEISER"};
    static final String[] ACT_ICONS = {"", "■", "↘", "✕", "⏭", "🔉"};

    /** Funktionen fuer Fernbedienungs-Tasten (die ersten vier entsprechen den Steuer-Kacheln) */
    static final int KEY_STOP = 1, KEY_FADE = 2, KEY_KILL = 3, KEY_NEXT = 4, KEY_MUTE = 5, KEY_PREV_PRESET = 6, KEY_NEXT_PRESET = 7,
            KEY_DUCK = 8;
    static final String[] KEY_NAMES = {"", "STOPP (wie eingestellt)", "FADE (alles ausblenden)", "ALLES AUS (sofort)",
            "WEITER (nächster Titel)", "MUTE an/aus", "Preset zurück ‹", "Preset vor ›", "LEISER an/aus (Ansage)"};

    /** Funktion einer Steuer-Kachel -> Funktion fuer runAction (gleiche Nummern, ausser LEISER) */
    static int keyOfAction(int act) {
        return act == ACT_DUCK ? KEY_DUCK : act;
    }

    // ------------------------------------------------------------------ ein Titel
    static final class Track {
        String uri, name;
        /** aus den MP3-Tags (leer = keine Tags) */
        String title = "", artist = "";
        /** Tags wurden schon gelesen (auch wenn keine drin waren) */
        boolean tagged;
        /** Ordner, aus dem der Titel stammt ("" = einzeln gewaehlt) */
        String src = "";
        /** NEU: beim Einlesen neu im Ordner aufgetaucht, noch nicht in „Titel einstellen“ geoeffnet */
        boolean isNew;

        /** Anzeige: "Interpret – Titel" aus den Tags, sonst Dateiname (global abschaltbar) */
        static boolean useTags = true;

        Track(String uri, String name) {
            this.uri = uri;
            this.name = name;
        }

        String display() {
            if (useTags && title != null && !title.trim().isEmpty()) {
                return artist == null || artist.trim().isEmpty() ? title.trim() : artist.trim() + " – " + title.trim();
            }
            return Util.stripExt(name);
        }

        boolean hasTags() {
            return title != null && !title.trim().isEmpty();
        }

        void copyTags(Track o) {
            title = o.title;
            artist = o.artist;
            tagged = o.tagged;
        }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject().put("u", uri).put("n", name);
            if (tagged) o.put("g", true).put("t", title).put("a", artist);
            if (!src.isEmpty()) o.put("f", src);
            if (isNew) o.put("nw", true);
            return o;
        }

        static Track from(JSONObject o) {
            Track t = new Track(o.optString("u"), o.optString("n"));
            t.tagged = o.optBoolean("g", false);
            t.title = o.optString("t", "");
            t.artist = o.optString("a", "");
            t.src = o.optString("f", "");
            t.isNew = o.optBoolean("nw", false);
            return t;
        }
    }

    /** ein als Playlist freigegebener Ordner */
    static final class Folder {
        final String uri, name;

        Folder(String uri, String name) {
            this.uri = uri;
            this.name = name;
        }
    }

    private static final java.util.Random RND = new java.util.Random();

    // ------------------------------------------------------------------ eine Kachel
    static final class Btn {
        String label = "";
        int color = 0;
        /** einzeln gewaehlte Titel (Reihenfolge = Abspielreihenfolge) */
        List<Track> files = new ArrayList<>();
        /** Ordner als Playlist (einer oder mehrere); Titel werden beim Start/Einlesen aus den Ordnern geholt */
        List<Folder> folders = new ArrayList<>();
        /** zuletzt eingelesener Inhalt aller Ordner (Cache, damit der Start sofort klappt) */
        List<Track> folderTracks = new ArrayList<>();
        /** festgelegter naechster Titel (wird auf der ruhenden Kachel angezeigt, "" = noch nicht bestimmt) */
        String nextUri = "";
        /** zuletzt gespielter Titel (Zufall wiederholt ihn nicht direkt) */
        String lastUri = "";
        /** Zufall: in diesem Durchgang schon gespielte Titel (jedes Lied genau einmal, dann neuer Durchgang) */
        List<String> runDone = new ArrayList<>();

        /** Anzahl der Titel dieser Liste, die im aktuellen Durchgang schon gelaufen sind */
        int runCount() {
            if (runDone.isEmpty()) return 0;
            java.util.Set<String> done = new java.util.HashSet<>(runDone);
            int c = 0;
            for (Track t : tracks()) if (done.contains(t.uri)) c++;
            return c;
        }

        /** Titel, die im aktuellen Durchgang noch nicht gelaufen sind (leer -> neuer Durchgang) */
        List<Track> undone() {
            java.util.Set<String> done = new java.util.HashSet<>(runDone);
            List<Track> out = new ArrayList<>();
            for (Track t : tracks()) if (!done.contains(t.uri)) out.add(t);
            return out;
        }

        /** Zaehler fuer die ruhende Kachel: das wievielte Lied als Naechstes kommt (1-basiert) */
        int counterIdle() {
            List<Track> t = tracks();
            if (shuffle) {
                int c = runCount();
                return c >= t.size() ? 1 : c + 1;
            }
            int i = t.indexOf(nextTrack());
            return i < 0 ? 1 : i + 1;
        }

        /** Zaehler waehrend ein Titel laeuft: das wievielte Lied des Durchgangs (1-basiert) */
        int counterPlaying(Track cur) {
            if (shuffle) return Math.max(1, runCount());
            int i = tracks().indexOf(cur);
            return i < 0 ? 1 : i + 1;
        }

        /** Titel wurde gestartet -> im Durchgang abhaken (nur Zufall braucht das) */
        void markRun(Track t) {
            if (shuffle && t != null && !runDone.contains(t.uri)) runDone.add(t.uri);
        }
        boolean shuffle, loop, resume = true;
        /** Kachel-Lautstaerke zusaetzlich zur Titel-Lautstaerke (dB) */
        float gainDb = 0f;
        /** Einblenden in Sekunden (0 = sofort voll) */
        float fadeIn = 0f;
        /** Beim Stoppen: -1 = Standard aus den Einstellungen, 0 = hart, >0 = eigene Fade-Zeit (s) */
        float stopFade = -1f;
        int others = OTHERS_KEEP;
        /** Startposition (s) - gilt fuer jeden Titel der Kachel */
        float startSec = 0f;
        /** wurde schon gespielt (Markierung) */
        boolean played;
        /** naechster Titel bei "weiter mit naechstem Titel" */
        int pos = 0;
        /** Groesse in Rasterzellen (Breite x Hoehe), verankert an der eigenen Position oben links */
        int w = 1, h = 1;
        /** Bild auf der Kachel (Dateiname in files/img, "" = keins) */
        String image = "";
        /** 0 = Bild fuellt die Kachel, 1 = Bild als Symbol ueber dem Namen */
        int imageMode = 0;
        boolean showLabel = true;
        /** kein Kachelfeld zeichnen - nur Bild/Text (und Rahmen, wenn sie laeuft) */
        boolean transparent = false;
        /** bei transparentem Hintergrund trotzdem einen Rahmen in der Kachelfarbe zeigen */
        boolean frame = false;
        /** startet immer sofort, wird nie vorgemerkt (Torhupe, Effekte) */
        boolean immediate = false;
        /** ACT_SOUND oder eine Steuer-Funktion (Stopp, Fade, Alles aus, Weiter) */
        int action = ACT_SOUND;
        /**
         * Start-/Endpunkt je Titel NUR auf dieser Kachel (Sekunden; 0 = Anfang bzw. bis zum Ende).
         * Derselbe Titel auf einer anderen Kachel bleibt davon unberuehrt.
         */
        Map<String, float[]> trims = new HashMap<>();

        float trimStart(Track t) {
            float[] x = t == null ? null : trims.get(t.uri);
            return x != null && x[0] > 0.01f ? x[0] : startSec; // sonst "Start bei Sekunde" der Kachel
        }

        float trimEnd(Track t) {
            float[] x = t == null ? null : trims.get(t.uri);
            return x != null ? x[1] : 0f;
        }

        void setTrim(Track t, float start, float end) {
            if (start < 0.05f && end < 0.05f) trims.remove(t.uri);
            else trims.put(t.uri, new float[]{Math.max(0, start), Math.max(0, end)});
        }

        boolean isControl() {
            return action != ACT_SOUND;
        }

        /** kein Titel/Ordner -> nichts abzuspielen */
        boolean isEmpty() {
            return files.isEmpty() && folders.isEmpty();
        }

        boolean hasFolders() {
            return !folders.isEmpty();
        }

        /** ganz leer: weder Titel noch Bild noch Name (Kacheln nur mit Bild/Text sind Deko, z. B. Ueberschriften) */
        boolean isBlank() {
            return isEmpty() && image.isEmpty() && label.trim().isEmpty() && action == ACT_SOUND;
        }

        /** wird nie vorgemerkt: ausdruecklich so eingestellt oder unterbricht ohnehin (Durchsage / andere ausblenden) */
        boolean startsImmediately() {
            return immediate || others != OTHERS_KEEP;
        }

        boolean isPlaylist() {
            return !folders.isEmpty() || files.size() > 1;
        }

        List<Track> tracks() {
            return folders.isEmpty() ? files : folderTracks;
        }

        String foldersLabel() {
            if (folders.isEmpty()) return "";
            return folders.size() == 1 ? folders.get(0).name : folders.get(0).name + " +" + (folders.size() - 1);
        }

        /**
         * Der Titel, mit dem der naechste Start beginnt. Wird einmal festgelegt und bleibt, bis er gespielt
         * oder weitergeschaltet wurde - auch bei Zufall (dann vorab ausgelost), damit die Anzeige stimmt.
         */
        Track nextTrack() {
            List<Track> t = tracks();
            if (t.isEmpty()) return null;
            if (!nextUri.isEmpty()) for (Track x : t) if (x.uri.equals(nextUri)) return x;
            Track pick;
            if (shuffle && t.size() > 1) {
                // Zufall ohne Wiederholung: nur aus den Titeln, die in diesem Durchgang noch nicht liefen
                List<Track> u = undone();
                if (u.isEmpty()) {
                    runDone.clear(); // Liste durch -> neuer Durchgang
                    u = new ArrayList<>(t);
                }
                pick = u.get(RND.nextInt(u.size()));
                for (int i = 0; i < 6 && u.size() > 1 && pick.uri.equals(lastUri); i++) pick = u.get(RND.nextInt(u.size()));
            } else {
                pick = t.get(resume ? Math.floorMod(pos, t.size()) : 0);
            }
            nextUri = pick.uri;
            return pick;
        }

        /** naechsten Titel weiterschalten, bevor die Kachel gestartet wird (dir = +1 vor, -1 zurueck) */
        void skipNext(int dir) {
            List<Track> t = tracks();
            if (t.size() < 2) return;
            Track cur = nextTrack();
            int i = Math.max(0, t.indexOf(cur));
            Track pick;
            if (shuffle) {
                List<Track> u = undone();
                if (u.size() < 2) return; // nur noch ein Lied offen in diesem Durchgang
                pick = u.get(RND.nextInt(u.size()));
                for (int k = 0; k < 8 && pick == cur; k++) pick = u.get(RND.nextInt(u.size()));
            } else {
                int n = Math.floorMod(i + dir, t.size());
                pick = t.get(n);
                pos = n;
            }
            nextUri = pick.uri;
        }

        String title() {
            if (!label.trim().isEmpty()) return label.trim();
            if (isControl()) return ACT_ICONS[action] + "  " + ACT_NAMES[action];
            if (!folders.isEmpty()) return foldersLabel();
            if (!files.isEmpty()) return files.get(0).display();
            return "";
        }

        Btn copy() {
            try {
                return from(toJson());
            } catch (Exception e) {
                return new Btn();
            }
        }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            JSONArray rd = new JSONArray();
            for (String x : runDone) rd.put(x);
            o.put("run", rd);
            o.put("label", label).put("color", color).put("nextUri", nextUri).put("lastUri", lastUri)
                    .put("shuffle", shuffle).put("loop", loop).put("resume", resume)
                    .put("gainDb", gainDb).put("fadeIn", fadeIn).put("stopFade", stopFade)
                    .put("others", others).put("startSec", startSec).put("played", played).put("pos", pos)
                    .put("w", w).put("h", h).put("image", image).put("imageMode", imageMode)
                    .put("showLabel", showLabel).put("immediate", immediate).put("transparent", transparent).put("frame", frame)
                    .put("action", action);
            JSONObject tr = new JSONObject();
            for (Map.Entry<String, float[]> e : trims.entrySet())
                tr.put(e.getKey(), new JSONArray().put((double) e.getValue()[0]).put((double) e.getValue()[1]));
            o.put("trims", tr);
            JSONArray f = new JSONArray();
            for (Track t : files) f.put(t.toJson());
            o.put("files", f);
            JSONArray ft = new JSONArray();
            for (Track t : folderTracks) ft.put(t.toJson());
            o.put("folderTracks", ft);
            JSONArray fo = new JSONArray();
            for (Folder x : folders) fo.put(new JSONObject().put("u", x.uri).put("n", x.name));
            o.put("folders", fo);
            return o;
        }

        static Btn from(JSONObject o) {
            Btn b = new Btn();
            if (o == null) return b;
            b.label = o.optString("label", "");
            b.color = o.optInt("color", 0);
            JSONArray fo = o.optJSONArray("folders");
            if (fo != null) {
                for (int i = 0; i < fo.length(); i++) {
                    JSONObject x = fo.optJSONObject(i);
                    if (x != null && !x.optString("u").isEmpty()) b.folders.add(new Folder(x.optString("u"), x.optString("n", "Ordner")));
                }
            } else if (!o.optString("folder", "").isEmpty()) { // bis v0.5: genau ein Ordner
                b.folders.add(new Folder(o.optString("folder"), o.optString("folderName", "Ordner")));
            }
            b.nextUri = o.optString("nextUri", "");
            b.lastUri = o.optString("lastUri", "");
            JSONArray rd = o.optJSONArray("run");
            if (rd != null) for (int i = 0; i < rd.length(); i++) b.runDone.add(rd.optString(i));
            b.shuffle = o.optBoolean("shuffle", false);
            b.loop = o.optBoolean("loop", false);
            b.resume = o.optBoolean("resume", true);
            b.gainDb = (float) o.optDouble("gainDb", 0);
            b.fadeIn = (float) o.optDouble("fadeIn", 0);
            b.stopFade = (float) o.optDouble("stopFade", -1);
            b.others = o.optInt("others", OTHERS_KEEP);
            b.startSec = (float) o.optDouble("startSec", 0);
            b.played = o.optBoolean("played", false);
            b.pos = o.optInt("pos", 0);
            b.w = Math.max(1, Math.min(COLS, o.optInt("w", 1)));
            b.h = Math.max(1, Math.min(ROWS, o.optInt("h", 1)));
            b.image = o.optString("image", "");
            b.imageMode = o.optInt("imageMode", 0);
            b.showLabel = o.optBoolean("showLabel", true);
            b.immediate = o.optBoolean("immediate", false);
            b.transparent = o.optBoolean("transparent", false);
            b.frame = o.optBoolean("frame", false);
            b.action = o.optInt("action", ACT_SOUND);
            if (b.action < 0 || b.action >= ACT_NAMES.length) b.action = ACT_SOUND;
            JSONObject tr = o.optJSONObject("trims");
            if (tr != null) {
                java.util.Iterator<String> it = tr.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    JSONArray x = tr.optJSONArray(k);
                    if (x != null && x.length() >= 2) b.trims.put(k, new float[]{(float) x.optDouble(0, 0), (float) x.optDouble(1, 0)});
                }
            }
            JSONArray f = o.optJSONArray("files");
            if (f != null) for (int i = 0; i < f.length(); i++) b.files.add(Track.from(f.optJSONObject(i)));
            JSONArray ft = o.optJSONArray("folderTracks");
            if (ft != null) for (int i = 0; i < ft.length(); i++) {
                Track t = Track.from(ft.optJSONObject(i));
                if (t.src.isEmpty() && b.folders.size() == 1) t.src = b.folders.get(0).uri; // Altdaten
                b.folderTracks.add(t);
            }
            return b;
        }
    }

    // ------------------------------------------------------------------ ein Preset (eine Seite)
    static final class Preset {
        String name = "Neues Preset";
        /** Wappen/Logo in der Kopfleiste (Dateiname in files/img) */
        String logo = "";
        final Btn[] btns = new Btn[SLOTS];

        Preset() {
            for (int i = 0; i < SLOTS; i++) btns[i] = new Btn();
        }

        JSONObject toJson() throws Exception {
            JSONArray a = new JSONArray();
            for (Btn b : btns) a.put(b.toJson());
            return new JSONObject().put("name", name).put("logo", logo).put("btns", a);
        }

        /** true, wenn mindestens eine Kachel belegt ist */
        boolean anyUsed() {
            for (Btn b : btns) if (!b.isBlank()) return true;
            return false;
        }

        /**
         * Belegungsplan: owner[zelle] = Index der Kachel, die diese Zelle belegt (bei grossen Kacheln mehrere Zellen).
         * Leere Kacheln belegen nur ihre eigene Zelle. Ueberschneidungen gewinnt die zuerst kommende Kachel.
         */
        int[] owners() {
            int[] own = new int[SLOTS];
            java.util.Arrays.fill(own, -1);
            for (int i = 0; i < SLOTS; i++) {
                if (own[i] >= 0) continue;
                Btn b = btns[i];
                int c = i % COLS, r = i / COLS;
                int w = b.isBlank() ? 1 : Math.min(b.w, COLS - c), h = b.isBlank() ? 1 : Math.min(b.h, ROWS - r);
                for (int y = r; y < r + h; y++)
                    for (int x = c; x < c + w; x++)
                        if (own[y * COLS + x] < 0) own[y * COLS + x] = i;
            }
            return own;
        }

        /** passt eine Kachel der Groesse w x h an Position slot? (Zellen leer oder von "self" belegt) */
        String fits(int slot, int w, int h, int self) {
            int c = slot % COLS, r = slot / COLS;
            if (c + w > COLS || r + h > ROWS) return "reicht über den Rand";
            int[] own = owners();
            for (int y = r; y < r + h; y++)
                for (int x = c; x < c + w; x++) {
                    int cell = y * COLS + x;
                    if (cell == slot || cell == self) continue;
                    int o = own[cell];
                    if (o >= 0 && o != self && o != slot && !btns[o].isBlank()) return "Kachel " + (o + 1) + " ist im Weg";
                }
            return null;
        }

        static Preset from(JSONObject o) {
            Preset p = new Preset();
            p.name = o.optString("name", "Preset");
            p.logo = o.optString("logo", "");
            JSONArray a = o.optJSONArray("btns");
            if (a != null) for (int i = 0; i < Math.min(SLOTS, a.length()); i++) p.btns[i] = Btn.from(a.optJSONObject(i));
            return p;
        }
    }

    // ------------------------------------------------------------------ Board
    final List<Preset> presets = new ArrayList<>();
    /** Lautstaerke je Titel (dB), gilt ueberall, wo der Titel verwendet wird */
    final Map<String, Float> trackGain = new HashMap<>();
    /** gemessener Pegel je Titel (dBFS, RMS) - unter Adresse und Dateiname, wie trackGain */
    final Map<String, Float> loud = new HashMap<>();
    /** automatische Lautstaerke-Angleichung */
    boolean normOn = true;
    /** Zielpegel der Angleichung (dBFS RMS) */
    float normTarget = -16f;
    int current = 0;

    // Einstellungen
    float fadeButtonSec = 5f;     // FADE-Taste oben
    float stopFadeSec = 3f;       // Standard "Ausblenden beim Stoppen"
    float switchFadeSec = 1.5f;   // andere Sounds ausblenden beim Start einer Kachel
    float duckDb = -12f;          // "leiser" fuer Durchsagen
    boolean keepOnCharging = true;
    boolean keepOnAlways = false;
    int guardMs = 500;            // Schutz vor versehentlichem Doppeltipp
    boolean markPlayed = true;
    /** Antippen, waehrend Musik laeuft = vormerken */
    boolean queueOn = true;
    /** Vorgemerktes startet: false = nach dem Ausblenden, true = gleichzeitig (Ueberblenden) */
    boolean queueCrossfade = false;
    /** kurzes Ausblenden beim Weiterschalten innerhalb einer Playlist (s, 0 = hart) */
    float skipFadeSec = 0.7f;
    /** LEISER-Taste: laufende Musik um diesen Wert absenken (dB) */
    float manualDuckDb = -12f;
    /** neuer Tag: Gespielt-Markierungen bzw. Playlists automatisch zuruecksetzen */
    boolean dayResetPlayed = true, dayResetLists = false;
    String lastDay = "";
    /** Sperre: optionaler PIN ("" = ohne) */
    String lockPin = "";
    /** Fernbedienung: Tastencode -> Funktion (KEY_*) */
    final Map<Integer, Integer> keyMap = new HashMap<>();
    /** Titelanzeige aus MP3-Tags */
    boolean useTags = true;

    private final File file;

    private Board(Context c) {
        file = new File(c.getFilesDir(), "board.json");
    }

    Preset preset() {
        if (current < 0 || current >= presets.size()) current = 0;
        return presets.get(current);
    }

    /**
     * Lautstaerke eines Titels (dB). Gespeichert unter der Datei-Adresse UND unter dem Dateinamen:
     * wird dieselbe Datei spaeter ueber einen anderen Weg gewaehlt (anderer Ordner, "Zuletzt verwendet" ...),
     * findet die App den Wert ueber den Namen trotzdem wieder.
     */
    float gainOf(Track t) {
        if (t == null) return 0f;
        Float g = trackGain.get(t.uri);
        if (g == null && t.name != null) g = trackGain.get(nameKey(t.name));
        return g == null ? 0f : g;
    }

    void setGain(Track t, float db) {
        if (t == null) return;
        if (Math.abs(db) < 0.05f) {
            trackGain.remove(t.uri);
            if (t.name != null) trackGain.remove(nameKey(t.name));
        } else {
            trackGain.put(t.uri, db);
            if (t.name != null) trackGain.put(nameKey(t.name), db);
        }
    }

    /** gemessener Pegel oder null */
    Float levelOf(Track t) {
        if (t == null) return null;
        Float l = loud.get(t.uri);
        if (l == null && t.name != null) l = loud.get(nameKey(t.name));
        return l;
    }

    /** Platzhalter fuer "nicht messbar" - wird gespeichert, damit die Datei nicht bei jedem Start neu probiert wird */
    static final float UNMEASURABLE = -999f;

    void setLevel(Track t, float dbfs) {
        if (Float.isNaN(dbfs)) dbfs = UNMEASURABLE;
        loud.put(t.uri, dbfs);
        if (t.name != null) loud.put(nameKey(t.name), dbfs);
    }

    static boolean measurable(Float l) {
        return l != null && !Float.isNaN(l) && l > -900f;
    }

    /** automatische Korrektur fuer diesen Titel (dB), 0 wenn aus oder noch nicht gemessen */
    float autoGainOf(Track t) {
        if (!normOn) return 0f;
        Float l = levelOf(t);
        if (!measurable(l)) return 0f;
        return Math.max(-15f, Math.min(12f, normTarget - l));
    }

    private static String nameKey(String name) {
        return "name:" + name.trim().toLowerCase(java.util.Locale.ROOT);
    }

    static Board load(Context c) {
        Board b = new Board(c);
        try {
            if (b.file.exists()) {
                FileInputStream in = new FileInputStream(b.file);
                byte[] buf = new byte[(int) b.file.length()];
                int off = 0, n;
                while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
                in.close();
                b.fromJson(new JSONObject(new String(buf, "UTF-8")));
            }
        } catch (Exception ignored) {
            // kaputte Datei -> mit leerem Board starten (alte Datei bleibt als .bad liegen)
            b.file.renameTo(new File(b.file.getPath() + ".bad"));
            b.presets.clear();
        }
        if (b.presets.isEmpty()) {
            Preset p = new Preset();
            p.name = "Heimspiel";
            b.presets.add(p);
        }
        return b;
    }

    void save() {
        try {
            File tmp = new File(file.getPath() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(toJson().toString().getBytes("UTF-8"));
            out.getFD().sync();
            out.close();
            tmp.renameTo(file);
        } catch (Exception ignored) {
        }
    }

    JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("version", 3).put("current", current)
                .put("fadeButtonSec", fadeButtonSec).put("stopFadeSec", stopFadeSec)
                .put("switchFadeSec", switchFadeSec).put("duckDb", duckDb)
                .put("keepOnCharging", keepOnCharging).put("keepOnAlways", keepOnAlways)
                .put("guardMs", guardMs).put("markPlayed", markPlayed)
                .put("queueOn", queueOn).put("queueCrossfade", queueCrossfade)
                .put("skipFadeSec", skipFadeSec).put("manualDuckDb", manualDuckDb).put("useTags", useTags)
                .put("dayResetPlayed", dayResetPlayed).put("dayResetLists", dayResetLists).put("lastDay", lastDay)
                .put("lockPin", lockPin);
        JSONObject km = new JSONObject();
        for (Map.Entry<Integer, Integer> e : keyMap.entrySet()) km.put(String.valueOf(e.getKey()), e.getValue());
        o.put("keyMap", km);
        JSONArray ps = new JSONArray();
        for (Preset p : presets) ps.put(p.toJson());
        o.put("presets", ps);
        JSONObject tg = new JSONObject();
        for (Map.Entry<String, Float> e : trackGain.entrySet()) tg.put(e.getKey(), e.getValue());
        o.put("trackGain", tg);
        JSONObject ld = new JSONObject();
        for (Map.Entry<String, Float> e : loud.entrySet()) if (!Float.isNaN(e.getValue())) ld.put(e.getKey(), e.getValue());
        o.put("loud", ld);
        o.put("normOn", normOn).put("normTarget", normTarget);
        return o;
    }

    void fromJson(JSONObject o) {
        presets.clear();
        trackGain.clear();
        current = o.optInt("current", 0);
        fadeButtonSec = (float) o.optDouble("fadeButtonSec", 5);
        stopFadeSec = (float) o.optDouble("stopFadeSec", 3);
        switchFadeSec = (float) o.optDouble("switchFadeSec", 1.5);
        duckDb = (float) o.optDouble("duckDb", -12);
        keepOnCharging = o.optBoolean("keepOnCharging", true);
        keepOnAlways = o.optBoolean("keepOnAlways", false);
        guardMs = o.optInt("guardMs", 500);
        markPlayed = o.optBoolean("markPlayed", true);
        queueOn = o.optBoolean("queueOn", true);
        queueCrossfade = o.optBoolean("queueCrossfade", false);
        skipFadeSec = (float) o.optDouble("skipFadeSec", 0.7);
        manualDuckDb = (float) o.optDouble("manualDuckDb", -12);
        useTags = o.optBoolean("useTags", true);
        dayResetPlayed = o.optBoolean("dayResetPlayed", true);
        dayResetLists = o.optBoolean("dayResetLists", false);
        lastDay = o.optString("lastDay", "");
        lockPin = o.optString("lockPin", "");
        keyMap.clear();
        JSONObject km = o.optJSONObject("keyMap");
        if (km != null) {
            java.util.Iterator<String> it = km.keys();
            while (it.hasNext()) {
                String k = it.next();
                try {
                    keyMap.put(Integer.parseInt(k), km.optInt(k));
                } catch (Exception ignored) {
                }
            }
        }
        Track.useTags = useTags;
        JSONArray ps = o.optJSONArray("presets");
        if (ps != null) for (int i = 0; i < ps.length(); i++) presets.add(Preset.from(ps.optJSONObject(i)));
        loud.clear();
        JSONObject ld = o.optJSONObject("loud");
        if (ld != null) {
            java.util.Iterator<String> it = ld.keys();
            while (it.hasNext()) {
                String k = it.next();
                loud.put(k, (float) ld.optDouble(k, -16));
            }
        }
        normOn = o.optBoolean("normOn", true);
        normTarget = (float) o.optDouble("normTarget", -16);
        JSONObject tg = o.optJSONObject("trackGain");
        if (tg != null) {
            java.util.Iterator<String> it = tg.keys();
            while (it.hasNext()) {
                String k = it.next();
                trackGain.put(k, (float) tg.optDouble(k, 0));
            }
        }
    }
}
