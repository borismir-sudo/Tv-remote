package com.boris.tvremote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/** Round 4-way pad with an OK button in the middle, drawn by hand so it is always centred. */
public class DpadView extends View {

    public interface OnKey { void onKey(String name); }

    private static final String[] NAMES = {"UP", "RIGHT", "DOWN", "LEFT", "ENTER"};

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wedge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ok = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float density;
    private OnKey listener;
    private int pressed = -1;

    private final Runnable repeat = new Runnable() {
        @Override public void run() {
            if (pressed >= 0 && pressed < 4 && listener != null) {
                listener.onKey(NAMES[pressed]);
                handler.postDelayed(this, 130);
            }
        }
    };

    public DpadView(Context c, int ringColor, int okColor, int fg) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        ring.setColor(ringColor);
        wedge.setColor(0x22FFFFFF);
        ok.setColor(okColor);
        arrow.setColor(fg);
        arrow.setStyle(Paint.Style.STROKE);
        arrow.setStrokeWidth(3.2f * density);
        arrow.setStrokeCap(Paint.Cap.ROUND);
        arrow.setStrokeJoin(Paint.Join.ROUND);
        label.setColor(0xFFFFFFFF);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        setContentDescription("Джойстик: стрелки и OK");
    }

    public void setOnKey(OnKey l) { listener = l; }

    private float radius() { return Math.min(getWidth(), getHeight()) / 2f - 4 * density; }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f, r = radius();
        if (r <= 0) return;
        c.drawCircle(cx, cy, r, ring);
        if (pressed >= 0 && pressed < 4) {
            oval.set(cx - r, cy - r, cx + r, cy + r);
            float start = new float[]{225, 315, 45, 135}[pressed];
            c.drawArc(oval, start, 90, true, wedge);
        }
        float okR = r * 0.40f;
        ok.setAlpha(pressed == 4 ? 200 : 255);
        c.drawCircle(cx, cy, okR, ok);
        label.setTextSize(okR * 0.48f);
        c.drawText("OK", cx, cy - (label.descent() + label.ascent()) / 2f, label);

        float d = r * 0.72f, s = r * 0.10f;
        chevron(c, cx, cy - d, s, 0);
        chevron(c, cx + d, cy, s, 1);
        chevron(c, cx, cy + d, s, 2);
        chevron(c, cx - d, cy, s, 3);
    }

    private void chevron(Canvas c, float x, float y, float s, int dir) {
        path.reset();
        switch (dir) {
            case 0: path.moveTo(x - s, y + s / 2); path.lineTo(x, y - s / 2); path.lineTo(x + s, y + s / 2); break;
            case 1: path.moveTo(x - s / 2, y - s); path.lineTo(x + s / 2, y); path.lineTo(x - s / 2, y + s); break;
            case 2: path.moveTo(x - s, y - s / 2); path.lineTo(x, y + s / 2); path.lineTo(x + s, y - s / 2); break;
            default: path.moveTo(x + s / 2, y - s); path.lineTo(x - s / 2, y); path.lineTo(x + s / 2, y + s); break;
        }
        c.drawPath(path, arrow);
    }

    private int region(float x, float y) {
        float dx = x - getWidth() / 2f, dy = y - getHeight() / 2f, r = radius();
        float dist = (float) Math.hypot(dx, dy);
        if (dist > r) return -1;
        if (dist < r * 0.40f) return 4;
        if (Math.abs(dx) > Math.abs(dy)) return dx > 0 ? 1 : 3;
        return dy > 0 ? 2 : 0;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int reg = region(e.getX(), e.getY());
                if (reg < 0) return false;
                getParent().requestDisallowInterceptTouchEvent(true);
                pressed = reg;
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                if (listener != null) listener.onKey(NAMES[reg]);
                if (reg < 4) handler.postDelayed(repeat, 450);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(repeat);
                pressed = -1;
                invalidate();
                if (e.getActionMasked() == MotionEvent.ACTION_UP) performClick();
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() { return super.performClick(); }
}
