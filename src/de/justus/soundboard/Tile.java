package de.justus.soundboard;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

/**
 * Eine Kachel, komplett selbst gezeichnet:
 * ruhend = dunkel getoent mit farbigem Rand, laufend = voll eingefaerbt mit Fortschrittsbalken und Restzeit.
 */
final class Tile extends View {

    Board.Btn btn;
    Engine.Voice voice;
    boolean editMode, selected;
    float stopSec;
    /** Position in der Warteschlange (0 = als Naechstes), -1 = nicht vorgemerkt */
    int queuePos = -1;
    /** Steuer-Kachel hat gerade etwas zu tun (es laeuft etwas) */
    boolean armed;
    /** Groesse in Rasterzellen (fuer das Layout) */
    int spanW = 1, spanH = 1;

    private final float d;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint small = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint time = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final RectF dst = new RectF();
    private final Path clip = new Path();
    private final Paint imgPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint shade = new Paint();
    private final DashPathEffect dash;

    // Zwischenspeicher fuer den Titel-Umbruch (StaticLayout ist teuer)
    private String cachedText;
    private int cachedW;
    private float cachedSize, cachedH;
    private StaticLayout cachedLayout;

    Tile(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        label.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        small.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        time.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        time.setTextAlign(Paint.Align.RIGHT);
        dash = new DashPathEffect(new float[]{6 * d, 5 * d}, 0);
        setHapticFeedbackEnabled(true);
    }

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        float rad = 14 * d, sw = 2 * d;
        r.set(sw / 2, sw / 2, w - sw / 2, h - sw / 2);
        boolean empty = btn == null || btn.isBlank();

        // ---------------------------------------------------------------- leere Kachel
        if (empty) {
            fill.setColor(Util.alpha(Util.SURFACE, editMode ? 0.9f : 0.55f));
            cv.drawRoundRect(r, rad, rad, fill);
            if (editMode) {
                stroke.setPathEffect(dash);
                stroke.setStrokeWidth(sw);
                stroke.setColor(selected ? Util.AMBER : Util.alpha(Util.MUTED, 0.6f));
                cv.drawRoundRect(r, rad, rad, stroke);
                stroke.setPathEffect(null);
                label.setColor(Util.alpha(Util.MUTED, 0.8f));
                label.setTextSize(30 * d);
                label.setTextAlign(Paint.Align.CENTER);
                cv.drawText("+", w / 2f, h / 2f + 10 * d, label);
                label.setTextAlign(Paint.Align.LEFT);
            }
            return;
        }

        int col = Util.color(btn.color);
        boolean playing = voice != null;
        boolean paused = playing && voice.paused;
        boolean fading = playing && voice.stopping;
        Bitmap img = btn.image.isEmpty() ? null : Images.get(getContext(), btn.image);
        boolean bgImage = img != null && btn.imageMode == 0;

        // ---------------------------------------------------------------- Flaeche
        int bg;
        boolean ctrlOn = btn.isControl() && armed;
        if (ctrlOn && btn.action == Board.ACT_DUCK) {
            // LEISER aktiv: blinkt mit 1 Hz (0,5 s an, 0,5 s aus)
            long now = SystemClock.uptimeMillis();
            ctrlOn = (now / 500) % 2 == 0;
            postInvalidateDelayed(500 - now % 500 + 5);
        }
        if (ctrlOn) {
            bg = Util.mix(Util.BG, col, 0.8f); // Steuer-Kachel "scharf": es laeuft etwas
        } else if (playing && !paused) {
            // laufend: volle Farbe, beim Ausblenden pulsierend
            float pulse = fading ? 0.55f + 0.45f * (float) Math.abs(Math.sin(SystemClock.uptimeMillis() / 180.0)) : 1f;
            bg = Util.mix(Util.BG, col, 0.35f + 0.65f * pulse);
        } else if (paused) {
            bg = Util.mix(Util.BG, col, 0.45f);
        } else {
            bg = Util.mix(Util.BG, col, 0.20f); // gespielt: nur der Punkt, keine blassere Farbe
        }
        boolean clear = btn.transparent; // transparente Kachel: kein Feld, nur Bild/Text
        if (clear) {
            if (playing) {
                fill.setColor(Util.alpha(col, paused ? 0.2f : 0.35f));
                cv.drawRoundRect(r, rad, rad, fill);
            }
        } else {
            fill.setColor(bg);
            cv.drawRoundRect(r, rad, rad, fill);
        }

        float pad = 10 * d;
        float bottomBand = 22 * d; // unten: Symbole bzw. Fortschritt

        // ---------------------------------------------------------------- Bild als Hintergrund
        if (bgImage) {
            clip.reset();
            clip.addRoundRect(r, rad, rad, Path.Direction.CW);
            cv.save();
            cv.clipPath(clip);
            imgPaint.setAlpha(playing ? 255 : 215);
            drawCrop(cv, img, w, h);
            // Farbschleier beim Laufen + dunkler Verlauf unten, damit Schrift lesbar bleibt
            if (playing && !paused) {
                fill.setColor(Util.alpha(col, fading ? 0.25f : 0.35f));
                cv.drawRect(0, 0, w, h, fill);
            }
            if (!clear || btn.showLabel || playing) { // Verlauf nur, wo Schrift lesbar sein muss
                shade.setShader(new LinearGradient(0, h * 0.35f, 0, h, Color.TRANSPARENT, Color.argb(225, 8, 10, 14), Shader.TileMode.CLAMP));
                cv.drawRect(0, 0, w, h, shade);
            }
            cv.restore();
        }

        boolean special = selected || queuePos >= 0;
        boolean framed = clear && btn.frame; // transparent, aber mit Rahmen in der Kachelfarbe
        if (!clear || framed || special || playing || editMode) {
            stroke.setStrokeWidth(special ? 4 * d : ((playing && (bgImage || clear)) ? 4 * d : (framed ? 3 * d : sw)));
            stroke.setColor(special ? Util.AMBER
                    : (playing ? (bgImage || clear ? col : Util.alpha(Color.WHITE, 0.85f))
                    : (framed ? col
                    : (clear ? Util.alpha(Util.MUTED, 0.35f) : Util.alpha(col, 0.9f)))));
            if (clear && !framed && !playing && !special) stroke.setPathEffect(dash); // im Bearbeiten-Modus sichtbar machen
            cv.drawRoundRect(r, rad, rad, stroke);
            stroke.setPathEffect(null);
        }

        boolean darkText = !bgImage && !clear && ((playing && !paused) || ctrlOn) && Util.isLight(bg);
        int fg = darkText ? Color.rgb(20, 20, 24) : Util.TEXT;
        int fgMuted = darkText ? Color.argb(190, 20, 20, 24) : (playing || bgImage ? Color.argb(215, 255, 255, 255) : Util.MUTED);

        // ---------------------------------------------------------------- Unterzeile (Titel / Ordner)
        String sub;
        if (playing) sub = voice.trackName();
        else if (img != null && !btn.isPlaylist()) sub = "";
        else if (editMode && btn.hasFolders()) sub = "📁 " + btn.foldersLabel() + " · " + btn.folderTracks.size();
        else if (editMode && btn.files.size() > 1) sub = "♫ " + btn.files.size() + " Titel";
        else if (btn.isPlaylist() && btn.nextTrack() != null) sub = "⏭ " + btn.nextTrack().display(); // ruhend: naechster Titel
        else if (btn.files.size() == 1 && !btn.label.trim().isEmpty()) sub = btn.files.get(0).display();
        else sub = "";
        small.setTextSize(Math.max(11 * d, Math.min(14 * d, h * 0.11f)));
        small.setColor(fgMuted);
        float subH = sub.isEmpty() ? 0 : small.getTextSize() * 1.3f;

        // ---------------------------------------------------------------- Kachelname (max. 2 Zeilen, schrumpft bei Bedarf)
        int tw = (int) (w - 2 * pad);
        boolean badge = !editMode && btn.isPlaylist() && !btn.isControl() && btn.tracks().size() > 1;
        float regionTop = badge ? 28 * d : pad, regionBottom = h - bottomBand - 2 * d; // Platz fuer den 3/12-Zaehler
        float maxSize = Math.min(Math.min(h * 0.22f, w * 0.15f), 40 * d);
        boolean showText = btn.showLabel || img == null;
        String text = btn.title();
        StaticLayout sl = null;
        float blockH = subH;
        if (showText) {
            float availText = regionBottom - regionTop - subH;
            if (img != null && btn.imageMode == 1) availText = Math.min(availText, (regionBottom - regionTop) * 0.42f);
            sl = layoutFor(text, tw, img != null ? Math.min(maxSize, 22 * d) : maxSize, availText);
            blockH += sl.getHeight();
        }

        float top;
        if (img != null && btn.imageMode == 1) {
            // Bild als Symbol: oben das Bild, darunter Name
            float imgBottom = regionBottom - blockH - (blockH > 0 ? 4 * d : 0);
            drawFit(cv, img, pad, regionTop, w - pad, imgBottom, paused ? 150 : 255);
            top = imgBottom + 4 * d;
        } else if (bgImage) {
            top = regionBottom - blockH; // Schrift unten auf dem Bild
        } else {
            top = regionTop + Math.max(0, (regionBottom - regionTop - blockH) / 2f);
        }
        if (sl != null) {
            label.setColor(fg);
            if (bgImage) label.setShadowLayer(4 * d, 0, 1 * d, Color.BLACK);
            cv.save();
            cv.translate(pad, top);
            sl.draw(cv);
            cv.restore();
            label.clearShadowLayer();
            top += sl.getHeight();
        }
        if (!sub.isEmpty()) {
            String s = TextUtils.ellipsize(sub, small, tw, TextUtils.TruncateAt.END).toString();
            small.setTextAlign(Paint.Align.CENTER);
            cv.drawText(s, w / 2f, top + small.getTextSize() * 1.05f, small);
            small.setTextAlign(Paint.Align.LEFT);
        }

        // ---------------------------------------------------------------- unten: Fortschritt oder Symbole
        float by = h - 9 * d;
        if (playing) {
            long dur = voice.duration(), pos = voice.position();
            float p = dur > 0 ? Math.min(1f, pos / (float) dur) : 0f;
            float bx0 = pad, bx1 = w - pad - 44 * d, bh = 5 * d;
            fill.setColor(darkText ? Color.argb(60, 0, 0, 0) : Color.argb(70, 255, 255, 255));
            cv.drawRoundRect(bx0, by - bh, bx1, by, bh / 2, bh / 2, fill);
            fill.setColor(darkText ? Color.argb(200, 0, 0, 0) : Color.WHITE);
            cv.drawRoundRect(bx0, by - bh, bx0 + (bx1 - bx0) * p, by, bh / 2, bh / 2, fill);
            time.setTextSize(13 * d);
            time.setColor(fg);
            String t = paused ? "❚❚" : (fading ? "↘" : "-" + Util.fmtTime(dur - pos));
            cv.drawText(t, w - pad, by + 1 * d, time);
        } else if (btn.isControl()) {
            small.setTextSize(11 * d);
            small.setColor(darkText ? Color.argb(190, 20, 20, 24) : Util.alpha(Util.MUTED, 0.95f));
            String[] hints = {"", "stoppt alles wie eingestellt", "blendet alles aus", "alles sofort aus", "nächster Titel",
                    "leiser · tippen = wieder laut"};
            String hnt = ctrlOn ? hints[btn.action] : (btn.action == Board.ACT_DUCK ? "tippen = laufende Musik leiser" : "nichts läuft");
            cv.drawText(TextUtils.ellipsize(hnt, small, w - 2 * pad, TextUtils.TruncateAt.END).toString(), pad, by, small);
        } else if (!btn.isEmpty()) {
            small.setTextSize(11 * d);
            small.setColor(bgImage ? Color.argb(220, 255, 255, 255) : Util.alpha(Util.MUTED, 0.95f));
            StringBuilder b = new StringBuilder();
            if (btn.startsImmediately()) b.append("⚡ ");
            if (btn.shuffle) b.append("🔀 ");
            if (btn.loop) b.append("🔁 ");
            if (btn.others == Board.OTHERS_DUCK) b.append("🔉 ");
            if (btn.others == Board.OTHERS_FADE) b.append("⤵ ");
            if (stopSec <= 0.01f) b.append("■ hart");
            else b.append("↘ ").append(Util.fmtSec(stopSec));
            if (Math.abs(btn.gainDb) > 0.05f) b.append("  ").append(Util.fmtDb(btn.gainDb));
            String s = TextUtils.ellipsize(b.toString(), small, w - 2 * pad - (btn.played ? 16 * d : 0), TextUtils.TruncateAt.END).toString();
            cv.drawText(s, pad, by, small);
            if (btn.played && !editMode) {
                fill.setColor(Util.alpha(col, 0.9f));
                cv.drawCircle(w - pad - 4 * d, by - 4 * d, 5 * d, fill);
            }
        }

        // ---------------------------------------------------------------- Playlist-Zaehler oben rechts: "3/12"
        if (!editMode && btn.isPlaylist() && !btn.isControl()) {
            String cnt = null;
            int n = btn.tracks().size();
            if (n > 1) {
                // Durchgang: das wievielte Lied (laeuft = aktuelles, ruhend = naechstes); 🔀 = Zufall
                cnt = (btn.shuffle ? "🔀 " : "") + (playing ? btn.counterPlaying(voice.cur) : btn.counterIdle()) + "/" + n;
            }
            if (cnt != null) {
                small.setTextSize(13 * d);
                float cw = small.measureText(cnt) + 14 * d;
                float x1 = w - 6 * d, x0 = x1 - cw;
                fill.setColor(playing ? Color.argb(110, 0, 0, 0) : Util.alpha(col, 0.35f));
                cv.drawRoundRect(x0, 6 * d, x1, 27 * d, 10 * d, 10 * d, fill);
                small.setColor(Color.WHITE);
                small.setTextAlign(Paint.Align.CENTER);
                cv.drawText(cnt, (x0 + x1) / 2, 21.5f * d, small);
                small.setTextAlign(Paint.Align.LEFT);
            }
        }

        // ---------------------------------------------------------------- vorgemerkt
        if (queuePos >= 0) {
            String q = queuePos == 0 ? "⏭ als Nächstes" : "⏭ " + (queuePos + 1) + ".";
            small.setTextSize(12 * d);
            float qw = small.measureText(q) + 14 * d;
            fill.setColor(Util.AMBER);
            cv.drawRoundRect(6 * d, 6 * d, 6 * d + qw, 26 * d, 10 * d, 10 * d, fill);
            small.setColor(Color.rgb(20, 20, 24));
            cv.drawText(q, 13 * d, 21 * d, small);
        }
        if (editMode) {
            small.setTextSize(12 * d);
            small.setColor(Util.AMBER);
            small.setTextAlign(Paint.Align.RIGHT);
            String size = btn.w > 1 || btn.h > 1 ? btn.w + "×" + btn.h + "  ✎" : "✎";
            cv.drawText(size, w - 8 * d, 18 * d, small);
            small.setTextAlign(Paint.Align.LEFT);
        }
        if (fading) postInvalidateOnAnimation(); // Puls-Animation
    }

    /** Bild so skalieren, dass es die Kachel fuellt (Raender werden abgeschnitten) */
    private void drawCrop(Canvas cv, Bitmap b, int w, int h) {
        float s = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight());
        float bw = b.getWidth() * s, bh = b.getHeight() * s;
        dst.set((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2);
        cv.drawBitmap(b, null, dst, imgPaint);
    }

    /** Bild vollstaendig in das Rechteck einpassen (Seitenverhaeltnis bleibt) */
    private void drawFit(Canvas cv, Bitmap b, float l, float t, float rr, float bt, int alpha) {
        float aw = rr - l, ah = bt - t;
        if (aw <= 0 || ah <= 0) return;
        float s = Math.min(aw / b.getWidth(), ah / b.getHeight());
        float bw = b.getWidth() * s, bh = b.getHeight() * s;
        dst.set(l + (aw - bw) / 2, t + (ah - bh) / 2, l + (aw + bw) / 2, t + (ah + bh) / 2);
        imgPaint.setAlpha(alpha);
        cv.drawBitmap(b, null, dst, imgPaint);
    }

    /** Umbruch auf hoechstens 2 Zeilen; Schrift wird kleiner, bis es passt */
    private StaticLayout layoutFor(String text, int w, float maxSize, float maxH) {
        if (w <= 0) w = 1;
        if (cachedLayout != null && text.equals(cachedText) && w == cachedW && maxSize == cachedSize && maxH == cachedH) return cachedLayout;
        float size = maxSize;
        StaticLayout sl;
        while (true) {
            label.setTextSize(size);
            sl = StaticLayout.Builder.obtain(text, 0, text.length(), label, w)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setMaxLines(2)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setIncludePad(false)
                    .build();
            boolean fits = sl.getLineCount() <= 2 && sl.getHeight() <= maxH && sl.getEllipsisCount(sl.getLineCount() - 1) == 0;
            if (fits || size <= 12 * d) break;
            size -= 1 * d;
        }
        cachedText = text;
        cachedW = w;
        cachedSize = maxSize;
        cachedH = maxH;
        cachedLayout = sl;
        return sl;
    }

    void invalidateText() {
        cachedLayout = null;
    }
}
