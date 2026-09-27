package com.drex.hyperion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

/**
 * Velocímetro estilo cabina: arco de 240°, aguja con física amortiguada
 * (persigue el valor con un muelle críticamente amortiguado, nunca salta)
 * y lectura digital.
 *
 * 60fps vía Choreographer, cero allocations en onDraw (paints, RectF y
 * StringBuilder reutilizado), se pausa si no es visible o la aguja se asentó.
 *
 * API existente intacta: setSpeed(float), reset().
 */
public class SpeedGaugeView extends View {

    private static final int SEGS = 40;

    // ---- paints / geometría pre-alocados ----
    private final Paint bgArcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint segPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needleGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hubPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hubInnerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final StringBuilder textBuf = new StringBuilder(16);

    private float mCx, mCy, mR;

    // ---- física del muelle ----
    private float target;      // valor objetivo (Mbps)
    private float disp;        // valor mostrado
    private float vel;         // velocidad del muelle
    private float maxScale = 100f;
    private float targetMax = 100f;
    private float lastTextVal = -1f;

    // ---- frame loop ----
    private final Choreographer choreographer = Choreographer.getInstance();
    private final Choreographer.FrameCallback frameCb = new Choreographer.FrameCallback() {
        @Override public void doFrame(long tNanos) {
            frameScheduled = false;
            if (!loopOn || !isShown()) { loopOn = false; return; }
            float dt = lastNanos == 0 ? 1f / 60f
                    : Math.min(0.05f, (tNanos - lastNanos) / 1_000_000_000f);
            lastNanos = tNanos;
            boolean settled = advance(dt);
            invalidate();
            if (settled) loopOn = false;
            else scheduleFrame();
        }
    };
    private boolean loopOn;
    private boolean frameScheduled;
    private long lastNanos;

    public SpeedGaugeView(Context c) { super(c); init(); }
    public SpeedGaugeView(Context c, AttributeSet a) { super(c, a); init(); }
    public SpeedGaugeView(Context c, AttributeSet a, int s) { super(c, a, s); init(); }

    private void init() {
        setLayerType(LAYER_TYPE_HARDWARE, null);

        bgArcPaint.setStyle(Paint.Style.STROKE);
        bgArcPaint.setStrokeCap(Paint.Cap.ROUND);
        bgArcPaint.setStrokeWidth(16f);
        bgArcPaint.setColor(Color.argb(255, 20, 26, 58));

        segPaint.setStyle(Paint.Style.STROKE);
        segPaint.setStrokeCap(Paint.Cap.BUTT);
        segPaint.setStrokeWidth(16f);

        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setStrokeWidth(26f);
        glowPaint.setColor(Color.argb(38, 53, 224, 255));

        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setStrokeWidth(2f);
        tickPaint.setColor(Color.argb(120, 138, 144, 184));

        needleGlowPaint.setStyle(Paint.Style.STROKE);
        needleGlowPaint.setStrokeCap(Paint.Cap.ROUND);
        needleGlowPaint.setStrokeWidth(10f);
        needleGlowPaint.setColor(Color.argb(70, 53, 224, 255));

        needlePaint.setStyle(Paint.Style.STROKE);
        needlePaint.setStrokeCap(Paint.Cap.ROUND);
        needlePaint.setStrokeWidth(5f);
        needlePaint.setColor(Color.argb(255, 53, 224, 255));

        hubPaint.setStyle(Paint.Style.FILL);
        hubPaint.setColor(Color.argb(255, 237, 239, 255));
        hubInnerPaint.setStyle(Paint.Style.FILL);
        hubInnerPaint.setColor(Color.argb(255, 11, 15, 34));

        textPaint.setColor(Color.argb(255, 237, 239, 255));
        textPaint.setTextSize(44f);
        textPaint.setTextAlign(Paint.Align.CENTER);
        unitPaint.setColor(Color.argb(255, 138, 144, 184));
        unitPaint.setTextSize(20f);
        unitPaint.setTextAlign(Paint.Align.CENTER);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        mCx = w / 2f;
        mR = Math.min(w, h * 1.55f) / 2f - 14f;
        mCy = h - 22f;
        oval.set(mCx - mR, mCy - mR, mCx + mR, mCy + mR);
    }

    // ---------------- API ----------------

    public void setSpeed(float mbps) {
        float v = Math.max(0f, mbps);
        target = v;
        if (v > targetMax) targetMax = (float) Math.ceil(v / 50f) * 50f;
        startLoop();
    }

    public void reset() {
        target = 0f;
        disp = 0f;
        vel = 0f;
        targetMax = 100f;
        startLoop(); // la escala máxima regresa con easing
    }

    // ---------------- frame loop ----------------

    /**
     * Muelle críticamente amortiguado hacia el objetivo.
     * @return true cuando la aguja se asentó (se puede detener el loop).
     */
    private boolean advance(float dt) {
        float k = 110f;                       // rigidez
        float c = 2f * (float) Math.sqrt(k);  // amortiguamiento crítico
        vel += ((target - disp) * k - vel * c) * dt;
        disp += vel * dt;
        maxScale += (targetMax - maxScale) * Math.min(1f, dt * 4f);
        if (Math.abs(target - disp) < 0.005f && Math.abs(vel) < 0.02f
                && Math.abs(targetMax - maxScale) < 0.05f) {
            disp = target;
            vel = 0f;
            maxScale = targetMax;
            return true;
        }
        return false;
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
        else if (needsFrames()) startLoop();
    }

    private boolean needsFrames() {
        return Math.abs(target - disp) >= 0.005f || Math.abs(vel) >= 0.02f
                || Math.abs(targetMax - maxScale) >= 0.05f;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (needsFrames()) startLoop();
    }

    @Override protected void onDetachedFromWindow() {
        stopLoop();
        super.onDetachedFromWindow();
    }

    // ---------------- dibujo ----------------

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mR <= 0) return;
        float cx = mCx, cy = mCy, R = mR;

        // arco de fondo (150° -> 390° = 240° de barrido)
        canvas.drawArc(oval, 150f, 240f, false, bgArcPaint);

        float frac = disp / maxScale;
        if (frac < 0f) frac = 0f;
        else if (frac > 1f) frac = 1f;

        if (frac > 0.01f) {
            // resplandor bajo el arco de valor
            canvas.drawArc(oval, 150f, 240f * frac, false, glowPaint);
            // arco de valor con degradado índigo->cian por segmentos (sin allocations)
            int n = (int) (SEGS * frac);
            for (int i = 0; i < n; i++) {
                float f0 = i / (float) SEGS;
                int r = (int) (77 + (53 - 77) * f0);
                int g = (int) (93 + (224 - 93) * f0);
                segPaint.setColor(Color.rgb(r, g, 255));
                canvas.drawArc(oval, 150f + 240f * f0, 240f / SEGS + 1f, false, segPaint);
            }
        }

        // ticks
        for (int i = 0; i <= 12; i++) {
            double a = Math.toRadians(150.0 + 240.0 * i / 12.0);
            float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            canvas.drawLine(cx + ca * (R - 22f), cy + sa * (R - 22f),
                    cx + ca * (R - 32f), cy + sa * (R - 32f), tickPaint);
        }

        // aguja con estela según velocidad (física amortiguada)
        double na = Math.toRadians(150.0 + 240.0 * frac);
        float nca = (float) Math.cos(na), nsa = (float) Math.sin(na);
        float tip = R - 40f;
        float nx = cx + nca * tip, ny = cy + nsa * tip;
        float speedK = Math.min(1f, Math.abs(vel) / 60f);
        if (speedK > 0.02f) {
            // estela de movimiento detrás de la aguja
            needleGlowPaint.setAlpha((int) (70 * speedK));
            float bx = cx - nca * tip * 0.45f, by = cy - nsa * tip * 0.45f;
            canvas.drawLine(bx, by, nx, ny, needleGlowPaint);
        }
        canvas.drawLine(cx, cy, nx, ny, needlePaint);
        canvas.drawCircle(nx, ny, 4f, needlePaint);

        // buje
        canvas.drawCircle(cx, cy, 9f, hubPaint);
        canvas.drawCircle(cx, cy, 4f, hubInnerPaint);

        // lectura digital (StringBuilder reutilizado: cero allocations)
        if (Math.abs(disp - lastTextVal) >= 0.05f) {
            lastTextVal = disp;
            textBuf.setLength(0);
            if (disp < 10f) {
                int whole = (int) disp;
                int dec = (int) ((disp - whole) * 10f);
                textBuf.append(whole).append('.').append(dec);
            } else {
                textBuf.append((int) (disp + 0.5f));
            }
        }
        float ty = cy - R * 0.28f;
        canvas.drawText(textBuf, 0, textBuf.length(), cx, ty, textPaint);
        canvas.drawText("Mbps", cx, ty + 30f, unitPaint);
    }
}
