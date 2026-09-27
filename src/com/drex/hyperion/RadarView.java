package com.drex.hyperion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

import java.util.Random;

/**
 * Radar de escaneo del antivirus: barrido con estela luminosa (SweepGradient),
 * retícula con marcas de grado, anillos de pulso desde el centro, partículas
 * orbitales y pings de amenaza con anillo de fijación.
 *
 * 60fps vía Choreographer, cero allocations en onDraw (paints, gradiente y
 * arrays pre-alocados), se pausa si no es visible o no hay nada que animar.
 *
 * API existente intacta: setScanning(boolean), ping(float, int).
 */
public class RadarView extends View {

    private static final int MAX_BLIPS = 40;
    private static final float BLIP_LIFE_S = 5f;
    private static final int ORBS = 22;
    private static final float PULSE_PERIOD_S = 1.8f;

    // ---- paints pre-alocados (cero allocations en onDraw) ----
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sweepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sweepLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pulsePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint orbGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint orbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blipGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Random random = new Random();
    private final android.graphics.RectF lockRect = new android.graphics.RectF();

    // ---- geometría (onSizeChanged) ----
    private float cx, cy, R;
    private SweepGradient sweepGrad; // creado una vez por tamaño
    private final Matrix sweepMatrix = new Matrix();

    // ---- estado ----
    private boolean scanning;
    private float sweepDeg;
    private float sweepSpeedDegS = 150f;
    private float pulseT;
    private float clockS; // reloj acumulado de animación (segundos)

    // partículas orbitales (arrays fijos)
    private final float[] orbR = new float[ORBS];
    private final float[] orbA0 = new float[ORBS];
    private final float[] orbSpd = new float[ORBS];
    private final float[] orbSz = new float[ORBS];
    private final float[] orbAl = new float[ORBS];

    // blips de amenaza (pool con cursor circular)
    private final float[] blipAng = new float[MAX_BLIPS];
    private final float[] blipDist = new float[MAX_BLIPS];
    private final float[] blipBorn = new float[MAX_BLIPS];
    private final int[] blipRc = new int[MAX_BLIPS];
    private final int[] blipGc = new int[MAX_BLIPS];
    private final int[] blipBc = new int[MAX_BLIPS];
    private int blipCursor;

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
            if (scanning) {
                sweepDeg += dt * sweepSpeedDegS;
                if (sweepDeg >= 360f) sweepDeg -= 360f;
                pulseT += dt;
            }
            invalidate();
            if (scanning || liveBlips() > 0) scheduleFrame();
            else loopOn = false;
        }
    };
    private boolean loopOn;
    private boolean frameScheduled;
    private long lastNanos;

    public RadarView(Context c) { super(c); init(); }
    public RadarView(Context c, AttributeSet a) { super(c, a); init(); }
    public RadarView(Context c, AttributeSet a, int s) { super(c, a, s); init(); }

    private void init() {
        setLayerType(LAYER_TYPE_HARDWARE, null);

        ringPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setStrokeWidth(2f);
        crossPaint.setStyle(Paint.Style.STROKE);
        crossPaint.setStrokeWidth(1.5f);
        crossPaint.setColor(Color.argb(35, 77, 93, 255));
        sweepLinePaint.setStyle(Paint.Style.STROKE);
        sweepLinePaint.setStrokeWidth(2.5f);
        sweepLinePaint.setColor(Color.argb(230, 53, 224, 255));
        sweepLinePaint.setStrokeCap(Paint.Cap.ROUND);
        tipPaint.setStyle(Paint.Style.FILL);
        tipPaint.setColor(Color.argb(255, 160, 245, 255));
        pulsePaint.setStyle(Paint.Style.STROKE);
        pulsePaint.setStrokeWidth(2f);
        orbGlowPaint.setStyle(Paint.Style.FILL);
        orbPaint.setStyle(Paint.Style.FILL);
        orbPaint.setColor(Color.argb(255, 53, 224, 255));
        blipGlowPaint.setStyle(Paint.Style.FILL);
        blipPaint.setStyle(Paint.Style.FILL);
        lockPaint.setStyle(Paint.Style.STROKE);
        lockPaint.setStrokeWidth(2.5f);
        lockPaint.setStrokeCap(Paint.Cap.ROUND);
        wavePaint.setStyle(Paint.Style.STROKE);
        wavePaint.setStrokeWidth(2f);

        // partículas orbitales: radios, fases y velocidades fijos
        for (int i = 0; i < ORBS; i++) {
            orbR[i] = 0.25f + random.nextFloat() * 0.7f;
            orbA0[i] = random.nextFloat() * (float) (Math.PI * 2);
            float dir = random.nextBoolean() ? 1f : -1f;
            orbSpd[i] = dir * (0.25f + random.nextFloat() * 0.9f);
            orbSz[i] = 1.5f + random.nextFloat() * 2.5f;
            orbAl[i] = 90 + random.nextInt(120);
        }
        for (int i = 0; i < MAX_BLIPS; i++) blipBorn[i] = -1000f;
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        cx = w / 2f;
        cy = h / 2f;
        R = Math.min(w, h) / 2f - 8f;
        if (R > 0) {
            // estela luminosa: brillo máximo en el borde del barrido, se apaga ~108° atrás
            sweepGrad = new SweepGradient(cx, cy,
                    new int[]{Color.argb(220, 53, 224, 255),
                            Color.argb(70, 53, 224, 255),
                            Color.argb(0, 53, 224, 255)},
                    new float[]{0f, 0.14f, 0.30f});
        }
    }

    // ---------------- API ----------------

    public void setScanning(boolean s) {
        scanning = s;
        if (s) {
            for (int i = 0; i < MAX_BLIPS; i++) blipBorn[i] = -1000f;
            blipCursor = 0;
            pulseT = 0f;
            startLoop();
        } else {
            invalidate(); // el loop sigue hasta que mueran los blips
        }
    }

    /** Marca un hallazgo en el radar (color según riesgo). API existente. */
    public void ping(float dist01, int color) {
        int i = blipCursor;
        blipCursor = (blipCursor + 1) % MAX_BLIPS;
        blipAng[i] = random.nextFloat() * 360f;
        blipDist[i] = 0.15f + dist01 * 0.7f;
        blipBorn[i] = clockS;
        blipRc[i] = Color.red(color);
        blipGc[i] = Color.green(color);
        blipBc[i] = Color.blue(color);
        startLoop();
    }

    /** Limpia los hallazgos (aditivo). */
    public void clearBlips() {
        for (int i = 0; i < MAX_BLIPS; i++) blipBorn[i] = -1000f;
        if (!scanning) { stopLoop(); invalidate(); }
    }

    /** Velocidad del barrido en revoluciones por segundo (aditivo). */
    public void setSweepSpeed(float revsPerSecond) {
        sweepSpeedDegS = revsPerSecond * 360f;
    }

    // ---------------- frame loop ----------------

    private int liveBlips() {
        int n = 0;
        for (int i = 0; i < MAX_BLIPS; i++) {
            float age = (clockS - blipBorn[i]) / BLIP_LIFE_S;
            if (age >= 0f && age < 1f) n++;
        }
        return n;
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
        if (visibility == VISIBLE) {
            if (scanning || liveBlips() > 0) startLoop();
        } else stopLoop();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (scanning) startLoop();
    }

    @Override protected void onDetachedFromWindow() {
        stopLoop();
        super.onDetachedFromWindow();
    }

    // ---------------- dibujo ----------------

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (R <= 0) return;

        // anillos concéntricos
        ringPaint.setStrokeWidth(1.5f);
        for (int i = 1; i <= 4; i++) {
            ringPaint.setColor(Color.argb(50, 77, 93, 255));
            canvas.drawCircle(cx, cy, R * i / 4f, ringPaint);
        }
        ringPaint.setStrokeWidth(2f);
        ringPaint.setColor(Color.argb(90, 77, 93, 255));
        canvas.drawCircle(cx, cy, R, ringPaint);

        // cruz
        canvas.drawLine(cx - R, cy, cx + R, cy, crossPaint);
        canvas.drawLine(cx, cy - R, cx, cy + R, crossPaint);

        // retícula con marcas de grado (cada 5°, mayor cada 45°)
        for (int i = 0; i < 72; i++) {
            double rad = Math.toRadians(i * 5.0);
            float cos = (float) Math.cos(rad), sin = (float) Math.sin(rad);
            boolean major = (i % 9 == 0);
            float r0 = major ? R - 14f : R - 7f;
            tickPaint.setColor(Color.argb(major ? 150 : 70, 77, 93, 255));
            tickPaint.setStrokeWidth(major ? 2.5f : 1.5f);
            canvas.drawLine(cx + cos * r0, cy + sin * r0,
                    cx + cos * R, cy + sin * R, tickPaint);
        }

        if (scanning) {
            // partículas orbitales
            for (int i = 0; i < ORBS; i++) {
                float a = orbA0[i] + clockS * orbSpd[i];
                float ox = cx + (float) Math.cos(a) * R * orbR[i];
                float oy = cy + (float) Math.sin(a) * R * orbR[i];
                orbGlowPaint.setColor(Color.argb((int) (orbAl[i] * 0.35f), 53, 224, 255));
                canvas.drawCircle(ox, oy, orbSz[i] * 2.4f, orbGlowPaint);
                orbPaint.setAlpha((int) orbAl[i]);
                canvas.drawCircle(ox, oy, orbSz[i], orbPaint);
            }

            // estela luminosa del barrido (gradiente rotado con el ángulo)
            if (sweepGrad != null) {
                sweepMatrix.setRotate(sweepDeg, cx, cy);
                sweepGrad.setLocalMatrix(sweepMatrix);
                sweepPaint.setShader(sweepGrad);
                canvas.drawCircle(cx, cy, R, sweepPaint);
                sweepPaint.setShader(null);
            }

            // línea del barrido + punta brillante
            double srad = Math.toRadians(sweepDeg);
            float ex = cx + (float) Math.cos(srad) * R;
            float ey = cy + (float) Math.sin(srad) * R;
            canvas.drawLine(cx, cy, ex, ey, sweepLinePaint);
            canvas.drawCircle(ex, ey, 5f, tipPaint);
            tipPaint.setColor(Color.argb(90, 53, 224, 255));
            canvas.drawCircle(ex, ey, 11f, tipPaint);
            tipPaint.setColor(Color.argb(255, 160, 245, 255));

            // anillos de pulso desde el centro
            for (int k = 0; k < 2; k++) {
                float ph = ((pulseT + k * (PULSE_PERIOD_S / 2f)) % PULSE_PERIOD_S) / PULSE_PERIOD_S;
                float eased = 1f - (1f - ph) * (1f - ph);
                pulsePaint.setColor(Color.argb((int) (90 * (1f - ph)), 53, 224, 255));
                pulsePaint.setStrokeWidth(2f * (1f - ph) + 1f);
                canvas.drawCircle(cx, cy, 8f + eased * R * 0.5f, pulsePaint);
            }
        }

        // blips de amenaza con anillo de fijación
        for (int i = 0; i < MAX_BLIPS; i++) {
            float age = (clockS - blipBorn[i]) / BLIP_LIFE_S;
            if (age < 0f || age >= 1f) continue;
            double rad = Math.toRadians(blipAng[i]);
            float rr = R * blipDist[i];
            float bx = cx + (float) Math.cos(rad) * rr;
            float by = cy + (float) Math.sin(rad) * rr;
            int rC = blipRc[i], gC = blipGc[i], bC = blipBc[i];
            float fade = 1f - age;

            // halo
            blipGlowPaint.setColor(Color.argb((int) (90 * fade * fade), rC, gC, bC));
            canvas.drawCircle(bx, by, 4f + 14f * fade, blipGlowPaint);
            // núcleo
            blipPaint.setColor(Color.argb((int) (255 * (1f - age * age)), rC, gC, bC));
            canvas.drawCircle(bx, by, 4f, blipPaint);

            // onda expansiva del ping
            wavePaint.setColor(Color.argb((int) (120 * fade), rC, gC, bC));
            canvas.drawCircle(bx, by, 6f + age * 30f, wavePaint);

            // anillo de fijación: 4 esquinas rotando alrededor del blip
            float rot = (float) Math.toRadians(clockS * 70.0 + i * 37.0);
            lockPaint.setColor(Color.argb((int) (230 * fade), rC, gC, bC));
            float lr = 13f;
            lockRect.set(bx - lr, by - lr, bx + lr, by + lr);
            for (int k = 0; k < 4; k++) {
                float start = (float) Math.toDegrees(rot) + k * 90f - 25f;
                canvas.drawArc(lockRect, start, 50f, false, lockPaint);
            }
        }
    }
}
