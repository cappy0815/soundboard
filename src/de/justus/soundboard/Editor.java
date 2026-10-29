package de.justus.soundboard;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/**
 * Kachel einrichten: Name, Farbe, Titel oder Ordner, Lautstaerke je Titel, Playlist-Verhalten,
 * Ein-/Ausblenden, harter Stopp und was mit anderen Sounds passiert.
 * Gearbeitet wird auf einer Kopie - erst "Speichern" uebernimmt.
 */
final class Editor {

    private final MainActivity a;
    private final int slot;
    private final Board.Btn draft;
    private AlertDialog dlg;

    private LinearLayout trackList, colorRow, sizeRow, imgOptions, actRow, soundBox, rightSound;
    private TextView actInfo, ctrlInfo, btNew, btSeen;
    private EditText etSearch;
    private View filterBox;
    private boolean onlyNew;

    /** passt der Titel zu Suche und NEU-Filter? */
    private boolean shows(Board.Track t) {
        if (onlyNew && !t.isNew) return false;
        String q = etSearch == null ? "" : etSearch.getText().toString().trim().toLowerCase(java.util.Locale.GERMANY);
        if (q.isEmpty()) return true;
        return t.display().toLowerCase(java.util.Locale.GERMANY).contains(q)
                || (t.name != null && t.name.toLowerCase(java.util.Locale.GERMANY).contains(q));
    }
    private android.widget.ImageView imgPreview;
    private android.widget.Button imgRemove;
    private Switch swLabel;
    private TextView sourceInfo;
    private EditText etName, etStart;

    Editor(MainActivity a, int slot) {
        this.a = a;
        this.slot = slot;
        this.draft = a.board.preset().btns[slot].copy();
    }

    void show() {
        LinearLayout cols = new LinearLayout(a);
        cols.setPadding(a.dp(24), a.dp(18), a.dp(24), a.dp(4));

        // ================================================================ linke Spalte: Name, Farbe, Quelle
        LinearLayout left = new LinearLayout(a);
        left.setOrientation(LinearLayout.VERTICAL);

        TextView head = Ui.text(a, "Kachel " + (slot + 1) + " einrichten", 20, Util.TEXT, true);
        left.addView(head);

        left.addView(Ui.section(a, "Funktion"));
        actRow = new LinearLayout(a);
        android.widget.HorizontalScrollView ahs = new android.widget.HorizontalScrollView(a);
        ahs.setHorizontalScrollBarEnabled(false);
        ahs.addView(actRow);
        left.addView(ahs);
        actInfo = Ui.text(a, "", 12, Util.MUTED, false);
        actInfo.setPadding(0, a.dp(6), 0, 0);
        left.addView(actInfo);

        left.addView(Ui.section(a, "Name"));
        etName = new EditText(a);
        etName.setText(draft.label);
        etName.setHint("leer = Datei- bzw. Ordnername");
        etName.setHintTextColor(Util.alpha(Util.MUTED, 0.7f));
        etName.setTextColor(Util.TEXT);
        etName.setSingleLine(true);
        etName.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        left.addView(etName, new LinearLayout.LayoutParams(-1, -2));

        left.addView(Ui.section(a, "Farbe"));
        colorRow = new LinearLayout(a);
        left.addView(colorRow);
        buildColors();
        final Switch swClear = Ui.toggle(a, "Transparenter Hintergrund (kein Kachelfeld – nur Bild und Name)", draft.transparent);
        left.addView(swClear);
        final Switch swFrame = Ui.toggle(a, "   … mit Rahmen in der Kachelfarbe", draft.frame);
        swFrame.setVisibility(draft.transparent ? View.VISIBLE : View.GONE);
        left.addView(swFrame);
        swClear.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                swFrame.setVisibility(on ? View.VISIBLE : View.GONE);
            }
        });

        left.addView(Ui.section(a, "Größe (Breite × Höhe)"));
        sizeRow = new LinearLayout(a);
        left.addView(sizeRow);
        buildSizes();

        left.addView(Ui.section(a, "Bild"));
        LinearLayout imgRow = new LinearLayout(a);
        imgRow.setGravity(Gravity.CENTER_VERTICAL);
        imgPreview = new android.widget.ImageView(a);
        imgPreview.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        imgRow.addView(imgPreview, new LinearLayout.LayoutParams(a.dp(56), a.dp(56)));
        View g0 = new View(a);
        imgRow.addView(g0, new LinearLayout.LayoutParams(a.dp(10), 1));
        imgRow.addView(Ui.button(a, "🖼  Bild wählen", Util.TEXT, false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.pickImage(MainActivity.REQ_IMAGE);
            }
        }));
        View g1 = new View(a);
        imgRow.addView(g1, new LinearLayout.LayoutParams(a.dp(10), 1));
        imgRemove = Ui.button(a, "Entfernen", Util.RED, false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setImage("");
            }
        });
        imgRow.addView(imgRemove);
        left.addView(imgRow);
        imgOptions = new LinearLayout(a);
        imgOptions.setOrientation(LinearLayout.VERTICAL);
        RadioGroup rgImg = new RadioGroup(a);
        rgImg.setOrientation(LinearLayout.HORIZONTAL);
        rgImg.addView(radio("füllt die Kachel", 20));
        rgImg.addView(radio("als Symbol über dem Namen", 21));
        rgImg.check(20 + draft.imageMode);
        rgImg.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup g, int id) {
                draft.imageMode = id - 20;
            }
        });
        imgOptions.addView(rgImg);
        swLabel = Ui.toggle(a, "Name auf der Kachel anzeigen", draft.showLabel);
        imgOptions.addView(swLabel);
        left.addView(imgOptions);
        showImage();

        soundBox = new LinearLayout(a);
        soundBox.setOrientation(LinearLayout.VERTICAL);
        left.addView(soundBox);
        soundBox.addView(Ui.section(a, "Titel"));
        LinearLayout src = new LinearLayout(a);
        src.addView(Ui.button(a, "♫  Titel wählen", Util.TEXT, false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.pickFiles();
            }
        }));
        View gap = new View(a);
        src.addView(gap, new LinearLayout.LayoutParams(a.dp(10), 1));
        src.addView(Ui.button(a, "📁  Ordner hinzufügen", Util.TEXT, false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.pickFolder();
            }
        }));
        soundBox.addView(src);
        sourceInfo = Ui.text(a, "", 13, Util.MUTED, false);
        sourceInfo.setPadding(0, a.dp(8), 0, a.dp(4));
        soundBox.addView(sourceInfo);
        // Suche + NEU-Filter
        LinearLayout filterRow = new LinearLayout(a);
        filterRow.setGravity(Gravity.CENTER_VERTICAL);
        etSearch = new EditText(a);
        etSearch.setHint("🔍  Titel suchen …");
        etSearch.setHintTextColor(Util.alpha(Util.MUTED, 0.7f));
        etSearch.setTextColor(Util.TEXT);
        etSearch.setSingleLine(true);
        etSearch.setTextSize(14);
        etSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence c, int s0, int n, int a0) {
            }

            @Override
            public void onTextChanged(CharSequence c, int s0, int b0, int n) {
                rebuildTracks();
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
            }
        });
        filterRow.addView(etSearch, new LinearLayout.LayoutParams(0, -2, 1f));
        btNew = Ui.text(a, "", 14, Util.TEXT, true);
        btNew.setPadding(a.dp(14), a.dp(6), a.dp(14), a.dp(6));
        btNew.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onlyNew = !onlyNew;
                rebuildTracks();
            }
        });
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
        nlp.leftMargin = a.dp(8);
        filterRow.addView(btNew, nlp);
        btSeen = Ui.text(a, "alle gesehen", 13, Util.MUTED, false);
        btSeen.setPadding(a.dp(12), a.dp(6), a.dp(12), a.dp(6));
        Ui.outline(btSeen, Util.MUTED);
        btSeen.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                for (Board.Track t : draft.tracks()) t.isNew = false;
                onlyNew = false;
                rebuildTracks();
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.leftMargin = a.dp(6);
        filterRow.addView(btSeen, slp);
        filterBox = filterRow;
        soundBox.addView(filterRow);
        trackList = new LinearLayout(a);
        trackList.setOrientation(LinearLayout.VERTICAL);
        soundBox.addView(trackList);
        rebuildTracks();

        // ================================================================ rechte Spalte: Verhalten
        LinearLayout rightOuter = new LinearLayout(a);
        rightOuter.setOrientation(LinearLayout.VERTICAL);
        LinearLayout right = new LinearLayout(a);
        right.setOrientation(LinearLayout.VERTICAL);
        rightOuter.addView(right);
        rightSound = right;
        ctrlInfo = Ui.text(a, "", 14, Util.TEXT, false);
        ctrlInfo.setPadding(0, a.dp(40), 0, 0);
        rightOuter.addView(ctrlInfo);

        right.addView(Ui.section(a, "Lautstärke & Blenden"));
        right.addView(Ui.slider(a, "Kachel-Gain", -24, 12, 0.5f, draft.gainDb, new Ui.Fmt() {
            @Override
            public String fmt(float v) {
                return Util.fmtDb(v);
            }
        }, new Ui.OnValue() {
            @Override
            public void value(float v) {
                draft.gainDb = v;
                a.engine.setPreviewBtnDb(v);
            }
        }));
        right.addView(Ui.slider(a, "Einblenden", 0, 10, 0.5f, draft.fadeIn, new Ui.Fmt() {
            @Override
            public String fmt(float v) {
                return Util.fmtSec(v);
            }
        }, new Ui.OnValue() {
            @Override
            public void value(float v) {
                draft.fadeIn = v;
            }
        }));

        right.addView(Ui.section(a, "Beim Stoppen"));
        final RadioGroup rgStop = new RadioGroup(a);
        RadioButton rDef = radio("Standard: ausblenden in " + Util.fmtSec(a.board.stopFadeSec), 1);
        RadioButton rHard = radio("Hart stoppen (sofort aus)", 2);
        RadioButton rOwn = radio("Eigene Fade-Zeit:", 3);
        rgStop.addView(rDef);
        rgStop.addView(rHard);
        rgStop.addView(rOwn);
        right.addView(rgStop);
        final float ownInit = draft.stopFade > 0 ? draft.stopFade : a.board.stopFadeSec;
        final float[] own = {ownInit};
        final LinearLayout ownSlider = Ui.slider(a, "   Fade-Zeit", 0.5f, 15, 0.5f, ownInit, new Ui.Fmt() {
            @Override
            public String fmt(float v) {
                return Util.fmtSec(v);
            }
        }, new Ui.OnValue() {
            @Override
            public void value(float v) {
                own[0] = v;
                if (draft.stopFade > 0) draft.stopFade = v;
            }
        });
        right.addView(ownSlider);
        rgStop.check(draft.stopFade < 0 ? 1 : (draft.stopFade == 0 ? 2 : 3));
        ownSlider.setAlpha(draft.stopFade > 0 ? 1f : 0.35f);
        rgStop.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup g, int id) {
                draft.stopFade = id == 1 ? -1f : (id == 2 ? 0f : own[0]);
                ownSlider.setAlpha(id == 3 ? 1f : 0.35f);
            }
        });

        right.addView(Ui.section(a, "Beim Start – andere laufende Sounds"));
        RadioGroup rgOthers = new RadioGroup(a);
        rgOthers.addView(radio("laufen weiter", 10));
        rgOthers.addView(radio("werden leiser (Durchsage, " + Util.fmtDb(a.board.duckDb) + ")", 11));
        rgOthers.addView(radio("werden ausgeblendet (" + Util.fmtSec(a.board.switchFadeSec) + ")", 12));
        rgOthers.check(10 + draft.others);
        rgOthers.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup g, int id) {
                draft.others = id - 10;
            }
        });
        right.addView(rgOthers);

        final Switch swImmediate = Ui.toggle(a, "Startet immer sofort – wird nie vorgemerkt (Torhupe, Effekte)", draft.immediate);
        right.addView(swImmediate);
        right.addView(Ui.text(a, "Kacheln mit „leiser“ oder „ausblenden“ starten ohnehin sofort. Andere Kacheln lassen sich trotzdem dahinter vormerken.", 12, Util.MUTED, false));

        right.addView(Ui.section(a, "Playlist"));
        final Switch swShuffle = Ui.toggle(a, "Zufällige Reihenfolge", draft.shuffle);
        final Switch swLoop = Ui.toggle(a, "Wiederholen (Einzeltitel in Schleife, Playlist endlos)", draft.loop);
        final Switch swResume = Ui.toggle(a, "Nächster Start macht beim nächsten Titel weiter", draft.resume);
        right.addView(swShuffle);
        right.addView(swLoop);
        right.addView(swResume);

        LinearLayout startRow = new LinearLayout(a);
        startRow.setGravity(Gravity.CENTER_VERTICAL);
        startRow.addView(Ui.text(a, "Start bei Sekunde", 14, Util.TEXT, false),
                new LinearLayout.LayoutParams(0, -2, 1f));
        etStart = new EditText(a);
        etStart.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etStart.setText(draft.startSec > 0 ? String.format(Locale.GERMANY, "%.1f", draft.startSec).replace(',', '.') : "");
        etStart.setHint("0");
        etStart.setHintTextColor(Util.MUTED);
        etStart.setTextColor(Util.TEXT);
        etStart.setGravity(Gravity.END);
        startRow.addView(etStart, new LinearLayout.LayoutParams(a.dp(90), -2));
        right.addView(startRow);
        TextView startHint = Ui.text(a, "z. B. um ein langes Intro zu überspringen", 12, Util.MUTED, false);
        right.addView(startHint);

        // ================================================================ zusammensetzen
        cols.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
        View sep = new View(a);
        sep.setBackgroundColor(Util.alpha(Util.MUTED, 0.25f));
        LinearLayout.LayoutParams sepLp = new LinearLayout.LayoutParams(a.dp(1), -1);
        sepLp.leftMargin = a.dp(20);
        sepLp.rightMargin = a.dp(20);
        cols.addView(sep, sepLp);
        cols.addView(rightOuter, new LinearLayout.LayoutParams(0, -2, 1f));
        buildActions();
        ScrollView sv = new ScrollView(a);
        sv.addView(cols);

        AlertDialog.Builder bld = new AlertDialog.Builder(a, Ui.DIALOG_THEME)
                .setView(Ui.withScrollHint(sv))
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Speichern", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        draft.label = etName.getText().toString().trim();
                        if (draft.shuffle != swShuffle.isChecked() || draft.resume != swResume.isChecked()) draft.nextUri = "";
                        draft.shuffle = swShuffle.isChecked();
                        draft.loop = swLoop.isChecked();
                        draft.resume = swResume.isChecked();
                        draft.immediate = swImmediate.isChecked();
                        draft.showLabel = swLabel.isChecked();
                        draft.transparent = swClear.isChecked();
                        draft.frame = swFrame.isChecked();
                        try {
                            String s = etStart.getText().toString().trim().replace(',', '.');
                            draft.startSec = s.isEmpty() ? 0f : Math.max(0f, Float.parseFloat(s));
                        } catch (Exception e) {
                            draft.startSec = 0f;
                        }
                        a.board.preset().btns[slot] = draft;
                        a.board.save();
                        a.refreshTiles();
                        a.analyzeLoudness(false); // neu gewaehlte Titel vermessen
                    }
                });
        if (!a.board.preset().btns[slot].isBlank()) {
            bld.setNeutralButton("Leeren", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    confirmClear();
                }
            });
        }
        dlg = bld.create();
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                a.engine.stopPreview();
                if (a.editor == Editor.this) a.editor = null;
            }
        });
        dlg.show();
        Ui.dialog(dlg);
        Ui.width(dlg, 0.86f);
        if (dlg.getWindow() != null) {
            // Tastatur nicht automatisch oeffnen
            dlg.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        }
    }

    private void confirmClear() {
        Ui.dialog(new AlertDialog.Builder(a, Ui.DIALOG_THEME)
                .setTitle("Kachel leeren?")
                .setMessage("Name, Titel und Einstellungen dieser Kachel werden entfernt.")
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Leeren", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Engine.Voice v = a.engine.voice(a.board.current, slot);
                        if (v != null) a.engine.stop(v, 0);
                        a.board.preset().btns[slot] = new Board.Btn();
                        a.board.save();
                        a.refreshTiles();
                    }
                })
                .show());
    }

    private RadioButton radio(String label, int id) {
        RadioButton r = new RadioButton(a);
        r.setId(id);
        r.setText(label);
        r.setTextColor(Util.TEXT);
        r.setTextSize(14);
        r.setButtonTintList(android.content.res.ColorStateList.valueOf(Util.AMBER));
        return r;
    }

    // ------------------------------------------------------------------ Groesse
    /**
     * Groesse in zwei Reihen: Breite 1-4 und Hoehe 1-3. Werte, die mit der jeweils anderen Richtung
     * nicht passen (Rand oder belegte Kachel im Weg), sind ausgegraut und sagen beim Antippen, warum.
     */
    private void buildSizes() {
        sizeRow.removeAllViews();
        sizeRow.setOrientation(LinearLayout.VERTICAL);
        sizeRow.addView(sizeLine("Breite", 4, true));
        sizeRow.addView(sizeLine("Höhe", 3, false));
    }

    private LinearLayout sizeLine(String label, int max, final boolean width) {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, a.dp(3), 0, a.dp(3));
        row.addView(Ui.text(a, label, 14, Util.TEXT, false), new LinearLayout.LayoutParams(a.dp(70), -2));
        for (int v = 1; v <= max; v++) {
            final int val = v;
            final int w = width ? v : draft.w, h = width ? draft.h : v;
            boolean sel = (width ? draft.w : draft.h) == v;
            final String why = a.board.preset().fits(slot, w, h, slot);
            TextView chip = Ui.text(a, String.valueOf(v), 15, Util.TEXT, true);
            if (sel) Ui.pill(chip, Util.AMBER);
            else Ui.outline(chip, why == null ? Util.TEXT : Util.MUTED);
            chip.setPadding(0, a.dp(6), 0, a.dp(6));
            chip.setAlpha(why == null || sel ? 1f : 0.4f);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View x) {
                    if (why != null) {
                        a.toast(w + "×" + h + " passt hier nicht: " + why);
                        return;
                    }
                    if (width) draft.w = val;
                    else draft.h = val;
                    buildSizes();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(a.dp(52), -2);
            lp.rightMargin = a.dp(8);
            row.addView(chip, lp);
        }
        return row;
    }

    // ------------------------------------------------------------------ Funktion
    private static final String[] ACT_HELP = {
            "Spielt Titel, eine Playlist oder einen Ordner ab.",
            "Große Stopp-Taste: stoppt alle laufenden Sounds – jeder so, wie auf seiner Kachel eingestellt (hart oder ausblenden). "
                    + "Vorgemerktes startet danach.",
            "Wie die Fade-Taste oben: blendet alles mit der Fade-Zeit aus. Vorgemerktes startet danach.",
            "Alles sofort aus – ohne Ausblenden.",
            "Nächster Titel der zuletzt gestarteten laufenden Playlist (mit kurzem Ausblenden).",
            "Für Ansagen über laufender Musik: tippen = alles, was gerade läuft, wird weich um den eingestellten Wert leiser "
                    + "(⚙ → „LEISER-Taste“); nochmal tippen = zurück auf die alte Lautstärke. Sounds, die währenddessen neu "
                    + "starten (z. B. Torhupe), bleiben normal laut. Die Kachel blinkt, solange abgesenkt ist."};

    private void buildActions() {
        actRow.removeAllViews();
        for (int i = 0; i < Board.ACT_NAMES.length; i++) {
            final int act = i;
            String lbl = i == 0 ? "♫  Sound" : Board.ACT_ICONS[i] + "  " + Board.ACT_NAMES[i].charAt(0)
                    + Board.ACT_NAMES[i].substring(1).toLowerCase(java.util.Locale.GERMANY);
            TextView chip = Ui.text(a, lbl, 14, Util.TEXT, true);
            if (draft.action == i) Ui.pill(chip, Util.AMBER);
            else Ui.outline(chip, Util.TEXT);
            chip.setPadding(a.dp(14), a.dp(7), a.dp(14), a.dp(7));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    draft.action = act;
                    buildActions();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = a.dp(6);
            actRow.addView(chip, lp);
        }
        boolean ctrl = draft.isControl();
        actInfo.setText(ACT_HELP[draft.action]);
        soundBox.setVisibility(ctrl ? View.GONE : View.VISIBLE);
        rightSound.setVisibility(ctrl ? View.GONE : View.VISIBLE);
        ctrlInfo.setVisibility(ctrl ? View.VISIBLE : View.GONE);
        ctrlInfo.setText(ctrl ? "Steuer-Kachel „" + Board.ACT_NAMES[draft.action] + "“\n\n" + ACT_HELP[draft.action]
                + "\n\nTipp: groß machen (z. B. 2×2) und rot färben – dann triffst du sie auch im Eifer des Gefechts. "
                + "Sie leuchtet, sobald etwas läuft. Titel bleiben gespeichert, falls du zurück auf „Sound“ stellst." : "");
    }

    // ------------------------------------------------------------------ Bild
    void setImage(String name) {
        draft.image = name == null ? "" : name;
        showImage();
    }

    private void showImage() {
        boolean has = !draft.image.isEmpty();
        imgPreview.setImageBitmap(has ? Images.get(a, draft.image) : null);
        imgPreview.setVisibility(has ? View.VISIBLE : View.GONE);
        imgRemove.setVisibility(has ? View.VISIBLE : View.GONE);
        imgOptions.setVisibility(has ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ Farben
    private void buildColors() {
        colorRow.removeAllViews();
        for (int i = 0; i < Util.PALETTE.length; i++) {
            final int idx = i;
            View dot = new View(a);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(Util.PALETTE[i]);
            if (i == draft.color) g.setStroke(a.dp(3), Color.WHITE);
            dot.setBackground(g);
            dot.setContentDescription(Util.PALETTE_NAMES[i]);
            dot.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    draft.color = idx;
                    buildColors();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(a.dp(34), a.dp(34));
            lp.rightMargin = a.dp(8);
            colorRow.addView(dot, lp);
        }
    }

    // ------------------------------------------------------------------ Titel
    void addFiles(List<Board.Track> tracks) {
        if (tracks.isEmpty()) return;
        if (draft.hasFolders()) {
            draft.folders.clear();
            draft.folderTracks.clear();
            a.toast("Ordner-Playlist durch einzelne Titel ersetzt");
        }
        for (Board.Track t : tracks) {
            boolean dup = false;
            for (Board.Track o : draft.files) if (o.uri.equals(t.uri)) dup = true;
            if (!dup) draft.files.add(t);
        }
        draft.nextUri = "";
        rebuildTracks();
    }

    /** weiteren Ordner zur Playlist dazunehmen (Titel der Ordner werden hintereinander gespielt) */
    void addFolder(String tree, String name, List<Board.Track> tracks) {
        for (Board.Folder f : draft.folders) {
            if (f.uri.equals(tree)) {
                a.toast("Ordner „" + name + "“ ist schon drin");
                return;
            }
        }
        if (!draft.files.isEmpty()) {
            draft.files.clear();
            a.toast("Einzelne Titel durch Ordner-Playlist ersetzt");
        }
        draft.folders.add(new Board.Folder(tree, name));
        List<Board.Track> merged = new java.util.ArrayList<>(draft.folderTracks);
        merged.addAll(tracks);
        draft.folderTracks = merged;
        draft.pos = 0;
        draft.nextUri = "";
        rebuildTracks();
    }

    private void removeFolder(Board.Folder f) {
        draft.folders.remove(f);
        List<Board.Track> rest = new java.util.ArrayList<>();
        for (Board.Track t : draft.folderTracks) if (!f.uri.equals(t.src)) rest.add(t);
        draft.folderTracks = rest;
        draft.pos = 0;
        draft.nextUri = "";
        rebuildTracks();
    }

    private void rebuildTracks() {
        trackList.removeAllViews();
        List<Board.Track> list = draft.tracks();
        int tags = 0;
        for (Board.Track t : list) if (t.hasTags()) tags++;
        String tagInfo = list.isEmpty() ? "" : "\n🏷  " + tags + " von " + list.size() + " Titeln mit MP3-Tags"
                + (tags < list.size() ? " (ohne Tags wird der Dateiname angezeigt)" : "");
        if (draft.hasFolders()) {
            sourceInfo.setText("📁  " + draft.folders.size() + (draft.folders.size() == 1 ? " Ordner" : " Ordner, hintereinander")
                    + " · " + list.size() + " Titel\n"
                    + "Neue Dateien kommen beim App-Start oder über Menü → „Ordner neu einlesen“ automatisch dazu." + tagInfo);
            for (final Board.Folder f : draft.folders) {
                LinearLayout row = new LinearLayout(a);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(a.dp(10), a.dp(4), a.dp(4), a.dp(4));
                int n = 0;
                for (Board.Track t : list) if (f.uri.equals(t.src)) n++;
                TextView name = Ui.text(a, "📁  " + f.name + "  ·  " + n + " Titel", 14, Util.AMBER, true);
                name.setSingleLine(true);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
                TextView del = small("✕");
                del.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        removeFolder(f);
                    }
                });
                row.addView(del);
                trackList.addView(row);
            }
        } else if (list.isEmpty()) {
            sourceInfo.setText("Noch kein Titel gewählt. Mehrere Titel oder Ordner = Playlist.");
        } else if (list.size() == 1) {
            sourceInfo.setText("Einzeltitel · Titel antippen = Lautstärke, Start & Ende" + tagInfo);
        } else {
            sourceInfo.setText("Playlist · " + list.size() + " Titel · Titel antippen = Lautstärke, Start & Ende" + tagInfo);
        }
        // Suche / NEU
        int nNew = 0;
        for (Board.Track t : list) if (t.isNew) nNew++;
        if (nNew == 0) onlyNew = false;
        filterBox.setVisibility(list.size() > 1 ? View.VISIBLE : View.GONE);
        btNew.setText(onlyNew ? "nur NEU (" + nNew + ") ✓" : "NEU (" + nNew + ")");
        if (onlyNew) Ui.pill(btNew, Util.AMBER);
        else Ui.outline(btNew, nNew > 0 ? Util.AMBER : Util.MUTED);
        btNew.setPadding(a.dp(14), a.dp(6), a.dp(14), a.dp(6));
        btNew.setEnabled(nNew > 0);
        btSeen.setVisibility(nNew > 0 ? View.VISIBLE : View.GONE);
        final List<Board.Track> shown = new java.util.ArrayList<>();
        for (Board.Track t : list) if (shows(t)) shown.add(t);
        if (shown.isEmpty() && !list.isEmpty()) {
            TextView none = Ui.text(a, "Kein Titel passt zur Suche.", 13, Util.MUTED, false);
            none.setPadding(a.dp(10), a.dp(8), 0, a.dp(8));
            trackList.addView(none);
        }
        for (int i = 0; i < list.size(); i++) {
            final int idx = i;
            final Board.Track t = list.get(i);
            if (!shows(t)) continue;
            final int shownIdx = shown.indexOf(t);
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(a.dp(10), a.dp(6), a.dp(4), a.dp(6));
            row.setBackgroundResource(android.R.drawable.list_selector_background);
            TextView name = Ui.text(a, (i + 1) + ".  " + t.display(), 14, Util.TEXT, false);
            if (t.isNew) {
                TextView nw = Ui.text(a, "NEU", 11, Util.BG, true);
                Ui.pill(nw, Util.AMBER);
                nw.setPadding(a.dp(7), a.dp(1), a.dp(7), a.dp(1));
                LinearLayout.LayoutParams nwlp = new LinearLayout.LayoutParams(-2, -2);
                nwlp.rightMargin = a.dp(8);
                row.addView(nw, nwlp);
            }
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            float[] tr = draft.trims.get(t.uri);
            if (tr != null) {
                String tt = (tr[0] > 0.05f ? "▶ " + Dialogs.fmtPos(tr[0]) : "") + (tr[0] > 0.05f && tr[1] > 0.05f ? " · " : "")
                        + (tr[1] > 0.05f ? "■ " + Dialogs.fmtPos(tr[1]) : "");
                TextView trim = Ui.text(a, tt, 12, Util.GREEN, false);
                trim.setPadding(a.dp(8), 0, 0, 0);
                row.addView(trim);
            }
            float g = a.board.gainOf(t);
            String gt = a.board.normOn ? (a.board.levelOf(t) == null ? "auto …" : "auto " + Util.fmtDb(a.board.autoGainOf(t))) : "";
            if (Math.abs(g) > 0.05f || gt.isEmpty()) gt += (gt.isEmpty() ? "" : "  ·  ") + Util.fmtDb(g);
            TextView gain = Ui.text(a, gt, 13, Math.abs(g) > 0.05f ? Util.AMBER : Util.MUTED, false);
            gain.setPadding(a.dp(8), 0, a.dp(8), 0);
            row.addView(gain);
            if (!draft.hasFolders()) {
                if (i > 0) {
                    TextView up = small("↑");
                    up.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            Board.Track x = draft.files.remove(idx);
                            draft.files.add(idx - 1, x);
                            draft.nextUri = "";
                            rebuildTracks();
                        }
                    });
                    row.addView(up);
                }
                TextView del = small("✕");
                del.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        draft.files.remove(idx);
                        draft.nextUri = "";
                        rebuildTracks();
                    }
                });
                row.addView(del);
            }
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Dialogs.trackGain(a, shown, shownIdx, draft, a.board.preset().btns[slot], new Runnable() {
                        @Override
                        public void run() {
                            rebuildTracks();
                        }
                    }, false);
                }
            });
            trackList.addView(row);
        }
    }

    private TextView small(String s) {
        TextView t = Ui.text(a, s, 16, Util.MUTED, false);
        t.setGravity(Gravity.CENTER);
        t.setBackgroundResource(android.R.drawable.list_selector_background);
        t.setLayoutParams(new LinearLayout.LayoutParams(a.dp(36), a.dp(32)));
        return t;
    }
}
