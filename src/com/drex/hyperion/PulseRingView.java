package com.drex.hyperion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

/**
 * Anillos expansivos (pulso) detrás del botón de conexión.
 * 60fps vía Choreographer, cero allocations en onDraw, se pausa si no es visible.
 */
public class PulseRingView extends View {

    private static final int RINGS = 3;
    private static final float PERIOD_S = 2.4f;

    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint idlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private boolean active;
    private int ringR = 53, ringG = 224, ringB = 255;

    // ---- frame loop ----
    private final Choreographer choreographer = Choreographer.getInstance();
    private final Choreographer.FrameCallback frameCb = new Choreographer.FrameCallback() {
        @Override public void doFrame(long tNanos) {
            frameScheduled = false;
            if (!loopOn || !isShown()) { loopOn = false; return; }
            float dt = lastNanos == 0 ? 1f / 60f
                    : Math.min(0.05f, (tNanos - lastNanos) / 1_000_000_000f);
            lastNanos = tNanos;
            timeS += dt;
            invalidate();
            scheduleFrame();
        }
    };
    private boolean loopOn;
    private boolean frameScheduled;
    private long lastNanos;
    private float timeS;

    public PulseRingView(Context c) { super(c); init(); }
    public PulseRingView(Context c, AttributeSet a) { super(c, a); init(); }
    public PulseRingView(Context c, AttributeSet a, int s) { super(c, a, s); init(); }

    private void init() {
        setLayerType(LAYER_TYPE_HARDWARE, null);
        ringPaint.setStyle(Paint.Style.STROKE);
        idlePaint.setStyle(Paint.Style.STROKE);
        idlePaint.setColor(Color.argb(40, 77, 93, 255));
        idlePaint.setStrokeWidth(2f);
    }

    public void setActive(boolean a) {
        active = a;
        if (a) {
            timeS = 0f;
            startLoop();
        } else {
            stopLoop();
            invalidate();
        }
    }

    public void setRingColor(int color) {
        ringR = Color.red(color);
        ringG = Color.green(color);
        ringB = Color.blue(color);
        if (!active) invalidate();
    }

    private void scheduleFrame() {
        if (loopOn && !frameScheduled && isShown()) {
            frameScheduled = true;
            choreographer.postFrameCallback(frameCb);
        }
    }

    private void startLoop() {
        if (loopOn) return;
        loopOn = true;
        lastNanos = 0;
        scheduleFrame();
    }

    private void stopLoop() {
        loopOn = false;
        frameScheduled = false;
        choreographer.removeFrameCallback(frameCb);
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) { if (active) startLoop(); }
        else stopLoop();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (active) startLoop();
    }

    @Override protected void onDetachedFromWindow() {
        stopLoop();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w == 0) return;
        float cx = w / 2f, cy = h / 2f;
        float base = Math.min(w, h) * 0.36f;
        float max = Math.min(w, h) * 0.5f;
        if (!active) {
            canvas.drawCircle(cx, cy, base, idlePaint);
            return;
        }
        float t = (timeS % PERIOD_S) / PERIOD_S;
        for (int i = 0; i < RINGS; i++) {
            float ph = t + i / (float) RINGS;
            if (ph >= 1f) ph -= 1f;
            // easing: expansión rápida al inicio, desvanecido suave
            float eased = 1f - (1f - ph) * (1f - ph);
            float r = base + eased * (max - base);
            ringPaint.setColor(Color.argb((int) (110 * (1f - ph)), ringR, ringG, ringB));
            ringPaint.setStrokeWidth(3f * (1f - ph) + 1f);
            canvas.drawCircle(cx, cy, r, ringPaint);
        }
    }
}
