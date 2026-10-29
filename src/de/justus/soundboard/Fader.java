package de.justus.soundboard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * Dicker Fader fuer grobe Finger: die ganze Pille ist Griffflaeche.
 * Gezogen wird RELATIV (Finger irgendwo aufsetzen und schieben) - ein versehentliches Antippen
 * springt also nie auf einen anderen Wert. Bipolar (Track-Gain): Fuellung ab 0 dB, rastet bei 0 dB ein.
 * Lange halten ohne zu schieben = Standardwert (falls gesetzt), Antippen der Beschriftung = onLabelTap.
 */
final class Fader extends View {

    interface Listener {
        void onValue(float v);
    }

    float min = 0, max = 1, step = 0.01f, value = 0;
    boolean bipolar;           // Fuellung von 0 aus (Track-Gain)
    Float resetTo = null;      // lange halten = dieser Wert
    String label = "";
    int colorPos = Util.GREEN, colorNeg = Util.AMBER;
    Listener listener;
    Runnable onLabelTap;
    interface Fmt {
        String fmt(float v);
    }
    Fmt fmt = new Fmt() {
        @Override
        public String fmt(float v) {
            return String.valueOf(v);
        }
    };

    private final float d;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    /** Finger liegt gerade auf dem Fader (dann nicht von aussen nachfuehren) */
    boolean touching;
    private float downX, downValue;
    private long downAt;
    private boolean moved, longDone, inLabel;
    private int lastDetentSide;

    Fader(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        txt.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        setHapticFeedbackEnabled(true);
    }

    void setValue(float v) {
        value = clamp(v);
        invalidate();
    }

    private float clamp(float v) {
        return Math.max(min, Math.min(max, v));
    }

    private float xOf(float v, float x0, float x1) {
        return x0 + (x1 - x0) * (v - min) / (max - min);
    }

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        float rad = h / 2f;
        r.set(0, 0, w, h);
        boolean on = isEnabled();

        // Grundflaeche
        p.setShader(null);
        p.setColor(Util.SURFACE2);
        cv.drawRoundRect(r, rad, rad, p);

        // Fuellung
        float x0 = 0, x1 = w;
        float xv = xOf(value, x0, x1);
        float xs = bipolar ? xOf(Math.max(min, Math.min(max, 0f)), x0, x1) : x0;
        int col = value >= 0 || !bipolar ? colorPos : colorNeg;
        if (!on) col = Util.MUTED;
        float a = Math.min(xs, xv), b = Math.max(xs, xv);
        if (b - a > 0.5f) {
            p.setShader(new LinearGradient(a, 0, b, 0, Util.alpha(col, 0.55f), Util.alpha(col, 0.95f), Shader.TileMode.CLAMP));
            cv.save();
            cv.clipRect(a, 0, b, h);
            cv.drawRoundRect(r, rad, rad, p);
            cv.restore();
            p.setShader(null);
        }
        // 0-dB-Markierung
        if (bipolar) {
            p.setColor(Util.alpha(Color.WHITE, 0.35f));
            cv.drawRect(xs - 1 * d, h * 0.22f, xs + 1 * d, h * 0.78f, p);
        }
        // Griff
        float gx = Math.max(rad * 0.55f, Math.min(w - rad * 0.55f, xv));
        p.setColor(Color.argb(90, 0, 0, 0));
        cv.drawRoundRect(gx - 6 * d, 4 * d + 1.5f * d, gx + 6 * d, h - 4 * d + 1.5f * d, 6 * d, 6 * d, p);
        p.setColor(on ? Color.WHITE : Util.MUTED);
        cv.drawRoundRect(gx - 5 * d, 4 * d, gx + 5 * d, h - 4 * d, 5 * d, 5 * d, p);

        // Beschriftung links, Wert rechts (in der Pille)
        txt.setTextSize(13 * d);
        txt.setLetterSpacing(0.08f);
        txt.setColor(Util.alpha(Color.WHITE, on ? 0.85f : 0.4f));
        txt.setTextAlign(Paint.Align.LEFT);
        float ty = h / 2f + 4.5f * d;
        cv.drawText(label, 16 * d, ty, txt);
        txt.setLetterSpacing(0f);
        txt.setTextSize(16 * d);
        txt.setTextAlign(Paint.Align.RIGHT);
        txt.setColor(on ? Color.WHITE : Util.alpha(Color.WHITE, 0.4f));
        cv.drawText(fmt.fmt(value), w - 16 * d, ty + 1 * d, txt);
    }

    private final Runnable longPress = new Runnable() {
        @Override
        public void run() {
            if (moved || resetTo == null || !isEnabled()) return;
            longDone = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            setValue(resetTo);
            if (listener != null) listener.onValue(value);
        }
    };

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled()) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                touching = true;
                downX = e.getX();
                downValue = value;
                downAt = SystemClock.uptimeMillis();
                moved = false;
                longDone = false;
                inLabel = e.getX() < getWidth() * 0.3f;
                lastDetentSide = (int) Math.signum(value);
                postDelayed(longPress, 650);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - downX;
                if (!moved && Math.abs(dx) < 8 * d) return true;
                if (longDone) return true;
                if (!moved) {
                    moved = true;
                    removeCallbacks(longPress);
                    downX = e.getX(); // ab hier relativ ziehen, ohne Sprung
                    dx = 0;
                }
                // ganze Breite = ganzer Bereich
                float v = downValue + dx / Math.max(1, getWidth()) * (max - min);
                v = Math.round(v / step) * step;
                if (bipolar) {
                    // Rasten bei 0 dB
                    if (Math.abs(v) < step * 1.5f) v = 0f;
                    int side = (int) Math.signum(v);
                    if (side != lastDetentSide) {
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        lastDetentSide = side;
                    }
                }
                v = clamp(v);
                if (v != value) {
                    value = v;
                    invalidate();
                    if (listener != null) listener.onValue(value);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                touching = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                if (e.getActionMasked() == MotionEvent.ACTION_UP && !moved && !longDone && inLabel
                        && SystemClock.uptimeMillis() - downAt < 400 && onLabelTap != null) {
                    onLabelTap.run();
                }
                return true;
        }
        return false;
    }

    @Override
    public void setEnabled(boolean en) {
        super.setEnabled(en);
        invalidate();
    }
}
