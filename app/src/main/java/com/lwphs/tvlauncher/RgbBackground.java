package com.lwphs.tvlauncher;

import android.graphics.Bitmap;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

/**
 * Almost-black background with very dark red, green and blue light slowly drifting around.
 * Kept cheap for the TV: the lights are drawn into a tiny bitmap (1/8 of the screen) and
 * stretched to full size, which soft gradients survive unchanged, at 15 frames per second.
 */
final class RgbBackground extends Drawable {

    // Very dark on purpose: the screen should read as black with a hint of colour.
    private static final int[] COLORS = {0xFF1E040B, 0xFF03180D, 0xFF050B22};
    private static final long LOOP_MS = 30000; // one full drift cycle
    private static final long FRAME_MS = 66;   // ~15 fps is plenty for motion this slow
    private static final int SCALE = 8;        // lights drawn at 1/8 resolution

    private final Paint[] lights = new Paint[COLORS.length];
    private final Paint stretch = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Matrix move = new Matrix();
    private final Runnable nextFrame = this::invalidateSelf;
    private Bitmap small;
    private Canvas smallCanvas;

    RgbBackground() {
        for (int i = 0; i < lights.length; i++) {
            lights[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
            lights[i].setBlendMode(BlendMode.PLUS); // lights add up where they overlap
        }
    }

    @Override
    protected void onBoundsChange(Rect b) {
        int w = Math.max(1, b.width() / SCALE), h = Math.max(1, b.height() / SCALE);
        small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        smallCanvas = new Canvas(small);
        float radius = w * 0.6f;
        for (int i = 0; i < lights.length; i++) {
            // Centred on (0,0); moved into place every frame with a matrix.
            lights[i].setShader(new RadialGradient(0, 0, radius,
                    COLORS[i], Color.TRANSPARENT, Shader.TileMode.CLAMP));
        }
    }

    @Override
    public void draw(Canvas canvas) {
        if (small == null) {
            canvas.drawColor(Color.BLACK);
            return;
        }
        int w = small.getWidth(), h = small.getHeight();
        smallCanvas.drawColor(Color.BLACK);
        double t = (SystemClock.uptimeMillis() % LOOP_MS) * 2 * Math.PI / LOOP_MS;
        for (int i = 0; i < lights.length; i++) {
            double phase = t + i * 2 * Math.PI / lights.length;
            move.setTranslate(w * (0.5f + 0.4f * (float) Math.cos(phase)),
                    h * (0.5f + 0.4f * (float) Math.sin(2 * phase)));
            lights[i].getShader().setLocalMatrix(move);
            smallCanvas.drawRect(0, 0, w, h, lights[i]);
        }
        canvas.drawBitmap(small, null, getBounds(), stretch);
        scheduleSelf(nextFrame, SystemClock.uptimeMillis() + FRAME_MS);
    }

    @Override
    public void setAlpha(int alpha) { }

    @Override
    public void setColorFilter(ColorFilter colorFilter) { }

    @Override
    public int getOpacity() {
        return PixelFormat.OPAQUE;
    }
}
