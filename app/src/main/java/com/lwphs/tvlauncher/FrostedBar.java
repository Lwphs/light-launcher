package com.lwphs.tvlauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * Background of the top bar: a blurred copy of whatever has scrolled under it, under a translucent
 * tint (frosted glass). Android can't blur what's behind a view, so this draws the scrolling content
 * itself and blurs its own drawing. It only redraws when told to (scroll, new content, focus).
 */
public final class FrostedBar extends View {

    private static final int TINT = 0x800A0B0F; // ~50% dark panel

    private View source;
    private final int[] mine = new int[2];
    private final int[] theirs = new int[2];

    public FrostedBar(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /** Copies {@code scroll}'s content (as it sits under this view), blurred by {@code radius} px. */
    void follow(View scroll, float radius) {
        source = scroll;
        setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
    }

    /** No size of its own: it takes the top bar's height (given exactly by its parent), never more. */
    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int height = MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(heightSpec) : 0;
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (source != null) {
            getLocationInWindow(mine);
            source.getLocationInWindow(theirs);
            canvas.save();
            // Drawing a scroll view directly skips its scroll offset, so apply it here.
            canvas.translate(theirs[0] - mine[0] - source.getScrollX(), theirs[1] - mine[1] - source.getScrollY());
            source.draw(canvas);
            canvas.restore();
        }
        canvas.drawColor(TINT);
    }
}
