package com.drex.hyperion;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

import java.util.Random;

/**
 * Fondo estrellado: 3 capas con parallax por tiempo, parpadeo y estrellas
 * fugaces programadas por reloj absoluto (no por ticks).
 *
 * 60fps vía Choreographer, cero allocations en onDraw (arrays de estrellas
 * fijos, nebulosa pre-renderizada a bitmap, estelas sin shaders por frame),
 * se pausa si no es visible.
 */
public class StarfieldView extends View {

    private static final int MAX_METEORS = 6;
    private static final int MAX_STARS = 420;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();

    // estrellas: 3 capas de profundidad (arrays fijos)
    private int starCount;
    private final float[] sx0 = new float[MAX_STARS];
    private final float[] sy0 = new float[MAX_STARS];
    private final float[] sr = new float[MAX_STARS];
    private final float[] stw = new float[MAX_STARS];
    private final float[] sph = new float[MAX_STARS];
    private final float[] sal = new float[MAX_STARS];
    private final int[] scol = new int[MAX_STARS];
    private final float[] sspd = new float[MAX_STARS]; // px/s de deriva
    private static final float[] LAYER_SPD = {5f, 12f, 26f};
    private static final int[] STAR_TINTS = {0xFFFFFFFF, 0xFFBFE9FF, 0xFFC9CDFF};

    // meteoros (pool fijo)
    private final float[] mx = new float[MAX_METEORS];
    private final float[] my = new float[MAX_METEORS];
    private final float[] mvx = new float[MAX_METEORS];
    private final float[] mvy = new float[MAX_METEORS];
    private final float[] mlife = new float[MAX_METEORS];
    private final float[] mmax = new float[MAX_METEORS];

    private Bitmap nebula; // pre-renderizada en onSizeChanged
    private double nextMeteorS = -1; // reloj absoluto (segundos)
    private double clockS;

    // ---- frame loop ----
    private final Choreographer choreographer = Choreographer.getInstance();
    private final Choreographer.FrameCallback frameCb = new Choreographer.FrameCallback() {
        @Override public void doFrame(long tNanos) {
            frameScheduled = false;
            if (!loopOn || !isShown()) { loopOn = false; return; }
            float dt = lastNanos == 0 ? 1f / 60f
                    : Math.min(0.05f, (tNanos - lastNanos) / 1_000_000_000f);
            lastNanos = tNanos;
            clockS += dt;
            update(dt);
            invalidate();
            scheduleFrame();
        }
    };
    private boolean loopOn;
    private boolean frameScheduled;
    private long lastNanos;

    public StarfieldView(Context c) { super(c); init(); }
    public StarfieldView(Context c, AttributeSet a) { super(c, a); init(); }
    public StarfieldView(Context c, AttributeSet a, int s) { super(c, a, s); init(); }

    private void init() { setLayerType(LAYER_TYPE_HARDWARE, null); }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w <= 0 || h <= 0) return;

        // estrellas por capas: 50% / 30% / 20%
        starCount = Math.min(MAX_STARS, Math.max(60, (w * h) / 9000));
        int n0 = starCount * 50 / 100;
        int n1 = starCount * 30 / 100;
        for (int i = 0; i < starCount; i++) {
            int layer = i < n0 ? 0 : (i < n0 + n1 ? 1 : 2);
            sx0[i] = random.nextFloat() * w;
            sy0[i] = random.nextFloat() * h;
            sr[i] = (0.6f + random.nextFloat() * 1.8f) * (0.7f + layer * 0.35f);
            stw[i] = 1f + random.nextFloat() * 3f;
            sph[i] = random.nextFloat() * 6.28f;
            sal[i] = 90 + random.nextInt(165);
            scol[i] = STAR_TINTS[random.nextInt(STAR_TINTS.length)];
            sspd[i] = LAYER_SPD[layer] * (0.8f + random.nextFloat() * 0.4f);
        }

        // nebulosa índigo pre-renderizada (una sola vez por tamaño)
        if (nebula != null) { nebula.recycle(); nebula = null; }
        nebula = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas nc = new Canvas(nebula);
        Paint np = new Paint(Paint.ANTI_ALIAS_FLAG);
        float rad = Math.max(w, h) * 0.7f;
        np.setShader(new RadialGradient(w / 2f, h / 3f, rad,
                Color.argb(46, 77, 93, 255), Color.argb(0, 77, 93, 255),
                Shader.TileMode.CLAMP));
        nc.drawRect(0, 0, w, h, np);
        np.setShader(new RadialGradient(w * 0.82f, h * 0.75f, rad * 0.55f,
                Color.argb(30, 53, 224, 255), Color.argb(0, 53, 224, 255),
                Shader.TileMode.CLAMP));
        nc.drawRect(0, 0, w, h, np);

        for (int i = 0; i < MAX_METEORS; i++) mlife[i] = 0f;
        nextMeteorS = clockS + 2.5 + random.nextFloat() * 3f;
    }

    private void update(float dt) {
        int w = getWidth(), h = getHeight();
        // estrella fugaz por TIEMPO (reloj absoluto, no ticks)
        if (nextMeteorS < 0) nextMeteorS = clockS + 2.5;
        if (clockS >= nextMeteorS && w > 0) {
            for (int i = 0; i < MAX_METEORS; i++) {
                if (mlife[i] <= 0f) {
                    mx[i] = random.nextFloat() * w;
                    my[i] = -20f;
                    float speed = 500 + random.nextFloat() * 500;
                    double ang = Math.PI * (0.65 + random.nextFloat() * 0.15);
                    mvx[i] = (float) Math.cos(ang) * speed;
                    mvy[i] = Math.abs((float) Math.sin(ang) * speed);
                    mmax[i] = 0.7f + random.nextFloat() * 0.4f;
                    mlife[i] = mmax[i];
                    break;
                }
            }
            nextMeteorS = clockS + 3.0 + random.nextFloat() * 4.0;
        }
        for (int i = 0; i < MAX_METEORS; i++) {
            if (mlife[i] > 0f) {
                mx[i] += mvx[i] * dt;
                my[i] += mvy[i] * dt;
                mlife[i] -= dt;
                if (mlife[i] <= 0f || my[i] > h + 40) mlife[i] = 0f;
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
        if (visibility == VISIBLE) startLoop();
        else stopLoop();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startLoop();
    }

    @Override protected void onDetachedFromWindow() {
        stopLoop();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;

        if (nebula != null && !nebula.isRecycled()) {
            canvas.drawBitmap(nebula, 0, 0, null);
        }

        // estrellas con parallax por tiempo: deriva distinta por capa + wrap
        float t = (float) clockS;
        for (int i = 0; i < starCount; i++) {
            float sp = sspd[i];
            float x = sx0[i] + t * sp * 0.25f;
            float y = sy0[i] + t * sp;
            x -= ((int) (x / w)) * w;
            y -= ((int) (y / h)) * h;
            float tw = 0.55f + 0.45f * (float) Math.sin(t * stw[i] + sph[i]);
            paint.setColor(scol[i]);
            paint.setAlpha((int) (sal[i] * tw));
            canvas.drawCircle(x, y, sr[i], paint);
        }

        // meteoros: estela en 4 segmentos con degradado de alpha (sin shaders)
        for (int i = 0; i < MAX_METEORS; i++) {
            if (mlife[i] <= 0f) continue;
            float f = mlife[i] / mmax[i];
            float tailLen = 60f * f + 30f;
            float inv = 1f / (float) Math.hypot(mvx[i], mvy[i]);
            float tx = -mvx[i] * inv * tailLen;
            float ty = -mvy[i] * inv * tailLen;
            paint.setStrokeWidth(2.5f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            for (int k = 0; k < 4; k++) {
                float a0 = k / 4f, a1 = (k + 1) / 4f;
                int alpha = (int) (255 * f * (1f - (a0 + a1) / 2f));
                paint.setColor(Color.argb(alpha, 53, 224, 255));
                canvas.drawLine(mx[i] + tx * a0, my[i] + ty * a0,
                        mx[i] + tx * a1, my[i] + ty * a1, paint);
            }
            // cabeza brillante
            paint.setColor(Color.argb((int) (255 * f), 220, 250, 255));
            canvas.drawCircle(mx[i], my[i], 2.5f, paint);
        }
        paint.setAlpha(255);
    }
}
