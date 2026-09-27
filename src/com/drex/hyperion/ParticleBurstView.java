package com.drex.hyperion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

import java.util.Random;

/**
 * Explosión de partículas (éxito de misión).
 * 60fps vía Choreographer; pool fijo de partículas (cero allocations en onDraw
 * y en el update); se pausa si no es visible.
 */
public class ParticleBurstView extends View {

    private static final int MAX = 240;

    // paleta: cian, índigo, blanco, verde safe
    private static final int[] PALETTE = {
            0xFF35E0FF, 0xFF4D5DFF, 0xFFFFFFFF, 0xFF3DFF9C, 0xFF9AA3FF,
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();

    // pool de partículas (arrays pre-alocados)
    private final float[] px = new float[MAX];
    private final float[] py = new float[MAX];
    private final float[] vx = new float[MAX];
    private final float[] vy = new float[MAX];
    private final float[] life = new float[MAX];
    private final float[] maxLife = new float[MAX];
    private final float[] pr = new float[MAX];
    private final int[] pcol = new int[MAX];
    private int alive;

    // ---- frame loop ----
    private final Choreographer choreographer = Choreographer.getInstance();
    private final Choreographer.FrameCallback frameCb = new Choreographer.FrameCallback() {
        @Override public void doFrame(long tNanos) {
            frameScheduled = false;
            if (!loopOn || !isShown()) { loopOn = false; return; }
            float dt = lastNanos == 0 ? 1f / 60f
                    : Math.min(0.05f, (tNanos - lastNanos) / 1_000_000_000f);
            lastNanos = tNanos;
            update(dt);
            invalidate();
            if (alive > 0) scheduleFrame();
            else loopOn = false;
        }
    };
    private boolean loopOn;
    private boolean frameScheduled;
    private long lastNanos;

    public ParticleBurstView(Context c) { super(c); init(); }
    public ParticleBurstView(Context c, AttributeSet a) { super(c, a); init(); }
    public ParticleBurstView(Context c, AttributeSet a, int s) { super(c, a, s); init(); }

    private void init() { setLayerType(LAYER_TYPE_HARDWARE, null); }

    /** Explosión centrada (API existente). */
    public void burst(int count) {
        burstAt(getWidth() / 2f, getHeight() / 2f, count);
    }

    /** Explosión en un punto concreto (aditivo). */
    public void burstAt(float cx, float cy, int count) {
        if (getWidth() == 0 || count <= 0) return;
        for (int i = 0; i < count; i++) {
            int idx;
            if (alive < MAX) {
                idx = alive++;
            } else {
                // pool lleno: recicla una partícula viva al azar
                idx = random.nextInt(alive);
            }
            double a = random.nextDouble() * Math.PI * 2;
            float sp = 120 + random.nextFloat() * 420;
            px[idx] = cx; py[idx] = cy;
            vx[idx] = (float) Math.cos(a) * sp;
            vy[idx] = (float) Math.sin(a) * sp;
            maxLife[idx] = 0.7f + random.nextFloat() * 0.9f;
            life[idx] = maxLife[idx];
            pr[idx] = 2 + random.nextFloat() * 4;
            pcol[idx] = PALETTE[random.nextInt(PALETTE.length)];
        }
        startLoop();
    }

    private void update(float dt) {
        // fricción independiente del framerate (antes: 0.985 por tick @60fps)
        float drag = (float) Math.pow(0.985, dt * 60f);
        for (int i = alive - 1; i >= 0; i--) {
            px[i] += vx[i] * dt;
            py[i] += vy[i] * dt;
            vx[i] *= drag;
            vy[i] *= drag;
            vy[i] += 260f * dt; // leve gravedad cinematográfica
            life[i] -= dt;
            if (life[i] <= 0) {
                // swap-remove: la última viva ocupa el hueco
                int last = --alive;
                px[i] = px[last]; py[i] = py[last];
                vx[i] = vx[last]; vy[i] = vy[last];
                life[i] = life[last]; maxLife[i] = maxLife[last];
                pr[i] = pr[last]; pcol[i] = pcol[last];
            }
        }
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
        if (visibility != VISIBLE) stopLoop();
        else if (alive > 0) startLoop();
    }

    @Override protected void onDetachedFromWindow() {
        stopLoop();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (int i = 0; i < alive; i++) {
            float f = life[i] / maxLife[i];
            if (f < 0f) f = 0f;
            paint.setColor(pcol[i]);
            paint.setAlpha((int) (255 * f));
            canvas.drawCircle(px[i], py[i], pr[i] * f + 0.5f, paint);
        }
    }
}
