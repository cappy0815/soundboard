package de.justus.soundboard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Hauptbildschirm (nur Querformat): Kopfleiste, Kachelraster 8 x 5, Fussleiste mit laufenden Sounds und Lautstaerke.
 */
public class MainActivity extends Activity implements Engine.Listener {

    static final int REQ_FILES = 11, REQ_FOLDER = 12, REQ_EXPORT = 13, REQ_IMPORT = 14, REQ_IMAGE = 15, REQ_LOGO = 16;

    final Handler ui = new Handler(Looper.getMainLooper());
    Board board;
    Engine engine;
    Editor editor; // gerade offener Kachel-Editor (bekommt die Dateiauswahl)

    private final Tile[] tiles = new Tile[Board.SLOTS];
    private final long[] lastTap = new long[Board.SLOTS];
    private boolean editMode;
    private int moveFrom = -1;

    private ImageView ivLogo;
    private TextView tvClock, tvBattery, tvTitle, tvPresetNo, btEdit, btFade, btStop, btMute, tvHint;
    private LinearLayout chips;
    private Fader fVol, fGain;
    private AudioManager am;
    private final Map<Engine.Voice, TextView> chipTimes = new HashMap<>();

    private boolean plugged;
    private int battery = -1;
    private long backAt;

    // ------------------------------------------------------------------ Lebenszyklus
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        board = Board.load(this);
        engine = new Engine(this, board, this);
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        setContentView(buildUi());
        refreshAll();
        registerReceiver(batteryRx, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        Images.cleanup(this, board);
        ui.post(clockTick);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        checkNewDay();
        rescanFolders(false);
        analyzeLoudness(false); // Einzeltitel ohne Messwert (Ordner folgen nach dem Einlesen)
    }

    /** erster Start an einem neuen Tag: Gespielt-Markierungen / Playlists zuruecksetzen (Einstellungen) */
    private void checkNewDay() {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(new Date());
        if (today.equals(board.lastDay)) return;
        boolean first = board.lastDay.isEmpty();
        board.lastDay = today;
        if (!first && (board.dayResetPlayed || board.dayResetLists) && !engine.anyPlaying()) {
            for (Board.Preset p : board.presets) {
                for (Board.Btn b : p.btns) {
                    if (board.dayResetPlayed) b.played = false;
                    if (board.dayResetLists) {
                        b.pos = 0;
                        b.nextUri = "";
                        b.lastUri = "";
                        b.runDone.clear();
                    }
                }
            }
            toast("Neuer Spieltag: " + (board.dayResetPlayed && board.dayResetLists ? "Markierungen und Playlists"
                    : board.dayResetPlayed ? "Gespielt-Markierungen" : "Playlists") + " zurückgesetzt");
            refreshTiles();
        }
        board.save();
    }

    @Override
    protected void onPause() {
        board.save();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(batteryRx);
        } catch (Exception ignored) {
        }
        engine.release();
        board.save();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    /** Vollbild: Status- und Navigationsleiste ausblenden (Wischen vom Rand holt sie kurz zurueck) */
    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    /** Steuer-Funktion ausfuehren (Steuer-Kachel oder Fernbedienung) */
    void runAction(int act) {
        switch (act) {
            case Board.KEY_STOP:
                engine.stopAllAsSet();
                break;
            case Board.KEY_FADE:
                engine.fadeAll();
                break;
            case Board.KEY_KILL:
                engine.stopAll();
                break;
            case Board.KEY_NEXT:
                if (!engine.skipLatestPlaylist()) toast("Keine Playlist läuft");
                break;
            case Board.KEY_MUTE:
                engine.toggleMute();
                break;
            case Board.KEY_PREV_PRESET:
                switchPreset(board.current - 1);
                break;
            case Board.KEY_NEXT_PRESET:
                switchPreset(board.current + 1);
                break;
            case Board.KEY_DUCK:
                if (!engine.toggleManualDuck()) toast("Es läuft nichts, das leiser werden könnte");
                break;
        }
    }

    /**
     * Fernbedienung (Bluetooth-Klicker, Fusschalter ...): angelernte Tasten loesen ihre Funktion aus -
     * auch bei gesperrtem Bildschirm. Nur der erste Druck zaehlt (gehaltene Taste wiederholt nicht).
     */
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        Integer act = board.keyMap.get(e.getKeyCode());
        if (act != null && act > 0) {
            if (e.getAction() == android.view.KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) runAction(act);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // Schutz im Spielbetrieb: zweimal Zurueck zum Beenden
        if (locked) return;
        if (editMode) {
            setEditMode(false);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - backAt < 2000) {
            quit();
            return;
        }
        backAt = now;
        toast("Zum Beenden nochmal „Zurück“ drücken");
    }

    // ------------------------------------------------------------------ Akku + Display an beim Laden
    private final BroadcastReceiver batteryRx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            int lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            battery = lvl >= 0 && scale > 0 ? Math.round(lvl * 100f / scale) : -1;
            plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            applyKeepOn();
            updateBattery();
        }
    };

    void applyKeepOn() {
        boolean on = board.keepOnAlways || (board.keepOnCharging && plugged);
        if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        updateBattery();
    }

    private void updateBattery() {
        if (tvBattery == null) return;
        boolean on = board.keepOnAlways || (board.keepOnCharging && plugged);
        String s = (battery >= 0 ? battery + " %" : "–") + (plugged ? " ⚡" : "") + (on ? "  ☀" : "");
        tvBattery.setText(s);
        tvBattery.setTextColor(battery >= 0 && battery <= 20 && !plugged ? Util.RED : Util.MUTED);
    }

    // ------------------------------------------------------------------ Takt fuer Uhr, Fortschritt, Restzeiten
    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            tvClock.setText(new SimpleDateFormat("HH:mm", Locale.GERMANY).format(new Date()));
            for (Tile t : tiles) if (t.voice != null) t.invalidate();
            for (Map.Entry<Engine.Voice, TextView> e : chipTimes.entrySet()) {
                Engine.Voice v = e.getKey();
                e.getValue().setText(v.paused ? "❚❚" : (v.stopping ? "↘" : "-" + Util.fmtTime(v.duration() - v.position())));
            }
            syncVolume();
            ui.postDelayed(this, engine.anyPlaying() ? 200 : 1000);
        }
    };

    // ------------------------------------------------------------------ Aufbau
    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Util.BG);
        int p = dp(10);
        root.setPadding(p, dp(6), p, dp(6));

        root.addView(buildTopBar(), new LinearLayout.LayoutParams(-1, dp(62)));

        Grid grid = new Grid(this);
        for (int i = 0; i < Board.SLOTS; i++) {
            final int slot = i;
            Tile t = new Tile(this);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    tap(slot);
                }
            });
            t.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    longTap(slot);
                    return true;
                }
            });
            tiles[i] = t;
            grid.addView(t);
        }
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, 0, 1f);
        glp.topMargin = dp(6);
        glp.bottomMargin = dp(6);
        root.addView(grid, glp);

        root.addView(buildBottomBar(), new LinearLayout.LayoutParams(-1, dp(58)));

        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        frame.addView(root, new android.widget.FrameLayout.LayoutParams(-1, -1));
        frame.addView(buildLockOverlay(), new android.widget.FrameLayout.LayoutParams(-1, -1));
        return frame;
    }

    // ------------------------------------------------------------------ Sperre
    private boolean locked;
    private View lockOverlay;

    /** Sperre: alles abgedeckt, laufende Sounds spielen weiter. Entsperren: Schloss gedrueckt halten (+ optional PIN) */
    private View buildLockOverlay() {
        android.widget.FrameLayout ov = new android.widget.FrameLayout(this);
        ov.setBackgroundColor(Color.argb(150, 5, 7, 10));
        ov.setClickable(true); // schluckt alle Beruehrungen
        ov.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toast("Gesperrt – Schloss in der Mitte gedrückt halten");
            }
        });
        LinearLayout badge = new LinearLayout(this);
        badge.setOrientation(LinearLayout.VERTICAL);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(36), dp(22), dp(36), dp(22));
        GradientDrawable g = rounded(Util.SURFACE, 24);
        g.setStroke(dp(2), Util.AMBER);
        badge.setBackground(g);
        badge.addView(Ui.text(this, "🔒", 44, Util.TEXT, false));
        TextView t = Ui.text(this, "Gesperrt", 22, Util.TEXT, true);
        t.setGravity(Gravity.CENTER);
        badge.addView(t);
        TextView h = Ui.text(this, "zum Entsperren hier gedrückt halten", 13, Util.MUTED, false);
        h.setGravity(Gravity.CENTER);
        badge.addView(h);
        badge.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                tryUnlock();
                return true;
            }
        });
        ov.addView(badge, new android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        ov.setVisibility(View.GONE);
        lockOverlay = ov;
        return ov;
    }

    void setLocked(boolean on) {
        locked = on;
        if (on && editMode) setEditMode(false);
        lockOverlay.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) toast("Gesperrt – Sounds laufen weiter");
    }

    private void tryUnlock() {
        if (board.lockPin.isEmpty()) {
            setLocked(false);
            return;
        }
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        et.setTextColor(Util.TEXT);
        et.setGravity(Gravity.CENTER);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(et, new LinearLayout.LayoutParams(-1, -2));
        Ui.dialog(new AlertDialog.Builder(this, Ui.DIALOG_THEME)
                .setTitle("PIN eingeben")
                .setView(box)
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Entsperren", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (et.getText().toString().trim().equals(board.lockPin)) setLocked(false);
                        else toast("Falscher PIN");
                    }
                })
                .show());
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), 0, dp(8), 0);
        bar.setBackground(rounded(Util.SURFACE, 16));

        final TextView menu = iconButton("☰", 24);
        menu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu(menu);
            }
        });
        bar.addView(menu);
        TextView gear = iconButton("⚙", 22);
        gear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Dialogs.settings(MainActivity.this);
            }
        });
        bar.addView(gear);
        TextView help = iconButton("?", 22);
        help.setTypeface(Typeface.DEFAULT_BOLD);
        help.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Dialogs.help(MainActivity.this);
            }
        });
        bar.addView(help);

        LinearLayout status = new LinearLayout(this);
        status.setOrientation(LinearLayout.VERTICAL);
        status.setPadding(dp(8), 0, 0, 0);
        tvClock = Ui.text(this, "--:--", 22, Util.AMBER, true);
        tvBattery = Ui.text(this, "", 12, Util.MUTED, false);
        status.addView(tvClock);
        status.addView(tvBattery);
        bar.addView(status, new LinearLayout.LayoutParams(dp(100), -2));

        // Preset-Pfeile, Wappen, Preset-Name (links buendig, darf lang sein)
        TextView prev = iconButton("‹", 36);
        prev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchPreset(board.current - 1);
            }
        });
        TextView next = iconButton("›", 36);
        next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchPreset(board.current + 1);
            }
        });
        bar.addView(prev);
        bar.addView(next);
        ivLogo = new ImageView(this);
        ivLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        ivLogo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickPreset();
            }
        });
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(54), dp(54));
        llp.leftMargin = dp(10);
        bar.addView(ivLogo, llp);
        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setGravity(Gravity.CENTER_VERTICAL);
        titleBox.setPadding(dp(12), 0, dp(12), 0);
        tvTitle = Ui.text(this, "", 27, Util.TEXT, true);
        tvTitle.setIncludeFontPadding(false);
        tvTitle.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvPresetNo = Ui.text(this, "", 11, Util.MUTED, false);
        tvPresetNo.setIncludeFontPadding(false);
        tvPresetNo.setPadding(0, dp(2), 0, 0);
        titleBox.addView(tvTitle);
        titleBox.addView(tvPresetNo);
        titleBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickPreset();
            }
        });
        bar.addView(titleBox, new LinearLayout.LayoutParams(0, -1, 1f));

        // rechts: EDIT / FADE / STOP / MUTE
        btEdit = topButton("Bearbeiten", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setEditMode(!editMode);
            }
        });
        btFade = topButton("Fade", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                engine.fadeAll();
            }
        });
        btStop = topButton("Stopp", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (engine.anyPlaying()) toast("Zum harten Stopp aller Sounds gedrückt halten");
            }
        });
        btStop.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                engine.stopAll();
                return true;
            }
        });
        btMute = topButton("Mute", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                engine.toggleMute();
            }
        });
        bar.addView(btEdit);
        bar.addView(btFade);
        bar.addView(btStop);
        bar.addView(btMute);
        TextView lock = iconButton("🔒", 22);
        lock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setLocked(true);
            }
        });
        LinearLayout.LayoutParams lkp = new LinearLayout.LayoutParams(-2, -1);
        lkp.leftMargin = dp(6);
        bar.addView(lock, lkp);
        styleTopButtons();
        return bar;
    }

    private View buildBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), 0, dp(12), 0);
        bar.setBackground(rounded(Util.SURFACE, 16));

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout inner = new LinearLayout(this);
        inner.setGravity(Gravity.CENTER_VERTICAL);
        tvHint = Ui.text(this, "", 13, Util.MUTED, false);
        inner.addView(tvHint);
        chips = new LinearLayout(this);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        inner.addView(chips);
        hs.addView(inner);
        bar.addView(hs, new LinearLayout.LayoutParams(0, -1, 1f));

        // Track-Gain: Lautstaerke des laufenden Titels (dauerhaft fuer diesen Titel gespeichert)
        fGain = new Fader(this);
        fGain.min = -24f;
        fGain.max = 12f;
        fGain.step = 0.5f;
        fGain.bipolar = true;
        fGain.resetTo = 0f;
        fGain.label = "TRACK";
        fGain.colorPos = Util.GREEN;
        fGain.colorNeg = Util.color(5); // Blau fuer "leiser"
        fGain.fmt = new Fader.Fmt() {
            @Override
            public String fmt(float v) {
                return Util.fmtDb(v);
            }
        };
        fGain.listener = new Fader.Listener() {
            @Override
            public void onValue(float v) {
                engine.setTrackGain(engine.gainTarget(), v);
                ui.removeCallbacks(saveLater);
                ui.postDelayed(saveLater, 800);
            }
        };
        // "TRACK" antippen = naechsten laufenden Titel an den Regler haengen
        fGain.onLabelTap = new Runnable() {
            @Override
            public void run() {
                if (engine.voices.size() > 1) {
                    engine.cycleGainTarget();
                    Engine.Voice t = engine.gainTarget();
                    if (t != null) toast("Track-Gain regelt jetzt: " + t.trackName());
                }
            }
        };
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(270), dp(46));
        glp.leftMargin = dp(12);
        bar.addView(fGain, glp);

        // Medienlautstaerke des Tablets
        fVol = new Fader(this);
        fVol.min = 0;
        fVol.max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        fVol.step = 1;
        fVol.label = "MASTER";
        fVol.colorPos = Util.AMBER;
        final float volMax = fVol.max;
        fVol.fmt = new Fader.Fmt() {
            @Override
            public String fmt(float v) {
                return Math.round(v * 100f / volMax) + " %";
            }
        };
        fVol.listener = new Fader.Listener() {
            @Override
            public void onValue(float v) {
                try {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(v), 0);
                } catch (Exception ignored) {
                }
            }
        };
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(dp(270), dp(46));
        vlp.leftMargin = dp(10);
        bar.addView(fVol, vlp);
        syncVolume();
        syncTrackGain();
        return bar;
    }

    /** Master-Fader an die Medienlautstaerke angleichen (z. B. nach den Lautstaerketasten) */
    private void syncVolume() {
        if (fVol == null || fVol.touching) return;
        int v = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (Math.round(fVol.value) != v) fVol.setValue(v);
    }

    /** Track-Gain-Fader an den aktuell geregelten Titel anpassen */
    private void syncTrackGain() {
        if (fGain == null) return;
        Engine.Voice t = engine.gainTarget();
        boolean on = t != null && t.cur != null;
        fGain.setEnabled(on);
        String lbl = on && engine.voices.size() > 1 ? "TRACK ⇄" : "TRACK";
        // automatische Angleichung des laufenden Titels klein mit anzeigen (Regler = eigene Korrektur)
        if (on && board.normOn) {
            Float lv = board.levelOf(t.cur);
            if (lv == null) lbl += " · auto …";
            else if (Board.measurable(lv)) lbl += " · auto " + Util.fmtDb(board.autoGainOf(t.cur)).replace(" dB", "");
        }
        fGain.label = lbl;
        fGain.invalidate();
        if (fGain.touching) return;
        fGain.setValue(on ? board.gainOf(t.cur) : 0f);
    }

    private TextView iconButton(String s, float sp) {
        TextView t = Ui.text(this, s, sp, Util.TEXT, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), 0, dp(10), dp(2));
        t.setBackgroundResource(android.R.drawable.list_selector_background);
        return t;
    }

    private TextView topButton(String label, View.OnClickListener l) {
        TextView b = Ui.text(this, label, 15, Util.TEXT, true);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(104), dp(44));
        lp.leftMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private void styleTopButtons() {
        if (editMode) Ui.pill(btEdit, Util.AMBER);
        else Ui.outline(btEdit, Util.TEXT);
        Ui.outline(btFade, Util.TEXT);
        Ui.outline(btStop, Util.RED);
        if (engine.isMuted()) Ui.pill(btMute, Util.RED);
        else Ui.outline(btMute, Util.TEXT);
        for (TextView b : new TextView[]{btEdit, btFade, btStop, btMute}) b.setPadding(0, 0, 0, 0);
        // Schutz: waehrend etwas laeuft, laesst sich "Bearbeiten" nicht einschalten (liegt direkt neben FADE)
        boolean lockEdit = !editMode && engine.anyPlaying();
        btEdit.setEnabled(!lockEdit);
        btEdit.setAlpha(lockEdit ? 0.3f : 1f);
    }

    private GradientDrawable rounded(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(r));
        return g;
    }

    int dp(float v) {
        return Ui.dp(this, v);
    }

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ Kachelraster
    private static final class Grid extends ViewGroup {
        private final int gap;

        Grid(Context c) {
            super(c);
            gap = Ui.dp(c, 8);
        }

        @Override
        protected void onMeasure(int ws, int hs) {
            int w = MeasureSpec.getSize(ws), h = MeasureSpec.getSize(hs);
            setMeasuredDimension(w, h);
            float cw = Math.max(0, (w - gap * (Board.COLS - 1)) / (float) Board.COLS), ch = Math.max(0, (h - gap * (Board.ROWS - 1)) / (float) Board.ROWS);
            for (int i = 0; i < getChildCount(); i++) {
                Tile t = (Tile) getChildAt(i);
                int tw = Math.round(cw * t.spanW + gap * (t.spanW - 1)), th = Math.round(ch * t.spanH + gap * (t.spanH - 1));
                t.measure(MeasureSpec.makeMeasureSpec(Math.max(0, tw), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(Math.max(0, th), MeasureSpec.EXACTLY));
            }
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int w = r - l, h = b - t;
            float cw = (w - gap * (Board.COLS - 1)) / (float) Board.COLS, ch = (h - gap * (Board.ROWS - 1)) / (float) Board.ROWS;
            for (int i = 0; i < getChildCount(); i++) {
                Tile tile = (Tile) getChildAt(i);
                int c = i % Board.COLS, row = i / Board.COLS;
                int x = Math.round(c * (cw + gap)), y = Math.round(row * (ch + gap));
                tile.layout(x, y, x + tile.getMeasuredWidth(), y + tile.getMeasuredHeight());
            }
        }
    }

    private void tap(int slot) {
        if (editMode) {
            if (moveFrom >= 0) {
                finishMove(slot);
                return;
            }
            editor = new Editor(this, slot);
            editor.show();
            return;
        }
        // Schutz gegen versehentlichen Doppeltipp
        long now = System.currentTimeMillis();
        if (now - lastTap[slot] < board.guardMs) return;
        lastTap[slot] = now;
        Board.Btn b = board.preset().btns[slot];
        if (b.isControl()) {
            tiles[slot].performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            runAction(Board.keyOfAction(b.action));
            return;
        }
        if (b.isEmpty()) return;
        tiles[slot].performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
        engine.press(board.current, slot);
    }

    private void longTap(int slot) {
        if (editMode) {
            if (board.preset().btns[slot].isBlank() && moveFrom < 0) return;
            if (engine.voice(board.current, slot) != null) {
                toast("Erst stoppen, dann verschieben");
                return;
            }
            moveFrom = slot;
            tiles[slot].performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            tvHint.setText("Verschieben: Ziel-Kachel antippen (belegte Kacheln werden getauscht) · gleiche Kachel = abbrechen");
            refreshTiles();
            return;
        }
        if (engine.voice(board.current, slot) != null) {
            tiles[slot].performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            engine.togglePause(board.current, slot);
            return;
        }
        // ruhende Playlist: gedrueckt halten = naechsten Titel weiterschalten (vor dem Start)
        Board.Btn b = board.preset().btns[slot];
        if (b.isPlaylist() && b.tracks().size() > 1) {
            tiles[slot].performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            b.skipNext(+1);
            tiles[slot].invalidate();
            ui.removeCallbacks(saveLater);
            ui.postDelayed(saveLater, 1500);
        }
    }

    /**
     * Verschieben: zwei 1x1-Kacheln werden getauscht; eine grosse Kachel darf nur auf einen freien Platz,
     * auf den sie in voller Groesse passt.
     */
    private void finishMove(int to) {
        int from = moveFrom;
        moveFrom = -1;
        Board.Preset p = board.preset();
        Board.Btn[] bs = p.btns;
        if (to != from) {
            Board.Btn src = bs[from], dst = bs[to];
            boolean small = src.w == 1 && src.h == 1 && (dst.isBlank() || (dst.w == 1 && dst.h == 1));
            if (engine.voice(board.current, to) != null) {
                toast("Ziel-Kachel läuft gerade – erst stoppen");
            } else if (small) {
                bs[to] = src;
                bs[from] = dst;
                board.save();
            } else if (!dst.isBlank()) {
                toast("Große Kacheln nur auf einen freien Platz verschieben");
            } else {
                String why = p.fits(to, src.w, src.h, from);
                if (why != null) {
                    toast("Passt dort nicht: " + why);
                } else {
                    bs[to] = src;
                    bs[from] = new Board.Btn();
                    board.save();
                }
            }
        }
        updateHint();
        refreshTiles();
    }

    void setEditMode(boolean on) {
        editMode = on;
        moveFrom = -1;
        styleTopButtons();
        updateHint();
        refreshTiles();
    }

    private void updateHint() {
        tvHint.setText(editMode ? "✎  Kachel antippen = einrichten · gedrückt halten = verschieben" : "");
        tvHint.setTextColor(Util.AMBER);
        tvHint.setPadding(0, 0, editMode ? dp(16) : 0, 0);
    }

    void refreshAll() {
        Board.Preset p = board.preset();
        tvTitle.setText(p.name);
        tvPresetNo.setText("Preset " + (board.current + 1) + " von " + board.presets.size());
        android.graphics.Bitmap logo = Images.get(this, p.logo);
        ivLogo.setImageBitmap(logo);
        ivLogo.setVisibility(logo == null ? View.GONE : View.VISIBLE);
        for (Tile t : tiles) t.invalidateText();
        refreshTiles();
        rebuildChips();
        applyKeepOn();
    }

    /**
     * Kacheln aktualisieren. Grosse Kacheln verdecken die Zellen rechts/unten.
     * Im Spielbetrieb werden leere Kacheln ausgeblendet (ausser das Preset ist noch ganz leer).
     */
    void refreshTiles() {
        Board.Preset p = board.preset();
        Board.Btn[] bs = p.btns;
        int[] own = p.owners();
        boolean hideEmpty = !editMode && p.anyUsed();
        boolean relayout = false;
        for (int i = 0; i < Board.SLOTS; i++) {
            Tile t = tiles[i];
            Board.Btn b = bs[i];
            if (t.btn != b) t.invalidateText();
            t.btn = b;
            t.voice = engine.voice(board.current, i);
            t.editMode = editMode;
            t.selected = i == moveFrom;
            t.stopSec = engine.stopSec(b);
            t.queuePos = engine.queuePos(board.current, i);
            t.armed = b.action == Board.ACT_NEXT ? engine.anyPlaylistPlaying()
                    : b.action == Board.ACT_DUCK ? engine.manualDucked() : engine.anyPlaying();
            int c = i % Board.COLS, r = i / Board.COLS;
            int sw = b.isBlank() ? 1 : Math.min(b.w, Board.COLS - c), sh = b.isBlank() ? 1 : Math.min(b.h, Board.ROWS - r);
            if (sw != t.spanW || sh != t.spanH) {
                t.spanW = sw;
                t.spanH = sh;
                t.invalidateText();
                relayout = true;
            }
            int vis = own[i] != i ? View.GONE : (hideEmpty && b.isBlank() ? View.INVISIBLE : View.VISIBLE);
            if (t.getVisibility() != vis) {
                t.setVisibility(vis);
                relayout = true;
            }
            t.invalidate();
        }
        if (relayout) tiles[0].getParent().requestLayout();
    }

    // ------------------------------------------------------------------ laufende Sounds unten
    private void rebuildChips() {
        chips.removeAllViews();
        chipTimes.clear();
        for (final Engine.Voice v : engine.voices) {
            LinearLayout chip = new LinearLayout(this);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(dp(12), 0, dp(4), 0);
            int col = Util.color(v.btn.color);
            chip.setBackground(rounded(Util.mix(Util.SURFACE2, col, 0.55f), 20));
            TextView name = Ui.text(this, v.btn.title() + (v.btn.isPlaylist() && v.order.size() > 1
                    ? "  ·  " + v.btn.counterPlaying(v.cur) + "/" + v.btn.tracks().size() + "  ·  " + v.trackName() : ""), 14, Color.WHITE, true);
            name.setSingleLine(true);
            name.setMaxWidth(dp(320));
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            chip.addView(name);
            TextView time = Ui.text(this, "", 14, Color.WHITE, false);
            time.setPadding(dp(10), 0, dp(4), 0);
            chip.addView(time);
            chipTimes.put(v, time);
            if (v.btn.isPlaylist()) {
                TextView list = chipButton("☰");
                list.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View x) {
                        pickTrack(v);
                    }
                });
                chip.addView(list, chipGap(6));
                TextView skip = chipButton("⏭");
                skip.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View x) {
                        engine.skip(v);
                    }
                });
                chip.addView(skip, chipGap(8));
            }
            TextView stop = chipButton("■");
            stop.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View x) {
                    engine.stop(v, 0);
                }
            });
            chip.addView(stop, chipGap(18)); // Stopp mit mehr Abstand, damit man ihn nicht statt ⏭ erwischt
            // Chip antippen = ausblenden wie auf der Kachel eingestellt
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View x) {
                    engine.stop(v, engine.stopSec(v.btn));
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(48));
            lp.rightMargin = dp(8);
            chips.addView(chip, lp);
        }
        // vorgemerkte Kacheln (Warteschlange)
        for (int i = 0; i < engine.queue.size(); i++) {
            final int qi = i;
            int[] q = engine.queue.get(i);
            if (q[0] >= board.presets.size()) continue;
            Board.Btn b = board.presets.get(q[0]).btns[q[1]];
            LinearLayout chip = new LinearLayout(this);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(dp(12), 0, dp(4), 0);
            GradientDrawable g = rounded(Util.SURFACE2, 20);
            g.setStroke(dp(1.5f), Util.AMBER);
            chip.setBackground(g);
            TextView name = Ui.text(this, (i == 0 ? "⏭ als Nächstes: " : "⏭ " + (i + 1) + ". ") + b.title(), 14, Util.AMBER, true);
            name.setSingleLine(true);
            name.setMaxWidth(dp(300));
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            chip.addView(name);
            TextView del = chipButton("✕");
            del.setTextColor(Util.AMBER);
            del.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View x) {
                    engine.unqueue(qi);
                }
            });
            chip.addView(del);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(48));
            lp.rightMargin = dp(8);
            chips.addView(chip, lp);
        }
        if (!editMode) {
            boolean idle = engine.voices.isEmpty() && engine.queue.isEmpty();
            tvHint.setText(idle ? (normProgress.isEmpty() ? "Antippen = Start/Stopp · gedrückt halten = Pause" : normProgress) : "");
            tvHint.setTextColor(Util.alpha(Util.MUTED, 0.7f));
        }
    }

    /** Titel einer laufenden Playlist direkt auswaehlen (harter Wechsel) */
    private void pickTrack(final Engine.Voice v) {
        final String[] names = new String[v.order.size()];
        for (int i = 0; i < names.length; i++) names[i] = (i + 1) + ".  " + v.order.get(i).display();
        Ui.dialog(new AlertDialog.Builder(this, Ui.DIALOG_THEME)
                .setTitle(v.btn.title() + " – Titel wählen")
                .setSingleChoiceItems(names, v.idx, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        engine.jumpTo(v, which);
                    }
                })
                .setNegativeButton("Schließen", null)
                .show());
    }

    /** Knopf im Chip unten: gross genug fuer dicke Finger, mit leichtem Hintergrund als Trefferflaeche */
    private TextView chipButton(String s) {
        TextView t = Ui.text(this, s, 20, Color.WHITE, false);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = rounded(Color.argb(45, 255, 255, 255), 14);
        t.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(Color.argb(90, 255, 255, 255)), g, null));
        return t;
    }

    /** Abstand links vor einem Chip-Knopf */
    private LinearLayout.LayoutParams chipGap(int leftDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(56), dp(40));
        lp.leftMargin = dp(leftDp);
        return lp;
    }

    // ------------------------------------------------------------------ Engine-Rueckmeldungen
    private final Runnable saveLater = new Runnable() {
        @Override
        public void run() {
            board.save();
        }
    };

    @Override
    public void onEngineChanged() {
        playingNow = engine.anyPlaying();
        syncTrackGain();
        refreshTiles();
        rebuildChips();
        styleTopButtons();
        ui.removeCallbacks(clockTick);
        ui.post(clockTick);
        ui.removeCallbacks(saveLater);
        ui.postDelayed(saveLater, 1500);
    }

    @Override
    public void onEngineError(String msg) {
        toast(msg);
    }

    // ------------------------------------------------------------------ Presets
    void switchPreset(int idx) {
        int n = board.presets.size();
        if (n == 0) return;
        board.current = ((idx % n) + n) % n;
        moveFrom = -1;
        board.save();
        refreshAll();
    }

    private void pickPreset() {
        final String[] names = new String[board.presets.size()];
        for (int i = 0; i < names.length; i++) names[i] = (i + 1) + "   " + board.presets.get(i).name;
        Ui.dialog(new AlertDialog.Builder(this, Ui.DIALOG_THEME)
                .setTitle("Preset wählen")
                .setSingleChoiceItems(names, board.current, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        switchPreset(which);
                    }
                })
                .setNegativeButton("Schließen", null)
                .show());
    }

    private void askName(String title, String preset, final Ui.OnValue unused, final NameCb cb) {
        final EditText et = new EditText(this);
        et.setText(preset);
        et.setSelectAllOnFocus(true);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        et.setTextColor(Util.TEXT);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(et, new LinearLayout.LayoutParams(-1, -2));
        Ui.dialog(new AlertDialog.Builder(this, Ui.DIALOG_THEME)
                .setTitle(title)
                .setView(box)
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String s = et.getText().toString().trim();
                        if (!s.isEmpty()) cb.name(s);
                    }
                })
                .show());
    }

    interface NameCb {
        void name(String s);
    }

    private void showMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        Menu m = pm.getMenu();
        m.add(0, 1, 0, "Preset wählen …");
        m.add(0, 2, 1, "Neues Preset");
        m.add(0, 3, 2, "Preset umbenennen");
        m.add(0, 4, 3, "Preset duplizieren");
        m.add(0, 5, 4, "Preset löschen");
        m.add(0, 12, 4, "Wappen/Logo für dieses Preset …");
        if (!board.preset().logo.isEmpty()) m.add(0, 13, 4, "Wappen/Logo entfernen");
        m.add(0, 6, 5, "Gespielt-Markierungen zurücksetzen");
        m.add(0, 7, 6, "Ordner neu einlesen");
        m.add(0, 10, 7, "Sicherung exportieren …");
        m.add(0, 11, 8, "Sicherung importieren …");
        m.add(0, 99, 11, "Beenden");
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                menuAction(item.getItemId());
                return true;
            }
        });
        pm.show();
    }

    private void menuAction(int id) {
        switch (id) {
            case 1:
                pickPreset();
                break;
            case 2:
                askName("Neues Preset", "Preset " + (board.presets.size() + 1), null, new NameCb() {
                    @Override
                    public void name(String s) {
                        Board.Preset p = new Board.Preset();
                        p.name = s;
                        p.logo = board.preset().logo; // Wappen uebernehmen
                        board.presets.add(p);
                        switchPreset(board.presets.size() - 1);
                    }
                });
                break;
            case 3:
                askName("Preset umbenennen", board.preset().name, null, new NameCb() {
                    @Override
                    public void name(String s) {
                        board.preset().name = s;
                        board.save();
                        refreshAll();
                    }
                });
                break;
            case 4:
                try {
                    Board.Preset c = Board.Preset.from(board.preset().toJson());
                    c.name = board.preset().name + " (Kopie)";
                    board.presets.add(board.current + 1, c);
                    switchPreset(board.current + 1);
                } catch (Exception ignored) {
                }
                break;
            case 5:
                if (board.presets.size() <= 1) {
                    toast("Das letzte Preset kann nicht gelöscht werden");
                    break;
                }
                Ui.dialog(new AlertDialog.Builder(this, Ui.DIALOG_THEME)
                        .setTitle("Preset löschen?")
                        .setMessage("„" + board.preset().name + "“ mit allen Kacheln löschen?")
                        .setNegativeButton("Abbrechen", null)
                        .setPositiveButton("Löschen", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                engine.stopAll();
                                board.presets.remove(board.current);
                                switchPreset(Math.min(board.current, board.presets.size() - 1));
                            }
                        })
                        .show());
                break;
            case 6:
                for (Board.Btn b : board.preset().btns) b.played = false;
                board.save();
                refreshTiles();
                toast("Markierungen in diesem Preset zurückgesetzt");
                break;
            case 7:
                rescanFolders(true);
                break;
            case 8:
                Dialogs.settings(this);
                break;
            case 9:
                Dialogs.help(this);
                break;
            case 10:
                Intent ex = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json")
                        .putExtra(Intent.EXTRA_TITLE, "soundboard-sicherung-"
                                + new SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(new Date()) + ".json");
                startActivityForResult(ex, REQ_EXPORT);
                break;
            case 11:
                Intent im = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*");
                startActivityForResult(im, REQ_IMPORT);
                break;
            case 12:
                pickImage(REQ_LOGO);
                break;
            case 13:
                board.preset().logo = "";
                board.save();
                refreshAll();
                break;
            case 99:
                quit();
                break;
        }
    }

    private void quit() {
        engine.release();
        board.save();
        finishAndRemoveTask();
    }

    // ------------------------------------------------------------------ Ordner-Playlists neu einlesen
    /** liest alle Ordner-Kacheln aller Presets im Hintergrund neu ein (neue Dateien kommen automatisch dazu) */
    private volatile boolean scanning;

    /**
     * Liest alle Ordner aller Kacheln neu ein (neue Dateien kommen automatisch dazu) und holt fehlende
     * MP3-Tags nach - auch fuer einzeln gewaehlte Titel. Laeuft im Hintergrund; schon bekannte Tags werden
     * nicht erneut gelesen.
     */
    void rescanFolders(final boolean report) {
        if (scanning) {
            if (report) toast("Wird gerade eingelesen …");
            return;
        }
        // Momentaufnahme auf dem UI-Thread
        final List<Board.Btn> btns = new ArrayList<>();
        final List<List<Board.Folder>> folders = new ArrayList<>();
        final List<List<Board.Track>> old = new ArrayList<>();
        final Map<String, Board.Track> known = new HashMap<>();
        final List<String> untagged = new ArrayList<>();
        for (Board.Preset p : board.presets) {
            for (Board.Btn b : p.btns) {
                for (Board.Track t : b.files) {
                    if (t.tagged) known.put(t.uri, copyOf(t));
                    else if (!untagged.contains(t.uri)) untagged.add(t.uri);
                }
                for (Board.Track t : b.folderTracks) if (t.tagged) known.put(t.uri, copyOf(t));
                if (b.hasFolders()) {
                    btns.add(b);
                    folders.add(new ArrayList<>(b.folders));
                    List<Board.Track> o = new ArrayList<>();
                    for (Board.Track t : b.folderTracks) o.add(copyOf(t));
                    old.add(o);
                }
            }
        }
        if (btns.isEmpty() && untagged.isEmpty()) {
            if (report) toast("Keine Ordner-Kacheln vorhanden");
            return;
        }
        scanning = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                int fail = 0, total = 0, tagsRead = 0;
                final List<List<Board.Track>> res = new ArrayList<>();
                for (int i = 0; i < btns.size(); i++) {
                    List<Board.Track> merged = new ArrayList<>();
                    for (Board.Folder f : folders.get(i)) {
                        List<Board.Track> r = Util.scanFolder(MainActivity.this, f.uri);
                        if (r == null) { // Ordner gerade nicht lesbar -> alten Stand behalten
                            fail++;
                            for (Board.Track t : old.get(i)) if (f.uri.equals(t.src)) merged.add(t);
                            continue;
                        }
                        java.util.Map<String, Board.Track> before = new java.util.HashMap<>();
                        for (Board.Track t : old.get(i)) before.put(t.uri, t);
                        for (Board.Track t : r) {
                            // NEU = Datei war bei diesem Ordner bisher nicht dabei; sonst Markierung uebernehmen
                            Board.Track was = before.get(t.uri);
                            t.isNew = was == null || was.isNew;
                            Board.Track k = known.get(t.uri);
                            if (k != null) t.copyTags(k);
                            else {
                                Util.readTags(MainActivity.this, t);
                                known.put(t.uri, copyOf(t));
                                tagsRead++;
                            }
                        }
                        merged.addAll(r);
                    }
                    total += merged.size();
                    res.add(merged);
                }
                final Map<String, Board.Track> fileTags = new HashMap<>();
                for (String u : untagged) {
                    Board.Track t = new Board.Track(u, "");
                    Util.readTags(MainActivity.this, t);
                    fileTags.put(u, t);
                    tagsRead++;
                }
                final int fFail = fail, fTotal = total, fTags = tagsRead;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        scanning = false;
                        for (int i = 0; i < btns.size(); i++) btns.get(i).folderTracks = res.get(i);
                        for (Board.Preset p : board.presets)
                            for (Board.Btn b : p.btns)
                                for (Board.Track t : b.files) {
                                    Board.Track k = fileTags.get(t.uri);
                                    if (k != null && !t.tagged) t.copyTags(k);
                                }
                        board.save();
                        refreshTiles();
                        analyzeLoudness(false); // neue Titel gleich vermessen
                        if (report) toast(btns.size() + " Ordner-Kacheln eingelesen · " + fTotal + " Titel"
                                + (fTags > 0 ? " · " + fTags + " Tags gelesen" : "")
                                + (fFail > 0 ? " · " + fFail + " Ordner nicht lesbar" : ""));
                    }
                });
            }
        }).start();
    }

    // ------------------------------------------------------------------ Lautstaerke-Angleichung
    private volatile boolean measuring, playingNow;
    private boolean measureAgain;
    String normProgress = "";

    /**
     * Misst im Hintergrund die Lautheit aller Titel, die noch keinen Messwert haben (force = alle neu).
     * Pausiert, solange etwas abgespielt wird, damit das Dekodieren den Spielbetrieb nicht stoert.
     */
    void analyzeLoudness(final boolean force) {
        if (measuring) {
            measureAgain = true;
            return;
        }
        final List<Board.Track> todo = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Board.Preset p : board.presets)
            for (Board.Btn b : p.btns)
                for (Board.Track t : b.tracks())
                    if (seen.add(t.uri) && (force || board.levelOf(t) == null)) todo.add(copyOf(t));
        if (todo.isEmpty()) {
            if (force) toast("Keine Titel zum Messen");
            return;
        }
        measuring = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                int ok = 0;
                for (int i = 0; i < todo.size(); i++) {
                    while (playingNow) { // waehrend der Wiedergabe nicht messen
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    final Board.Track t = todo.get(i);
                    final float lvl = Loudness.measure(MainActivity.this, t.uri); // NaN = nicht messbar (wird gemerkt)
                    if (!Float.isNaN(lvl)) ok++;
                    final int n = i + 1;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            board.setLevel(t, lvl);
                            engine.regain();
                            normProgress = "🔊 Lautstärke wird gemessen: " + n + " / " + todo.size();
                            if (engine.voices.isEmpty() && !editMode) tvHint.setText(normProgress);
                        }
                    });
                }
                final int fOk = ok;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        measuring = false;
                        normProgress = "";
                        board.save();
                        rebuildChips();
                        toast("Lautstärke von " + fOk + " Titeln gemessen" + (fOk < todo.size() ? " · " + (todo.size() - fOk) + " nicht lesbar" : ""));
                        if (measureAgain) {
                            measureAgain = false;
                            analyzeLoudness(false);
                        }
                    }
                });
            }
        }).start();
    }

    private static Board.Track copyOf(Board.Track t) {
        Board.Track c = new Board.Track(t.uri, t.name);
        c.copyTags(t);
        c.src = t.src;
        c.isNew = t.isNew;
        return c;
    }

    // ------------------------------------------------------------------ Dateiauswahl / Sicherung
    void pickFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("audio/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_FILES);
    }

    void pickImage(int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
        startActivityForResult(i, req);
    }

    void pickFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_FOLDER);
    }

    private void keep(Uri u) {
        try {
            getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        hideSystemUi();
        if (res != RESULT_OK || data == null) return;
        if (req == REQ_FILES && editor != null) {
            List<Board.Track> out = new ArrayList<>();
            ClipData cd = data.getClipData();
            if (cd != null) {
                for (int i = 0; i < cd.getItemCount(); i++) {
                    Uri u = cd.getItemAt(i).getUri();
                    keep(u);
                    out.add(new Board.Track(u.toString(), Util.displayName(this, u)));
                }
            } else if (data.getData() != null) {
                Uri u = data.getData();
                keep(u);
                out.add(new Board.Track(u.toString(), Util.displayName(this, u)));
            }
            final Editor edF = editor;
            final List<Board.Track> fOut = out;
            new Thread(new Runnable() { // Tags im Hintergrund lesen
                @Override
                public void run() {
                    for (Board.Track t : fOut) Util.readTags(MainActivity.this, t);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            edF.addFiles(fOut);
                        }
                    });
                }
            }).start();
        } else if (req == REQ_FOLDER && editor != null && data.getData() != null) {
            final Uri tree = data.getData();
            keep(tree);
            final Editor ed = editor;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final List<Board.Track> list = Util.scanFolder(MainActivity.this, tree.toString());
                    if (list != null) for (Board.Track t : list) Util.readTags(MainActivity.this, t);
                    final String name = Util.treeName(MainActivity.this, tree);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (list == null) toast("Ordner nicht lesbar");
                            else ed.addFolder(tree.toString(), name, list);
                        }
                    });
                }
            }).start();
        } else if ((req == REQ_IMAGE || req == REQ_LOGO) && data.getData() != null) {
            final Uri u = data.getData();
            final int fReq = req;
            final Editor ed = editor;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final String name = Images.importImage(MainActivity.this, u);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (name == null) {
                                toast("Bild konnte nicht gelesen werden (" + Images.lastError + ")");
                            } else if (fReq == REQ_LOGO) {
                                board.preset().logo = name;
                                board.save();
                                refreshAll();
                            } else if (ed != null) {
                                ed.setImage(name);
                                toast("Bild übernommen – mit „Speichern“ bestätigen");
                            } else {
                                toast("Editor war geschlossen – Kachel nochmal öffnen und Bild erneut wählen");
                            }
                        }
                    });
                }
            }).start();
        } else if (req == REQ_EXPORT && data.getData() != null) {
            try {
                java.io.OutputStream os = getContentResolver().openOutputStream(data.getData(), "wt");
                org.json.JSONObject all = board.toJson();
                org.json.JSONObject imgs = new org.json.JSONObject();
                for (String n : Images.used(board)) {
                    String b64 = Images.toBase64(this, n);
                    if (b64 != null) imgs.put(n, b64);
                }
                all.put("images", imgs); // Bilder und Wappen reisen mit
                os.write(all.toString(1).getBytes("UTF-8"));
                os.close();
                toast("Sicherung gespeichert");
            } catch (Exception e) {
                toast("Sicherung fehlgeschlagen: " + e.getMessage());
            }
        } else if (req == REQ_IMPORT && data.getData() != null) {
            importBackup(data.getData());
        }
    }

    private void importBackup(final Uri u) {
        final org.json.JSONObject o;
        try {
            java.io.InputStream in = getContentResolver().openInputStream(u);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            o = new org.json.JSONObject(bo.toString("UTF-8"));
            if (o.optJSONArray("presets") == null) throw new Exception("keine Soundboard-Sicherung");
        } catch (Exception e) {
            toast("Import fehlgeschlagen: " + e.getMessage());
            return;
        }
        Ui.dialog(new AlertDialog.Builder(this, Ui.DIALOG_THEME)
                .setTitle("Sicherung importieren?")
                .setMessage("Alle Presets, Kacheln und Einstellungen werden ersetzt.\n\n"
                        + "Hinweis: Auf einem anderen Tablet müssen Titel und Ordner danach ggf. neu gewählt werden – "
                        + "Android gibt Dateizugriffe nur pro Gerät frei.")
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Importieren", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        engine.stopAll();
                        org.json.JSONObject imgs = o.optJSONObject("images");
                        if (imgs != null) {
                            java.util.Iterator<String> it = imgs.keys();
                            while (it.hasNext()) {
                                String n = it.next();
                                Images.fromBase64(MainActivity.this, n, imgs.optString(n));
                            }
                        }
                        board.fromJson(o);
                        if (board.presets.isEmpty()) board.presets.add(new Board.Preset());
                        board.save();
                        refreshAll();
                        rescanFolders(true);
                    }
                })
                .show());
    }

    /** Text-Groesse in sp (fuer Dialoge) */
    static void sp(TextView t, float sp) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
    }
}
