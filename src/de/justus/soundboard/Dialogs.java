package de.justus.soundboard;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Locale;

/** Einstellungen, Titel-Lautstaerke, Hilfe. */
final class Dialogs {

    private Dialogs() {
    }

    // ------------------------------------------------------------------ Titel-Lautstaerke einpegeln
    /** 72500 ms -> "1:12,5" */
    static String fmtPos(float sec) {
        if (sec < 0) sec = 0;
        int tenth = Math.round(sec * 10);
        return (tenth / 600) + ":" + String.format(Locale.GERMANY, "%02d", (tenth / 10) % 60) + "," + (tenth % 10);
    }

    /**
     * Titel einstellen: Lautstaerke (gilt fuer den Titel ueberall) und Start/Ende (gilt NUR auf dieser Kachel).
     * Beim Probehoeren kann Start/Ende mit "Jetzt" an der aktuellen Stelle gesetzt werden.
     */
    /**
     * Titel einstellen fuer list[idx]. Mit ⏮ / ⏭ geht es zum vorigen/naechsten Titel der Liste, ohne das Fenster
     * zu verlassen (lief das Probehoeren, laeuft der naechste Titel gleich weiter).
     */
    static void trackGain(final MainActivity a, final java.util.List<Board.Track> list, final int idx, final Board.Btn btn,
                          final Board.Btn skip, final Runnable done, final boolean autoplay) {
        final Board.Track t = list.get(idx);
        t.isNew = false; // einmal geoeffnet = gesehen
        final float btnDb = btn.gainDb;
        final boolean[] switching = {false};
        final AlertDialog[] dlg = new AlertDialog[1];
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(a.dp(24), a.dp(20), a.dp(24), a.dp(6));
        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.text(a, "Titel einstellen", 20, Util.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1f));
        if (list.size() > 1) {
            // ⏮  Titel 5 von 12  ⏭
            TextView prev = Ui.text(a, "⏮", 18, Util.TEXT, true);
            TextView pos = Ui.text(a, "Titel " + (idx + 1) + " von " + list.size(), 14, Util.AMBER, true);
            TextView next = Ui.text(a, "⏭", 18, Util.TEXT, true);
            for (TextView b : new TextView[]{prev, next}) {
                Ui.outline(b, Util.TEXT);
                b.setPadding(a.dp(16), a.dp(4), a.dp(16), a.dp(4));
            }
            prev.setAlpha(idx > 0 ? 1f : 0.3f);
            next.setAlpha(idx < list.size() - 1 ? 1f : 0.3f);
            pos.setPadding(a.dp(12), 0, a.dp(12), 0);
            final int[] dir = {-1, 1};
            TextView[] nav = {prev, next};
            for (int k = 0; k < 2; k++) {
                final int ni = idx + dir[k];
                nav[k].setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (ni < 0 || ni >= list.size()) return;
                        boolean play = a.engine.previewing();
                        switching[0] = true;
                        dlg[0].dismiss();
                        trackGain(a, list, ni, btn, skip, done, play);
                    }
                });
            }
            head.addView(prev);
            head.addView(pos);
            head.addView(next);
        }
        box.addView(head);
        TextView name = Ui.text(a, t.display(), 15, Util.AMBER, false);
        name.setPadding(0, a.dp(4), 0, a.dp(4));
        box.addView(name);

        // ---- Lautstaerke
        box.addView(Ui.section(a, "Lautstärke – gilt für diesen Titel überall"));
        Float lvl = a.board.levelOf(t);
        String autoInfo = !a.board.normOn ? "Automatische Angleichung ist aus (⚙)."
                : lvl == null ? "Automatische Angleichung: wird noch gemessen …"
                : !Board.measurable(lvl) ? "Automatische Angleichung: Datei nicht messbar (wird nicht automatisch neu versucht – ⚙ → „Alle Titel neu messen“)."
                : "Automatisch angeglichen: " + Util.fmtDb(a.board.autoGainOf(t)) + " (gemessen " + Util.fmtDb(lvl)
                + " RMS). Der Regler ist deine Feinkorrektur obendrauf.";
        box.addView(Ui.text(a, autoInfo, 13, Util.GREEN, false));
        float g0 = a.board.gainOf(t);
        box.addView(Ui.slider(a, "Titel-Gain", -24, 12, 0.5f, g0, new Ui.Fmt() {
            @Override
            public String fmt(float v) {
                return Util.fmtDb(v);
            }
        }, new Ui.OnValue() {
            @Override
            public void value(float v) {
                a.board.setGain(t, v);
                a.engine.regain(); // laufende Sounds und Vorhoeren sofort anpassen
            }
        }));
        box.addView(Ui.text(a, "Über 0 dB wird verstärkt. Beim Probehören ist der Kachel-Gain (" + Util.fmtDb(btnDb)
                + ") schon mit drin.", 12, Util.MUTED, false));

        // ---- Start / Ende
        box.addView(Ui.section(a, "Start & Ende – gilt nur auf dieser Kachel"));
        final float[] se = {0f, btn.trimEnd(t)};
        float[] own = btn.trims.get(t.uri);
        se[0] = own != null ? own[0] : 0f;
        final TextView tvStart = Ui.text(a, "", 16, Util.AMBER, true);
        final TextView tvEnd = Ui.text(a, "", 16, Util.AMBER, true);
        final Runnable showSE = new Runnable() {
            @Override
            public void run() {
                tvStart.setText(se[0] < 0.05f ? (btn.startSec > 0.01f ? "Kachel: " + fmtPos(btn.startSec) : "Anfang") : fmtPos(se[0]));
                tvEnd.setText(se[1] < 0.05f ? "bis zum Ende" : fmtPos(se[1]));
                btn.setTrim(t, se[0], se[1]);
            }
        };
        box.addView(trimRow(a, "Start ab", tvStart, se, 0, showSE));
        box.addView(trimRow(a, "Ende bei", tvEnd, se, 1, showSE));
        showSE.run();
        android.widget.Button copy = Ui.button(a, "Auf andere Kacheln übertragen …", Util.TEXT, false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyTrims(a, t, se, skip);
            }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.topMargin = a.dp(4);
        box.addView(copy, clp);
        box.addView(Ui.text(a, "So geht's: „▶ Ab 0:00“ spielt die Datei von Anfang an – an der richtigen Stelle bei Start "
                + "bzw. Ende „Jetzt“ tippen. „▶ Ab Startpunkt“ prüft dann, ob es sitzt (so startet die Kachel später). "
                + "Feinschritte darunter (−5 s … +5 s) oder Wert antippen und eintippen. ✕ = zurück auf Anfang bzw. Ende. "
                + "Gilt nur auf dieser Kachel – „Auf andere Kacheln übertragen“ kopiert ihn gezielt woandershin.",
                12, Util.MUTED, false));

        // ---- Probehoeren
        // Positions-Slider: zeigt die Stelle beim Probehoeren, Ziehen springt dorthin
        LinearLayout posRow = new LinearLayout(a);
        posRow.setGravity(Gravity.CENTER_VERTICAL);
        posRow.setPadding(0, a.dp(12), 0, a.dp(2));
        final TextView tvPos = Ui.text(a, "0:00,0", 15, Util.TEXT, true);
        posRow.addView(tvPos, new LinearLayout.LayoutParams(a.dp(70), -2));
        final android.widget.SeekBar sbPos = new android.widget.SeekBar(a);
        sbPos.setProgressTintList(android.content.res.ColorStateList.valueOf(Util.GREEN));
        sbPos.setThumbTintList(android.content.res.ColorStateList.valueOf(Util.GREEN));
        sbPos.setMax(1000);
        sbPos.setMinimumHeight(a.dp(40));
        posRow.addView(sbPos, new LinearLayout.LayoutParams(0, a.dp(44), 1f));
        final TextView tvDur = Ui.text(a, "–:––", 15, Util.MUTED, false);
        tvDur.setGravity(Gravity.END);
        posRow.addView(tvDur, new LinearLayout.LayoutParams(a.dp(70), -2));
        box.addView(posRow, new LinearLayout.LayoutParams(-1, -2));
        final long[] dur = {0};
        // Laenge schon vor dem Probehoeren ermitteln (fuer den Slider)
        final android.os.Handler hd = new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                long ms = 0;
                android.media.MediaMetadataRetriever mr = new android.media.MediaMetadataRetriever();
                try {
                    mr.setDataSource(a, android.net.Uri.parse(t.uri));
                    String x = mr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
                    if (x != null) ms = Long.parseLong(x.trim());
                } catch (Exception ignored) {
                } finally {
                    try {
                        mr.release();
                    } catch (Exception ignored) {
                    }
                }
                final long fms = ms;
                hd.post(new Runnable() {
                    @Override
                    public void run() {
                        if (fms > 0 && dur[0] == 0) {
                            dur[0] = fms;
                            tvDur.setText(fmtPos(fms / 1000f));
                        }
                    }
                });
            }
        }).start();
        LinearLayout bar = new LinearLayout(a);
        bar.setGravity(Gravity.CENTER);
        final Button playA = Ui.button(a, "▶  Ab 0:00", Util.GREEN, true, null);
        final Button playS = Ui.button(a, "▶  Ab Startpunkt", Util.GREEN, false, null);
        final Button stop = Ui.button(a, "■  Stopp", Util.TEXT, false, null);
        bar.addView(playA);
        View g1 = new View(a);
        bar.addView(g1, new LinearLayout.LayoutParams(a.dp(10), 1));
        bar.addView(playS);
        View g2 = new View(a);
        bar.addView(g2, new LinearLayout.LayoutParams(a.dp(10), 1));
        bar.addView(stop);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = a.dp(6);
        box.addView(bar, blp);

        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        final boolean[] dragging = {false};
        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (a.engine.previewing()) {
                    long d = a.engine.previewDur();
                    if (d > 0) {
                        dur[0] = d;
                        tvDur.setText(fmtPos(d / 1000f));
                    }
                    if (!dragging[0]) {
                        long p = a.engine.previewPos();
                        tvPos.setText(fmtPos(p / 1000f));
                        if (dur[0] > 0) sbPos.setProgress((int) (p * 1000 / dur[0]));
                    }
                    h.postDelayed(this, 100);
                }
            }
        };
        sbPos.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar s, int p, boolean user) {
                if (user && dur[0] > 0) tvPos.setText(fmtPos(dur[0] * p / 1000f / 1000f));
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar s) {
                dragging[0] = true;
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar s) {
                dragging[0] = false;
                if (dur[0] <= 0) return;
                long ms = dur[0] * s.getProgress() / 1000;
                if (!a.engine.previewing()) a.engine.preview(t, btnDb); // Slider ziehen startet das Probehoeren
                a.engine.previewSeek(ms);
                h.removeCallbacks(tick);
                h.post(tick);
            }
        });
        playA.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.engine.preview(t, btnDb);
                h.removeCallbacks(tick);
                h.post(tick);
            }
        });
        playS.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.engine.preview(t, btnDb);
                float st = se[0] > 0.05f ? se[0] : btn.startSec;
                a.engine.previewSeek(Math.round(st * 1000));
                h.removeCallbacks(tick);
                h.post(tick);
            }
        });
        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.engine.stopPreview();
            }
        });
        // "Jetzt"-Knoepfe brauchen die aktuelle Position

        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        AlertDialog d = new AlertDialog.Builder(a, Ui.DIALOG_THEME)
                .setView(Ui.withScrollHint(sv))
                .setPositiveButton("Fertig", null)
                .create();
        dlg[0] = d;
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface di) {
                h.removeCallbacks(tick);
                a.engine.stopPreview();
                a.board.save();
                if (!switching[0] && done != null) done.run(); // beim Blaettern erst am Ende die Liste neu zeichnen
            }
        });
        d.show();
        Ui.dialog(d);
        Ui.width(d, 0.72f);
        if (autoplay) { // beim Blaettern weiterhoeren
            a.engine.preview(t, btnDb);
            h.post(tick);
        }
    }

    /** "1:23,4" / "1:23" / "83,4" / "83" -> Sekunden; -1 bei unlesbarer Eingabe */
    static float parsePos(String in) {
        try {
            String x = in.trim().replace(',', '.');
            if (x.isEmpty()) return 0f;
            if (x.contains(":")) {
                String[] p = x.split(":");
                return Integer.parseInt(p[0].trim()) * 60f + Float.parseFloat(p[1].trim());
            }
            return Float.parseFloat(x);
        } catch (Exception e) {
            return -1f;
        }
    }

    /**
     * Zwei Zeilen: oben Beschriftung | Wert (antippen = eintippen) | Jetzt | ✕,
     * darunter Feinschritte −5 / −1 / −0,1 / +0,1 / +1 / +5 Sekunden.
     */
    private static LinearLayout trimRow(final MainActivity a, final String label, TextView value, final float[] se, final int i,
                                        final Runnable changed) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, a.dp(4), 0, a.dp(6));
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(Ui.text(a, label, 14, Util.TEXT, false), new LinearLayout.LayoutParams(a.dp(80), -2));
        value.setBackgroundResource(android.R.drawable.list_selector_background);
        value.setPadding(a.dp(6), a.dp(6), a.dp(6), a.dp(6));
        value.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final android.widget.EditText et = new android.widget.EditText(a);
                et.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
                et.setText(se[i] > 0.05f ? fmtPos(se[i]) : "");
                et.setHint("z. B. 1:23,4 oder 83");
                et.setHintTextColor(Util.MUTED);
                et.setTextColor(Util.TEXT);
                et.setSelectAllOnFocus(true);
                LinearLayout eb = new LinearLayout(a);
                eb.setPadding(a.dp(24), a.dp(8), a.dp(24), 0);
                eb.addView(et, new LinearLayout.LayoutParams(-1, -2));
                Ui.dialog(new AlertDialog.Builder(a, Ui.DIALOG_THEME)
                        .setTitle(label + " (Minuten:Sekunden)")
                        .setView(eb)
                        .setNegativeButton("Abbrechen", null)
                        .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                float x = parsePos(et.getText().toString());
                                if (x < 0) {
                                    a.toast("Nicht lesbar – Format z. B. 1:23,4 oder 83");
                                    return;
                                }
                                apply(a, se, i, x, changed);
                            }
                        })
                        .show());
            }
        });
        row.addView(value, new LinearLayout.LayoutParams(0, -2, 1f));
        String[] lbl = {"Jetzt", "✕"};
        for (int k = 0; k < lbl.length; k++) {
            final int op = k;
            TextView b = Ui.text(a, lbl[k], 15, Util.TEXT, true);
            if (k == 0) Ui.pill(b, Util.AMBER);
            else Ui.outline(b, Util.TEXT);
            b.setPadding(a.dp(16), a.dp(6), a.dp(16), a.dp(6));
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (op == 0) {
                        if (!a.engine.previewing()) {
                            a.toast("Erst probehören, dann an der richtigen Stelle „Jetzt“ tippen");
                            return;
                        }
                        apply(a, se, i, a.engine.previewPos() / 1000f, changed);
                    } else {
                        apply(a, se, i, 0, changed);
                    }
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = a.dp(6);
            row.addView(b, lp);
        }
        box.addView(row);
        // Feinschritte
        LinearLayout steps = new LinearLayout(a);
        steps.setGravity(Gravity.CENTER_VERTICAL);
        steps.setPadding(a.dp(80), a.dp(4), 0, 0);
        final float[] dv = {-5f, -1f, -0.1f, 0.1f, 1f, 5f};
        String[] dl = {"−5 s", "−1 s", "−0,1", "+0,1", "+1 s", "+5 s"};
        for (int k = 0; k < dv.length; k++) {
            final float delta = dv[k];
            TextView b = Ui.text(a, dl[k], 14, Util.TEXT, true);
            Ui.outline(b, Util.TEXT);
            b.setPadding(0, a.dp(6), 0, a.dp(6));
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    float base = se[i];
                    if (base < 0.05f && i == 1 && delta < 0 && a.engine.previewDur() > 0) base = a.engine.previewDur() / 1000f;
                    apply(a, se, i, base + delta, changed);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.rightMargin = a.dp(6);
            steps.addView(b, lp);
        }
        box.addView(steps);
        return box;
    }

    /** neuen Start- bzw. Endwert uebernehmen (auf Zehntel gerundet, Ende hinter Start) */
    private static void apply(MainActivity a, float[] se, int i, float x, Runnable changed) {
        x = Math.max(0, x);
        if (a.engine.previewDur() > 0) x = Math.min(x, a.engine.previewDur() / 1000f);
        se[i] = Math.round(x * 10) / 10f;
        if (se[1] > 0.05f && se[1] <= se[0] + 0.5f) {
            a.toast("Ende muss hinter dem Start liegen");
            if (i == 1) se[1] = 0;
            else se[0] = Math.max(0, se[1] - 1f);
        }
        changed.run();
    }

    /** Start/Ende dieses Titels auf andere Kacheln uebertragen, die ihn ebenfalls enthalten (Auswahl per Haken) */
    private static void copyTrims(final MainActivity a, final Board.Track t, final float[] se, final Board.Btn skip) {
        final java.util.List<Board.Btn> targets = new java.util.ArrayList<>();
        final java.util.List<String> names = new java.util.ArrayList<>();
        for (Board.Preset p : a.board.presets) {
            for (int k = 0; k < Board.SLOTS; k++) {
                Board.Btn b = p.btns[k];
                if (b == skip || b.isControl()) continue;
                boolean has = false;
                for (Board.Track x : b.tracks()) if (x.uri.equals(t.uri)) has = true;
                if (!has) continue;
                targets.add(b);
                String what = b.isPlaylist() ? "Playlist" : "Einzeltitel";
                names.add(p.name + "  ·  " + (b.title().isEmpty() ? "Kachel " + (k + 1) : b.title()) + "  (" + what + ")");
            }
        }
        if (targets.isEmpty()) {
            a.toast("Dieser Titel liegt auf keiner anderen Kachel");
            return;
        }
        final boolean[] chk = new boolean[targets.size()];
        for (int k = 0; k < chk.length; k++) chk[k] = targets.get(k).isPlaylist(); // Playlists vorausgewaehlt
        Ui.dialog(new AlertDialog.Builder(a, Ui.DIALOG_THEME)
                .setTitle("Start " + (se[0] > 0.05f ? fmtPos(se[0]) : "Anfang") + " · Ende " + (se[1] > 0.05f ? fmtPos(se[1]) : "–")
                        + " übertragen auf:")
                .setMultiChoiceItems(names.toArray(new String[0]), chk, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which, boolean on) {
                        chk[which] = on;
                    }
                })
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Übertragen", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        int n = 0;
                        for (int k = 0; k < chk.length; k++) {
                            if (!chk[k]) continue;
                            targets.get(k).setTrim(t, se[0], se[1]);
                            n++;
                        }
                        a.board.save();
                        a.toast(n == 0 ? "Nichts übertragen" : "Auf " + n + (n == 1 ? " Kachel" : " Kacheln") + " übertragen");
                    }
                })
                .show());
    }

    // ------------------------------------------------------------------ Einstellungen
    static void settings(final MainActivity a) {
        final Board b = a.board;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(a.dp(24), a.dp(20), a.dp(24), a.dp(6));
        box.addView(Ui.text(a, "Einstellungen", 20, Util.TEXT, true));

        final float[] v = {b.fadeButtonSec, b.stopFadeSec, b.switchFadeSec, b.duckDb, b.guardMs};
        Ui.Fmt sec = new Ui.Fmt() {
            @Override
            public String fmt(float x) {
                return Util.fmtSec(x);
            }
        };
        box.addView(Ui.section(a, "Lautstärke-Angleichung"));
        final Switch swNorm = Ui.toggle(a, "Alle Titel automatisch auf gleiche Lautstärke bringen", b.normOn);
        box.addView(swNorm);
        final float[] nt = {b.normTarget};
        box.addView(Ui.slider(a, "Zielpegel", -24, -8, 1f, nt[0], new Ui.Fmt() {
            @Override
            public String fmt(float x) {
                return Util.fmtDb(x);
            }
        }, new Ui.OnValue() {
            @Override
            public void value(float x) {
                nt[0] = x;
            }
        }));
        int all = 0, done = 0;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Board.Preset p : b.presets)
            for (Board.Btn bt : p.btns)
                for (Board.Track t : bt.tracks())
                    if (seen.add(t.uri)) {
                        all++;
                        if (b.levelOf(t) != null) done++;
                    }
        box.addView(Ui.text(a, "Die App misst jeden Titel einmal (im Hintergrund, pausiert während etwas läuft) und regelt "
                + "laute Titel leiser und leise lauter. Zielpegel höher = insgesamt lauter, aber leise Titel werden stärker "
                + "angehoben. Deine Titel-Korrektur kommt obendrauf. Gemessen: " + done + " von " + all + " Titeln.",
                12, Util.MUTED, false));
        android.widget.Button remeasure = Ui.button(a, "Alle Titel neu messen", Util.TEXT, false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.analyzeLoudness(true);
                a.toast("Messung läuft im Hintergrund");
            }
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
        rlp.topMargin = a.dp(6);
        box.addView(remeasure, rlp);

        box.addView(Ui.section(a, "Blenden"));
        box.addView(Ui.slider(a, "Fade-Taste oben", 1, 15, 0.5f, v[0], sec, new Ui.OnValue() {
            @Override
            public void value(float x) {
                v[0] = x;
            }
        }));
        box.addView(Ui.slider(a, "Stopp-Fade (Standard)", 0.5f, 15, 0.5f, v[1], sec, new Ui.OnValue() {
            @Override
            public void value(float x) {
                v[1] = x;
            }
        }));
        box.addView(Ui.slider(a, "Andere ausblenden", 0.5f, 5, 0.5f, v[2], sec, new Ui.OnValue() {
            @Override
            public void value(float x) {
                v[2] = x;
            }
        }));
        box.addView(Ui.slider(a, "Durchsage: andere", -30, -3, 1f, v[3], new Ui.Fmt() {
            @Override
            public String fmt(float x) {
                return Util.fmtDb(x);
            }
        }, new Ui.OnValue() {
            @Override
            public void value(float x) {
                v[3] = x;
            }
        }));
        TextView h1 = Ui.text(a, "„Stopp-Fade“ gilt für alle Kacheln, die beim Stoppen auf „Standard“ stehen. "
                + "„Andere ausblenden“ und „Durchsage“ wirken nur bei Kacheln, die das eingestellt haben.", 12, Util.MUTED, false);
        box.addView(h1);

        final float[] md = {b.manualDuckDb};
        box.addView(Ui.slider(a, "LEISER-Taste", -30, -3, 1f, md[0], new Ui.Fmt() {
            @Override
            public String fmt(float x) {
                return Util.fmtDb(x);
            }
        }, new Ui.OnValue() {
            @Override
            public void value(float x) {
                md[0] = x;
            }
        }));
        box.addView(Ui.text(a, "Um so viel wird laufende Musik mit der Steuer-Kachel bzw. Taste „LEISER“ abgesenkt "
                + "(z. B. für den Hallensprecher). Nochmal tippen = wieder laut.", 12, Util.MUTED, false));

        box.addView(Ui.section(a, "Playlists"));
        final float[] skip = {b.skipFadeSec};
        box.addView(Ui.slider(a, "Weiterschalten", 0, 2, 0.1f, skip[0], new Ui.Fmt() {
            @Override
            public String fmt(float x) {
                return x <= 0.01f ? "hart" : String.format(Locale.GERMANY, "%.1f s", x);
            }
        }, new Ui.OnValue() {
            @Override
            public void value(float x) {
                skip[0] = x;
            }
        }));
        TextView hs = Ui.text(a, "Kurzes Ausblenden, wenn du in einer laufenden Playlist mit ⏭ oder ☰ den Titel wechselst.", 12, Util.MUTED, false);
        box.addView(hs);
        final Switch swTags = Ui.toggle(a, "Titel aus MP3-Tags anzeigen („Interpret – Titel“), sonst Dateiname", b.useTags);
        box.addView(swTags);

        box.addView(Ui.section(a, "Vormerken (Warteschlange)"));
        final Switch swQueue = Ui.toggle(a, "Antippen, während Musik läuft = Kachel vormerken statt sofort starten", b.queueOn);
        box.addView(swQueue);
        final Switch swCross = Ui.toggle(a, "Vorgemerktes startet schon beim Fade-Tippen (überblenden) statt nach dem Ausblenden", b.queueCrossfade);
        box.addView(swCross);
        TextView hq = Ui.text(a, "Läuft irgendein Sound, wird eine angetippte Kachel vorgemerkt. Sie startet, wenn du „Fade“ "
                + "tippst oder alles von selbst zu Ende ist. Vorgemerkte Kachel nochmal antippen = Vormerkung weg. Kacheln mit ⚡ "
                + "sowie Durchsagen und „andere ausblenden“ starten selbst immer sofort.", 12, Util.MUTED, false);
        box.addView(hq);

        box.addView(Ui.section(a, "Bedienung"));
        box.addView(Ui.slider(a, "Doppeltipp-Schutz", 0, 1500, 100, v[4], new Ui.Fmt() {
            @Override
            public String fmt(float x) {
                return x <= 0 ? "aus" : String.format(Locale.GERMANY, "%.1f s", x / 1000f);
            }
        }, new Ui.OnValue() {
            @Override
            public void value(float x) {
                v[4] = x;
            }
        }));
        TextView h2 = Ui.text(a, "Ein zweiter Tipp auf dieselbe Kachel innerhalb dieser Zeit wird ignoriert – "
                + "damit ein hektischer Doppeltipp die Torhymne nicht gleich wieder stoppt.", 12, Util.MUTED, false);
        box.addView(h2);
        final Switch swMark = Ui.toggle(a, "Gespielte Kacheln markieren (Punkt unten rechts)", b.markPlayed);
        box.addView(swMark);

        box.addView(Ui.section(a, "Neuer Spieltag"));
        final Switch swDayPlayed = Ui.toggle(a, "Gespielt-Markierungen beim ersten Start des Tages zurücksetzen", b.dayResetPlayed);
        final Switch swDayLists = Ui.toggle(a, "Playlists beim ersten Start des Tages wieder von vorn beginnen", b.dayResetLists);
        box.addView(swDayPlayed);
        box.addView(swDayLists);
        box.addView(Ui.text(a, "„Von vorn“ gilt für Playlists ohne Zufall, die beim nächsten Titel weitermachen. "
                + "Bei Zufall wird der erste Titel neu ausgelost.", 12, Util.MUTED, false));

        box.addView(Ui.section(a, "Fernbedienung / Tasten"));
        box.addView(Ui.text(a, "Bluetooth-Klicker oder Fußschalter mit dem Tablet koppeln, dann hier je Funktion „Anlernen“ "
                + "tippen und die Taste drücken. Wird sofort gespeichert und funktioniert auch bei gesperrter Bedienung.", 12, Util.MUTED, false));
        final LinearLayout keyBox = new LinearLayout(a);
        keyBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(keyBox);
        buildKeys(a, keyBox);

        box.addView(Ui.section(a, "Sperre 🔒"));
        LinearLayout pinRow = new LinearLayout(a);
        pinRow.setGravity(Gravity.CENTER_VERTICAL);
        pinRow.addView(Ui.text(a, "PIN zum Entsperren (optional)", 14, Util.TEXT, false), new LinearLayout.LayoutParams(0, -2, 1f));
        final android.widget.EditText etPin = new android.widget.EditText(a);
        etPin.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        etPin.setText(b.lockPin);
        etPin.setHint("ohne");
        etPin.setHintTextColor(Util.MUTED);
        etPin.setTextColor(Util.TEXT);
        etPin.setGravity(Gravity.CENTER);
        pinRow.addView(etPin, new LinearLayout.LayoutParams(a.dp(120), -2));
        box.addView(pinRow);
        box.addView(Ui.text(a, "Das Schloss oben rechts sperrt die ganze Bedienung, laufende Sounds spielen weiter. "
                + "Entsperren: Schloss in der Mitte gedrückt halten – mit PIN zusätzlich PIN eingeben. Leer = ohne PIN.", 12, Util.MUTED, false));

        box.addView(Ui.section(a, "Display"));
        final Switch swCharge = Ui.toggle(a, "Display bleibt an, solange das Ladekabel steckt", b.keepOnCharging);
        final Switch swAlways = Ui.toggle(a, "Display immer an (auch ohne Ladekabel)", b.keepOnAlways);
        box.addView(swCharge);
        box.addView(swAlways);
        TextView h3 = Ui.text(a, "Gilt, solange die App im Vordergrund ist. Oben links zeigt ☀, dass das Display "
                + "gerade wach gehalten wird, ⚡ = Ladekabel steckt.", 12, Util.MUTED, false);
        box.addView(h3);

        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        AlertDialog d = new AlertDialog.Builder(a, Ui.DIALOG_THEME)
                .setView(Ui.withScrollHint(sv))
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Speichern", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        b.fadeButtonSec = v[0];
                        b.stopFadeSec = v[1];
                        b.switchFadeSec = v[2];
                        b.duckDb = v[3];
                        b.guardMs = Math.round(v[4]);
                        b.markPlayed = swMark.isChecked();
                        b.queueOn = swQueue.isChecked();
                        b.skipFadeSec = Math.round(skip[0] * 10f) / 10f;
                        b.manualDuckDb = md[0];
                        b.normOn = swNorm.isChecked();
                        b.normTarget = nt[0];
                        a.engine.regain(); // laufende Titel sofort anpassen
                        b.useTags = swTags.isChecked();
                        Board.Track.useTags = b.useTags;
                        b.queueCrossfade = swCross.isChecked();
                        b.keepOnCharging = swCharge.isChecked();
                        b.keepOnAlways = swAlways.isChecked();
                        b.dayResetPlayed = swDayPlayed.isChecked();
                        b.dayResetLists = swDayLists.isChecked();
                        b.lockPin = etPin.getText().toString().trim();
                        b.save();
                        a.applyKeepOn();
                        a.refreshTiles();
                        a.toast("Einstellungen gespeichert");
                    }
                })
                .create();
        d.show();
        Ui.dialog(d);
        Ui.width(d, 0.6f);
    }

    // ------------------------------------------------------------------ Fernbedienung
    static String keyName(int code) {
        String s = android.view.KeyEvent.keyCodeToString(code);
        return s.startsWith("KEYCODE_") ? s.substring(8) : s;
    }

    private static void buildKeys(final MainActivity a, final LinearLayout box) {
        box.removeAllViews();
        for (int act = 1; act < Board.KEY_NAMES.length; act++) {
            final int fAct = act;
            StringBuilder keys = new StringBuilder();
            for (java.util.Map.Entry<Integer, Integer> e : a.board.keyMap.entrySet()) {
                if (e.getValue() == act) {
                    if (keys.length() > 0) keys.append(", ");
                    keys.append(keyName(e.getKey()));
                }
            }
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, a.dp(3), 0, a.dp(3));
            row.addView(Ui.text(a, Board.KEY_NAMES[act], 14, Util.TEXT, false), new LinearLayout.LayoutParams(a.dp(190), -2));
            TextView k = Ui.text(a, keys.length() == 0 ? "–" : keys.toString(), 14, keys.length() == 0 ? Util.MUTED : Util.AMBER, true);
            row.addView(k, new LinearLayout.LayoutParams(0, -2, 1f));
            android.widget.Button learn = Ui.button(a, "Anlernen", Util.TEXT, false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    learnKey(a, fAct, box);
                }
            });
            row.addView(learn);
            if (keys.length() > 0) {
                TextView del = Ui.text(a, "✕", 16, Util.MUTED, false);
                del.setGravity(Gravity.CENTER);
                del.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        java.util.Iterator<java.util.Map.Entry<Integer, Integer>> it = a.board.keyMap.entrySet().iterator();
                        while (it.hasNext()) if (it.next().getValue() == fAct) it.remove();
                        a.board.save();
                        buildKeys(a, box);
                    }
                });
                row.addView(del, new LinearLayout.LayoutParams(a.dp(40), a.dp(36)));
            }
            box.addView(row);
        }
    }

    /** wartet auf einen Tastendruck und legt ihn auf die Funktion */
    private static void learnKey(final MainActivity a, final int act, final LinearLayout box) {
        final AlertDialog d = new AlertDialog.Builder(a, Ui.DIALOG_THEME)
                .setTitle("Taste anlernen")
                .setMessage("Jetzt die Taste auf der Fernbedienung drücken für:\n\n" + Board.KEY_NAMES[act])
                .setNegativeButton("Abbrechen", null)
                .create();
        d.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey(DialogInterface di, int code, android.view.KeyEvent e) {
                if (code == android.view.KeyEvent.KEYCODE_BACK) return false;
                if (e.getAction() != android.view.KeyEvent.ACTION_UP) return true;
                Integer old = a.board.keyMap.get(code);
                a.board.keyMap.put(code, act);
                a.board.save();
                String msg = "Taste " + keyName(code) + " = " + Board.KEY_NAMES[act];
                if (old != null && old != act) msg += " (vorher " + Board.KEY_NAMES[old] + ")";
                if (code == android.view.KeyEvent.KEYCODE_VOLUME_UP || code == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)
                    msg += "\nAchtung: Diese Taste regelt jetzt nicht mehr die Lautstärke des Tablets.";
                a.toast(msg);
                di.dismiss();
                buildKeys(a, box);
                return true;
            }
        });
        d.show();
        Ui.dialog(d);
    }

    // ------------------------------------------------------------------ Hilfe
    private static final String[][] CHANGELOG = {
            {"0.13.1", "Vormerken geht jetzt während JEDES laufenden Sounds – auch während Zamboni, Hymnen oder Hupen (⚡). "
                    + "⚡ bestimmt nur noch, ob eine Kachel selbst sofort startet. Fade blendet aus und startet das Vorgemerkte"},
            {"0.13", "TRACK-Fader zeigt die automatische Angleichung des laufenden Titels mit an (TRACK · auto −3,5)\n"
                    + "Knöpfe unten im laufenden Sound (☰ ⏭ ■) größer und mit mehr Abstand – Stopp deutlich abgesetzt\n"
                    + "Hilfe: neuer Reiter „Über“ mit Beschreibung, Hinweisen und Credits"},
            {"0.12.1", "Lautstärke-Messung: nicht messbare Dateien werden gemerkt und nicht mehr bei jedem App-Start erneut probiert"},
            {"0.12", "Editor: Suchfeld über der Titelliste – findet nach Interpret, Titel oder Dateiname\n"
                    + "NEU-Markierung: Titel, die beim Einlesen neu im Ordner aufgetaucht sind, bis du sie einmal öffnest\n"
                    + "„NEU (3)“ zeigt nur die neuen, „alle gesehen“ entfernt die Markierung; ⏮ / ⏭ blättert nur durch die angezeigten Titel"},
            {"0.11", "Automatische Lautstärke-Angleichung: jeder Titel wird einmal gemessen und auf einen gemeinsamen "
                    + "Zielpegel gebracht (⚙ → Lautstärke-Angleichung) – Titel-Korrektur bleibt als Feinregler obendrauf\n"
                    + "Messung läuft im Hintergrund und pausiert, solange etwas abgespielt wird\n"
                    + "Editor-Liste zeigt die automatische Korrektur je Titel (auto −3,5 dB)"},
            {"0.10", "Playlist-Zähler zählt den Durchgang: läuft = das wievielte Lied, ruhend = das nächste; zählt über "
                    + "Unterbrechungen weiter. Bei Zufall kommt jedes Lied genau einmal pro Durchgang, dann neu gemischt\n"
                    + "Gespielte Kacheln werden nicht mehr blasser – nur der Punkt zeigt „schon gespielt“\n"
                    + "„Titel einstellen“: Positions-Slider (zeigt und springt) und ⏮ / ⏭ zum vorigen/nächsten Titel der Playlist"},
            {"0.9.5", "Playlist-Kacheln zeigen oben rechts, welcher Titel läuft (z. B. 3/12) – ruhend den nächsten, bei Zufall die Anzahl\n"
                    + "Auch in der Fußleiste steht die Position (3/12)\n"
                    + "„Bearbeiten“ ist gesperrt, solange etwas läuft – kein versehentlicher Wechsel neben FADE"},
            {"0.9.4", "Start/Ende feiner: Schritte −5 / −1 / −0,1 / +0,1 / +1 / +5 Sekunden, Wert antippen = direkt eintippen (z. B. 1:23,4)\n"
                    + "„Auf andere Kacheln übertragen …“: Start/Ende eines Titels gezielt auf andere Kacheln/Playlists mit diesem Titel kopieren"},
            {"0.9.3", "LEISER-Kachel blinkt mit 1 Hz, solange die Absenkung aktiv ist"},
            {"0.9.2", "Neue Steuer-Funktion LEISER 🔉: laufende Musik für Ansagen weich absenken, nochmal tippen = wieder laut\n"
                    + "Absenkung einstellbar (⚙ → „LEISER-Taste“), auch auf die Fernbedienung legbar\n"
                    + "Sounds, die während der Absenkung neu starten, bleiben normal laut"},
            {"0.9.1", "Transparente Kacheln optional mit Rahmen in der Kachelfarbe\n"
                    + "„Titel einstellen“: Knöpfe heißen jetzt „▶ Ab 0:00“ und „▶ Ab Startpunkt“, Erklärung ergänzt"},
            {"0.9", "Start- und Endpunkt je Titel: im Editor Titel antippen → „Start & Ende“, beim Probehören mit „Jetzt“ setzen\n"
                    + "Gilt nur auf dieser Kachel – derselbe Titel auf einer anderen Kachel startet normal\n"
                    + "Am Endpunkt wird kurz ausgeblendet, dann kommt der nächste Titel\n"
                    + "Editor-Liste zeigt Start/Ende je Titel (▶ 0:12 · ■ 3:05)"},
            {"0.8", "Fernbedienung: Bluetooth-Klicker oder Fußschalter anlernen (⚙ → Fernbedienung / Tasten) – "
                    + "Stopp, Fade, Alles aus, Weiter, Mute, Preset wechseln; funktioniert auch bei gesperrter Bedienung"},
            {"0.7", "Steuer-Kacheln: eine Kachel kann STOPP, FADE, ALLES AUS oder WEITER sein – groß und gut zu treffen\n"
                    + "Sperre 🔒 oben rechts: Bedienung gesperrt, Sounds laufen weiter; Entsperren durch Gedrückthalten, PIN optional\n"
                    + "Neuer Spieltag: Gespielt-Markierungen (und optional Playlists) beim ersten Start des Tages zurücksetzen\n"
                    + "Einstellungen ⚙ und Hilfe ? haben eigene Knöpfe oben links\n"
                    + "Hilfe neu: Kurzanleitung und Reiter für Spiel, Einrichten, Einstellungen und Changelog"},
            {"0.6", "Mehrere Ordner pro Kachel: „Ordner hinzufügen“ beliebig oft, Titel laufen hintereinander, ✕ entfernt einen Ordner\n"
                    + "Weiterschalten in der laufenden Playlist (⏭ / ☰) mit kurzem Ausblenden – Dauer in den Einstellungen\n"
                    + "Ruhende Playlist-Kachel zeigt den nächsten Titel (⏭ …) – gedrückt halten = weiterschalten, bevor du startest\n"
                    + "Auch bei Zufall wird der nächste Titel vorab ausgelost, damit die Anzeige stimmt\n"
                    + "MP3-Tags: Anzeige „Interpret – Titel“, sonst Dateiname; der Editor zeigt, wie viele Titel Tags haben"},
            {"0.5", "Neue Fader für TRACK und MASTER: groß, ganze Fläche greifbar, ohne Titelanzeige\n"
                    + "Gezogen wird relativ – versehentliches Antippen verstellt nichts; TRACK rastet bei 0 dB ein\n"
                    + "TRACK lange halten = 0 dB · „TRACK ⇄“ antippen = anderen laufenden Titel regeln\n"
                    + "Titel-Lautstärke wird zusätzlich unter dem Dateinamen gespeichert – bleibt auch erhalten, "
                    + "wenn dieselbe Datei später über einen anderen Weg gewählt wird"},
            {"0.4", "Track-Gain-Regler in der Fußleiste: Lautstärke des laufenden Titels live nachregeln, "
                    + "wird dauerhaft für diesen Titel gespeichert\n"
                    + "Laufen mehrere Titel: Beschriftung „TRACK-GAIN“ antippen = nächsten wählen · Wert lange drücken = 0 dB"},
            {"0.3.1", "App-Icon überarbeitet: dickerer Puck, heller Hintergrund mit Eis-Optik"},
            {"0.3", "Neues App-Icon: Puck mit Equalizer auf dem Eis\n"
                    + "Preset-Name passt wieder mit „Preset x von y“ in die Kopfleiste\n"
                    + "„weiter scrollen“-Knopf in Hilfe, Einstellungen und Kachel-Editor\n"
                    + "Kachelgröße in zwei Reihen: Breite 1–4 und Höhe 1–3\n"
                    + "Bilder: liest jetzt auch HEIC-Fotos, Meldung bei Erfolg bzw. mit Fehlergrund\n"
                    + "Kacheln nur mit Bild/Name (ohne Titel) werden angezeigt – z. B. als Überschrift\n"
                    + "Transparenter Kachel-Hintergrund wählbar"},
            {"0.2", "Große Kacheln: 2×1, 1×2, 2×2 bis 4×3 (im Editor unter „Größe“)\n"
                    + "Bild auf Kacheln – füllend oder als Symbol über dem Namen, Name optional\n"
                    + "Wappen/Logo je Preset in der Kopfleiste, Preset-Name groß und links\n"
                    + "Leere Kacheln werden im Spielbetrieb ausgeblendet\n"
                    + "Vormerken: Antippen während Musik läuft setzt die Kachel auf die Warteschlange, Fade startet sie\n"
                    + "⚡ Sofort-Kacheln (Torhupe & Co.) werden nie vorgemerkt\n"
                    + "Playlist: Titel direkt wählen über ☰ in der Fußleiste (harter Wechsel)\n"
                    + "Sicherung enthält jetzt auch Bilder und Wappen"},
            {"0.1", "Erste Version: Kachelraster 8 × 5, Presets, Titel oder ganzer Ordner als Playlist\n"
                    + "Lautstärke je Titel (mit Probehören) und je Kachel, Verstärkung über 0 dB\n"
                    + "Je Kachel: hart stoppen oder ausblenden, Einblenden, Startsekunde\n"
                    + "Beim Start: andere Sounds weiterlaufen lassen, leiser machen (Durchsage) oder ausblenden\n"
                    + "Fade-, Stopp- und Mute-Taste, laufende Sounds unten mit Restzeit, Überspringen, Stopp\n"
                    + "Display bleibt beim Laden an, Doppeltipp-Schutz, Sicherung exportieren/importieren"},
    };

    private static final String[] HELP_TABS = {"Kurzanleitung", "Im Spiel", "Einrichten", "Einstellungen", "Changelog", "Über"};
    private static int helpTab = 0;

    /** Hilfe mit Reitern; Einstellungen haben einen eigenen Knopf (⚙) */
    static void help(final MainActivity a) {
        LinearLayout outer = new LinearLayout(a);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(a.dp(24), a.dp(20), a.dp(24), 0);
        String ver = "?";
        try {
            ver = a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.text(a, "Hilfe", 22, Util.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView chip = Ui.text(a, "Soundboard v" + ver, 12, Util.BG, true);
        Ui.pill(chip, Util.AMBER);
        chip.setPadding(a.dp(10), a.dp(3), a.dp(10), a.dp(3));
        head.addView(chip);
        outer.addView(head);

        final LinearLayout tabs = new LinearLayout(a);
        tabs.setPadding(0, a.dp(12), 0, a.dp(8));
        outer.addView(tabs);
        final LinearLayout content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        final ScrollView sv = new ScrollView(a);
        sv.addView(content);
        outer.addView(Ui.withScrollHint(sv), new LinearLayout.LayoutParams(-1, Math.round(a.getResources().getDisplayMetrics().heightPixels * 0.58f)));

        final Runnable[] show = new Runnable[1];
        show[0] = new Runnable() {
            @Override
            public void run() {
                tabs.removeAllViews();
                for (int i = 0; i < HELP_TABS.length; i++) {
                    final int t = i;
                    TextView c = Ui.text(a, HELP_TABS[i], 14, Util.TEXT, true);
                    if (i == helpTab) Ui.pill(c, Util.AMBER);
                    else Ui.outline(c, Util.TEXT);
                    c.setPadding(a.dp(14), a.dp(7), a.dp(14), a.dp(7));
                    c.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            helpTab = t;
                            show[0].run();
                        }
                    });
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                    lp.rightMargin = a.dp(6);
                    tabs.addView(c, lp);
                }
                content.removeAllViews();
                helpContent(a, content, helpTab);
                sv.scrollTo(0, 0);
            }
        };
        show[0].run();

        AlertDialog d = new AlertDialog.Builder(a, Ui.DIALOG_THEME)
                .setView(outer)
                .setPositiveButton("Alles klar", null)
                .create();
        d.show();
        Ui.dialog(d);
        Ui.width(d, 0.8f);
    }

    private static void helpContent(MainActivity a, LinearLayout c, int tab) {
        if (tab == 0) {
            c.addView(Ui.section(a, "Die wichtigsten Handgriffe"));
            item(a, c, "1", "„Bearbeiten“ (oben) einschalten, eine Kachel antippen, Titel oder Ordner wählen, „Speichern“");
            item(a, c, "2", "„Bearbeiten“ wieder aus – jetzt gilt: Kachel antippen = Start, nochmal = Stopp");
            item(a, c, "3", "Gedrückt halten = Pause · bei einer ruhenden Playlist = nächsten Titel weiterschalten");
            item(a, c, "4", "Läuft irgendein Sound, wird eine weitere Kachel vorgemerkt – „Fade“ blendet aus und startet sie");
            item(a, c, "5", "Unten: TRACK = Lautstärke des laufenden Titels (bleibt gespeichert), MASTER = Tablet-Lautstärke");
            item(a, c, "6", "🔒 oben rechts sperrt die Bedienung – Schloss in der Mitte gedrückt halten zum Entsperren");
            c.addView(Ui.section(a, "Oben links"));
            item(a, c, "☰", "Presets (neu, umbenennen, duplizieren, löschen), Wappen, Ordner einlesen, Sicherung, Beenden");
            item(a, c, "⚙", "Einstellungen");
            item(a, c, "?", "Diese Hilfe");
        } else if (tab == 1) {
            c.addView(Ui.section(a, "Kacheln"));
            item(a, c, "👆", "Antippen = Start, nochmal antippen = Stopp (hart oder ausblenden, je nach Kachel)");
            item(a, c, "↘", "Während eine Kachel ausblendet: nochmal antippen = sofort aus");
            item(a, c, "✋", "Gedrückt halten = Pause / weiter · ruhende Playlist: gedrückt halten = nächsten Titel weiterschalten");
            item(a, c, "⏭", "Ruhende Playlist zeigt unten den Titel, mit dem sie startet");
            item(a, c, "3/12", "Playlist-Kachel oben rechts: das wievielte Lied im aktuellen Durchgang (läuft = aktuelles, ruhend = nächstes). "
                    + "Zählt über Unterbrechungen weiter; nach dem letzten Lied beginnt ein neuer Durchgang");
            item(a, c, "⏭", "Läuft irgendein Sound (auch Zamboni, Hymne, Hupe), wird eine weitere Kachel vorgemerkt (gelber Rahmen). "
                    + "„Fade“ blendet aus und startet sie, ebenso wenn alles von selbst endet. Nochmal antippen = Vormerkung weg");
            item(a, c, "⚡", "Sofort-Kacheln (Torhupe, Effekte, Durchsagen) starten immer direkt, auch über laufende Musik – "
                    + "andere Kacheln lassen sich trotzdem dahinter vormerken");
            item(a, c, "■", "Steuer-Kacheln STOPP / FADE / ALLES AUS / WEITER leuchten, sobald etwas läuft");
            item(a, c, "🔉", "LEISER: laufende Musik für eine Ansage absenken, nochmal tippen = wieder laut (blinkt, solange abgesenkt)");
            c.addView(Ui.section(a, "Kopf- und Fußleiste"));
            item(a, c, "🎚", "Fade blendet alles aus · Stopp gedrückt halten = alles sofort aus · Mute = stumm");
            item(a, c, "✎", "„Bearbeiten“ ist ausgegraut, solange etwas läuft – erst stoppen, dann bearbeiten");
            item(a, c, "▭", "Laufende Sounds unten mit Restzeit · antippen = ausblenden · ☰ = Titel wählen · ⏭ = nächster Titel "
                    + "(kurz ausgeblendet) · ■ = sofort aus");
            item(a, c, "🎚", "TRACK: Lautstärke des laufenden Titels – bleibt für diesen Titel gespeichert. Finger irgendwo "
                    + "auflegen und schieben · lange halten = 0 dB · „TRACK ⇄“ antippen = anderen Titel wählen");
            item(a, c, "🔊", "MASTER: Medienlautstärke des Tablets (auch über die Lautstärketasten)");
            item(a, c, "‹ ›", "Preset wechseln · Preset-Name antippen = Liste · laufende Sounds spielen beim Wechsel weiter");
            item(a, c, "🔒", "Sperre: alles gesperrt, Sounds laufen weiter · Entsperren = Schloss in der Mitte gedrückt halten");
            item(a, c, "🎮", "Fernbedienung: angelernte Tasten (Bluetooth-Klicker, Fußschalter) wirken immer – auch bei Sperre");
            c.addView(Ui.section(a, "Symbole auf den Kacheln"));
            item(a, c, "🔀 🔁", "Zufall · Wiederholen");
            item(a, c, "🔉 ⤵", "Durchsage (andere leiser) · blendet andere aus");
            item(a, c, "↘ ■", "Ausblenden beim Stoppen (Zeit) · harter Stopp");
            item(a, c, "●", "Kachel wurde schon gespielt");
        } else if (tab == 2) {
            c.addView(Ui.section(a, "Kacheln einrichten"));
            item(a, c, "✎", "„Bearbeiten“: Kachel antippen = einrichten · gedrückt halten, dann Ziel antippen = verschieben/tauschen");
            item(a, c, "♫", "Funktion „Sound“: Titel wählen (mehrere = Playlist, Reihenfolge mit ↑) oder Ordner hinzufügen");
            item(a, c, "📁", "Ordner: auch mehrere, laufen hintereinander. Neue Dateien kommen beim App-Start oder über "
                    + "☰ → „Ordner neu einlesen“ automatisch dazu (ohne Unterordner)");
            item(a, c, "■", "Funktion STOPP / FADE / ALLES AUS / WEITER: Steuer-Kachel – am besten groß und rot");
            item(a, c, "🎛", "Titel in der Liste antippen = Lautstärke einpegeln (gilt überall) und Start/Ende setzen "
                    + "(gilt nur auf dieser Kachel) – beim Probehören mit „Jetzt“. Slider = Stelle im Lied, ⏮ / ⏭ = vorheriger/nächster Titel");
            item(a, c, "🏷", "MP3-Tags: „Interpret – Titel“, wenn vorhanden, sonst Dateiname. Der Editor zeigt, wie viele Titel Tags haben");
            item(a, c, "🔍", "Suchfeld über der Titelliste · „NEU (3)“ = nur neu eingelesene Titel zeigen · „alle gesehen“ = Markierung weg. "
                    + "Ein Titel ist NEU, bis du ihn einmal in „Titel einstellen“ öffnest");
            item(a, c, "⬛", "Größe: Kachel über mehrere Felder (z. B. 2×2). Felder rechts/unten müssen frei sein");
            item(a, c, "🖼", "Bild auf der Kachel: füllend oder als Symbol, Name ein-/ausblendbar");
            item(a, c, "◻", "Transparenter Hintergrund: nur Bild und Name, kein Kachelfeld – optional mit Rahmen in der Kachelfarbe");
            item(a, c, "👁", "Leere Kacheln sind im Spielbetrieb unsichtbar, unter „Bearbeiten“ wieder da");
            c.addView(Ui.section(a, "Presets & Daten (☰)"));
            item(a, c, "🛡", "Wappen/Logo je Preset in der Kopfleiste");
            item(a, c, "💾", "Sicherung exportieren/importieren: alle Presets, Bilder und Einstellungen als eine Datei");
            c.addView(Ui.section(a, "Formate"));
            item(a, c, "🎵", "MP3, M4A/AAC, WAV, OGG, FLAC u. a. – alles, was Android selbst abspielen kann. Keine DRM-Dateien.");
        } else if (tab == 3) {
            c.addView(Ui.section(a, "Was die Einstellungen (⚙) bewirken"));
            item(a, c, "↘", "Fade-Taste oben: Ausblendzeit für „Fade“ und die FADE-Kachel");
            item(a, c, "↘", "Stopp-Fade: Standard-Ausblendzeit für Kacheln, die beim Stoppen auf „Standard“ stehen");
            item(a, c, "⤵", "Andere ausblenden / Durchsage: wie schnell bzw. wie viel leiser andere Sounds werden");
            item(a, c, "⏭", "Weiterschalten: kurzes Ausblenden beim Titelwechsel in einer laufenden Playlist");
            item(a, c, "🔊", "Lautstärke-Angleichung: alle Titel auf gleiche Lautstärke, Zielpegel, alle neu messen");
            item(a, c, "🏷", "Titel aus MP3-Tags anzeigen oder Dateinamen");
            item(a, c, "⏭", "Vormerken an/aus und ob das Vorgemerkte schon beim Fade-Tippen startet (überblenden)");
            item(a, c, "✋", "Doppeltipp-Schutz und Gespielt-Markierung");
            item(a, c, "📅", "Neuer Spieltag: Markierungen und optional Playlists beim ersten Start des Tages zurücksetzen");
            item(a, c, "🎮", "Fernbedienung / Tasten: Funktion wählen, „Anlernen“, Taste am Klicker drücken");
            item(a, c, "🔒", "Sperre: optionaler PIN zum Entsperren");
            item(a, c, "☀", "Display bleibt an, solange das Ladekabel steckt (oder immer)");
        } else if (tab == 5) {
            String ver = "?";
            try {
                ver = a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName;
            } catch (Exception ignored) {
            }
            c.addView(Ui.section(a, "Soundboard " + ver));
            c.addView(para(a, "Ein Soundboard für den Spielbetrieb in der Eishalle: Musik, Hymnen, Jingles und Effekte "
                    + "liegen auf frei belegbaren Kacheln und lassen sich mit einem Fingertipp starten, ausblenden oder "
                    + "stoppen. Dazu kommen Playlists aus Ordnern, Lautstärke-Angleichung, Start- und Endpunkte je Titel, "
                    + "Vormerken, Ansagen-Absenkung, Steuer-Kacheln und Fernbedienung – gebaut für schnelle, sichere "
                    + "Bedienung am Tablet im Querformat."));
            c.addView(Ui.section(a, "Hinweise"));
            item(a, c, "🎁", "Diese App ist ein privates Hobbyprojekt. Sie ist kostenlos und nicht zum Verkauf bestimmt.");
            item(a, c, "⚠", "Die Nutzung erfolgt auf eigene Verantwortung. Die App wird ohne Gewähr bereitgestellt; "
                    + "eine Haftung für Schäden, die aus ihrer Nutzung entstehen, ist – soweit gesetzlich zulässig – ausgeschlossen.");
            item(a, c, "🎵", "Die App enthält keine Musik. Verwendet werden ausschließlich Audiodateien, die du selbst "
                    + "bereitstellst. Du bist dafür verantwortlich, die nötigen Rechte an diesen Dateien zu besitzen.");
            item(a, c, "📢", "Für das öffentliche Abspielen von Musik, z. B. bei Spielen mit Zuschauern, ist in der Regel eine "
                    + "Lizenz erforderlich (in Deutschland z. B. über die GEMA). Darum kümmert sich der Veranstalter – nicht die App.");
            item(a, c, "🔒", "Die App braucht keine Internetverbindung und sendet keine Daten. Alles bleibt auf dem Gerät.");
            item(a, c, "ℹ", "Unabhängiges Projekt ohne Verbindung zu anderen Soundboard-Apps oder deren Herstellern.");
            c.addView(Ui.section(a, "Gebaut von"));
            c.addView(para(a, "Programmiert von Justus Jonas – also Claude, der KI von Anthropic – nach Idee, Wünschen und "
                    + "unzähligen Tests von Michael Nowak. Gebaut in ein paar Tagen im Oktober 2026, für die Heimspiele des Neusser EV."));
            TextView credits = Ui.text(a, "Gebaut von Justus Jonas & Michael Nowak\n– mit Claude am Mischpult 🏒", 13, Util.MUTED, false);
            credits.setGravity(Gravity.CENTER);
            credits.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.ITALIC));
            credits.setPadding(0, a.dp(18), 0, a.dp(10));
            c.addView(credits, new LinearLayout.LayoutParams(-1, -2));
        } else {
            for (String[] e : CHANGELOG) {
                TextView v = Ui.text(a, "Version " + e[0], 14, Util.AMBER, true);
                v.setPadding(0, a.dp(10), 0, a.dp(2));
                c.addView(v);
                for (String line : e[1].split("\n")) item(a, c, "•", line);
            }
            TextView credits = Ui.text(a, "Gebaut von Justus Jonas & Michael Nowak\n– mit Claude am Mischpult 🏒", 12, Util.MUTED, false);
            credits.setGravity(Gravity.CENTER);
            credits.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.ITALIC));
            credits.setPadding(0, a.dp(18), 0, a.dp(10));
            c.addView(credits, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private static TextView para(MainActivity a, String s) {
        TextView t = Ui.text(a, s, 14, Util.TEXT, false);
        t.setLineSpacing(a.dp(2), 1f);
        t.setPadding(0, a.dp(2), 0, a.dp(4));
        return t;
    }

    private static void item(MainActivity a, LinearLayout box, String sym, String body) {
        LinearLayout row = new LinearLayout(a);
        row.setPadding(0, a.dp(3), 0, a.dp(3));
        TextView s = Ui.text(a, sym, 13, Util.AMBER, true);
        s.setGravity(Gravity.CENTER_HORIZONTAL);
        row.addView(s, new LinearLayout.LayoutParams(a.dp(44), -2));
        row.addView(Ui.text(a, body, 14, Util.TEXT, false), new LinearLayout.LayoutParams(0, -2, 1f));
        box.addView(row);
    }
}
