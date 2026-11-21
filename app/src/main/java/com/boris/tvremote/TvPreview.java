package com.boris.tvremote;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

/** Shows the latest TV still (16:9, letterboxed) plus an estimated cursor position. */
public class TvPreview extends View {
    public static final float TV_W = 1920f, TV_H = 1080f;

    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint img = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF screen = new RectF();
    private final Rect src = new Rect();
    private final RectF dst = new RectF();
    private final Path clip = new Path();
    private final Path arrow = new Path();
    private final float density;

    private Bitmap bitmap;
    private String message;
    private float cx = TV_W / 2, cy = TV_H / 2;
    private boolean showCursor;

    public TvPreview(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        frame.setColor(0xFF000000);
        text.setColor(0xFF8A93A0);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(13 * density);
        cursorFill.setColor(0xFFFFFFFF);
        cursorEdge.setColor(0xFF3B82F6);
        cursorEdge.setStyle(Paint.Style.STROKE);
        cursorEdge.setStrokeWidth(2 * density);
        cursorEdge.setStrokeJoin(Paint.Join.ROUND);
        setContentDescription("Экран телевизора");
    }

    /** View coordinates → TV pixels, or null if outside the drawn screen. */
    public float[] toTv(float x, float y) {
        if (screen.width() <= 0 || !screen.contains(x, y)) return null;
        return new float[]{(x - screen.left) / screen.width() * TV_W, (y - screen.top) / screen.height() * TV_H};
    }

    public void setBitmap(Bitmap b) { bitmap = b; message = null; invalidate(); }
    public boolean hasBitmap() { return bitmap != null; }
    public void setMessage(String m) { message = m; invalidate(); }

    /** Cursor in TV pixels (0..1920, 0..1080). */
    public void setCursor(float x, float y, boolean visible) {
        cx = Math.max(0, Math.min(TV_W, x));
        cy = Math.max(0, Math.min(TV_H, y));
        showCursor = visible;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        // fit a 16:9 screen into the view
        float sw = w, sh = w * 9f / 16f;
        if (sh > h) { sh = h; sw = h * 16f / 9f; }
        float left = (w - sw) / 2f, top = (h - sh) / 2f;
        screen.set(left, top, left + sw, top + sh);
        float r = 14 * density;

        c.save();
        clip.reset();
        clip.addRoundRect(screen, r, r, Path.Direction.CW);
        c.clipPath(clip);
        c.drawRect(screen, frame);
        if (bitmap != null) {
            src.set(0, 0, bitmap.getWidth(), bitmap.getHeight());
            float bs = Math.min(sw / bitmap.getWidth(), sh / bitmap.getHeight());
            float bw = bitmap.getWidth() * bs, bh = bitmap.getHeight() * bs;
            dst.set(left + (sw - bw) / 2f, top + (sh - bh) / 2f, left + (sw + bw) / 2f, top + (sh + bh) / 2f);
            c.drawBitmap(bitmap, src, dst, img);
        }
        if (bitmap == null || message != null) {
            String m = message != null ? message : "…";
            c.drawText(m, screen.centerX(), screen.centerY() - (text.descent() + text.ascent()) / 2f, text);
        }
        c.restore();

        if (showCursor) {
            float x = left + cx / TV_W * sw, y = top + cy / TV_H * sh, s = 14 * density;
            arrow.reset();
            arrow.moveTo(x, y);
            arrow.lineTo(x, y + s);
            arrow.lineTo(x + s * 0.3f, y + s * 0.72f);
            arrow.lineTo(x + s * 0.72f, y + s * 0.72f);
            arrow.close();
            c.drawPath(arrow, cursorFill);
            c.drawPath(arrow, cursorEdge);
        }
    }
}
