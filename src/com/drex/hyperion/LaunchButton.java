package com.drex.hyperion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;

import com.drex.hyperion.ui.Cine;

import java.util.Random;

/**
 * Botón circular gigante de conexión con secuencia de lanzamiento cinematográfica:
 * compresión → despegue con estela de partículas → estado "órbita" con pulso suave
 * mientras se establece la conexión.
 *
 * 60fps vía Choreographer, cero allocations en onDraw (gradientes, paints y pool
 * de partículas pre-alocados), se pausa si no es visible.
 *
 * API existente intacta: Listener, setListener, isConnected, setConnected, playLaunch.
 */
public class LaunchButton extends View {

    public interface Listener { void onTap(); }

    private static final float COMPRESS_S = 0.18f;
    private static final float LIFTOFF_S = 0.35f;
    private static final float RETURN_S = 0.45f;
    private static final int MAXP = 90;

    private static final int PHASE_IDLE = 0;
    private static final int PHASE_COMPRESS = 1;
    private static final int PHASE_LIFTOFF = 2;
    private static final int PHASE_RETURN = 3;
    private static final int PHASE_ORBIT = 4;

    private static final int[] EXHAUST_COLORS = {
            0xFF35E0FF, 0xFFFFFFFF, 0xFF9AA3FF, 0xFF4D5DFF,
    };

    // ---- paints / gradientes pre-alocados ----
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint partPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint orbitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private LinearGradient bodyGrad;
    private LinearGradient connGrad;
    private RadialGradient glowGrad;

    private final Random random = new Random();
    private float density = 1f;

    // ---- estado ----
    private boolean connected;
    private boolean launching;
    private int phase = PHASE_IDLE;
    private float phaseT;      // segundos dentro de la fase
    private float orbitT;      // reloj de órbita
    private float liftPx;      // elevación del núcleo (px)
    private float coreAlpha = 1f;
    private float launchProg;  // 0..1 progreso total (anillo)
    private float spawnAcc;
    private Listener listener;
    private Runnable pendingDone;

    // pool de partículas de escape/chispas
    private final float[] px = new float[MAXP];
    private final float[] py = new float[MAXP];
    private final float[] pvx = new float[MAXP];
    private final float[] pvy = new float[MAXP];
    private final float[] plife = new float[MAXP];
    private final float[] pmax = new float[MAXP];
    private final float[] psz = new float[MAXP];
    private final int[] pcol = new int[MAXP];
    private int palive;

    private float geomCx, geomCy, geomR;

    // ---- frame loop ----
    private final Choreographer choreographer = Choreographer.getInstance();
    private final Choreographer.FrameCallback frameCb = new Choreographer.FrameCallback() {
        @Override public void doFrame(long tNanos) {
            frameScheduled = false;
            if (!loopOn || !isShown()) { loopOn = false; return; }
            float dt = lastNanos == 0 ? 1f / 60f
                    : Math.min(0.05f, (tNanos - lastNanos) / 1_000_000_000f);
            lastNanos = tNanos;
            advance(dt);
            invalidate();
            if (phase == PHASE_IDLE && palive == 0) loopOn = false;
            if (loopOn) scheduleFrame();
        }
    };
    private boolean loopOn;
    private boolean frameScheduled;
    private long lastNanos;

    public LaunchButton(Context c) { super(c); init(c); }
    public LaunchButton(Context c, AttributeSet a) { super(c, a); init(c); }
    public LaunchButton(Context c, AttributeSet a, int s) { super(c, a, s); init(c); }

    private void init(Context c) {
        setLayerType(LAYER_TYPE_HARDWARE, null);
        setClickable(true);
        density = c.getResources().getDisplayMetrics().density;

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(7f);
        ringPaint.setStrokeCap(Paint.Cap.ROUND);
        bodyPaint.setStyle(Paint.Style.FILL);
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setColor(Color.WHITE);
        iconPaint.setStrokeWidth(9f);
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setStrokeJoin(Paint.Join.ROUND);
        orbitPaint.setStyle(Paint.Style.STROKE);
        orbitPaint.setStrokeWidth(2.5f);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        geomCx = w / 2f;
        geomCy = h / 2f;
        geomR = Math.min(w, h) / 2f - 6f;
        if (geomR > 0) {
            bodyGrad = new LinearGradient(geomCx, geomCy - geomR, geomCx, geomCy + geomR,
                    Color.rgb(47, 51, 184), Color.rgb(53, 224, 255),
                    Shader.TileMode.CLAMP);
            connGrad = new LinearGradient(geomCx, geomCy - geomR, geomCx, geomCy + geomR,
                    Color.rgb(20, 90, 60), Color.rgb(61, 255, 156),
                    Shader.TileMode.CLAMP);
            glowGrad = new RadialGradient(geomCx, geomCy, geomR,
                    Color.argb(255, 180, 245, 255), Color.argb(0, 53, 224, 255),
                    Shader.TileMode.CLAMP);
        }
    }

    // ---------------- API ----------------

    public void setListener(Listener l) { listener = l; }
    public boolean isConnected() { return connected; }

    public void setConnected(boolean c) {
        connected = c;
        launching = false;
        phase = PHASE_IDLE;
        liftPx = 0f;
        coreAlpha = 1f;
        palive = 0;
        setScaleX(1f);
        setScaleY(1f);
        setAlpha(1f);
        setTranslationY(0f);
        stopLoop();
        invalidate();
    }

    /** Secuencia cinematográfica de "lanzamiento" antes de conectar. */
    public void playLaunch(Runnable onDone) {
        if (launching || connected) return;
        launching = true;
        pendingDone = onDone;
        phase = PHASE_COMPRESS;
        phaseT = 0f;
        launchProg = 0f;
        startLoop();
    }

    // ---------------- frame loop ----------------

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

    private void setPhase(int p) {
        phase = p;
        phaseT = 0f;
    }

    private void advance(float dt) {
        phaseT += dt;
        float total = COMPRESS_S + LIFTOFF_S + RETURN_S;

        switch (phase) {
            case PHASE_COMPRESS: {
                float e = Cine.EASE_OUT.getInterpolation(Math.min(1f, phaseT / COMPRESS_S));
                setScaleY(1f - 0.10f * e);
                setScaleX(1f - 0.035f * e);
                launchProg = phaseT / total;
                if (phaseT >= COMPRESS_S) setPhase(PHASE_LIFTOFF);
                break;
            }
            case PHASE_LIFTOFF: {
                float k = Math.min(1f, phaseT / LIFTOFF_S);
                float e = Cine.EASE.getInterpolation(k);
                // la compresión se libera al despegar
                float rel = Cine.EASE_OUT.getInterpolation(Math.min(1f, k / 0.4f));
                setScaleY(0.90f + 0.10f * rel);
                setScaleX(0.965f + 0.035f * rel);
                liftPx = 40f * density * e;
                coreAlpha = 1f - 0.4f * e;
                launchProg = (COMPRESS_S + phaseT) / total;
                // estela de escape
                spawnAcc += dt * 150f;
                while (spawnAcc >= 1f) {
                    spawnAcc -= 1f;
                    spawnExhaust();
                }
                if (phaseT >= LIFTOFF_S) {
                    if (pendingDone != null) {
                        Runnable d = pendingDone;
                        pendingDone = null;
                        d.run();
                    }
                    setPhase(PHASE_RETURN);
                }
                break;
            }
            case PHASE_RETURN: {
                float e = Cine.EASE.getInterpolation(Math.min(1f, phaseT / RETURN_S));
                liftPx = 40f * density * (1f - e);
                coreAlpha = 0.6f + 0.4f * e;
                setScaleY(1f);
                setScaleX(1f);
                launchProg = (COMPRESS_S + LIFTOFF_S + phaseT) / total;
                if (phaseT >= RETURN_S) {
                    setPhase(PHASE_ORBIT);
                    orbitT = 0f;
                    launchProg = 1f;
                }
                break;
            }
            case PHASE_ORBIT: {
                orbitT += dt;
                // flotación suave + pulso
                liftPx = (float) Math.sin(orbitT * Math.PI * 2 / 2.4) * 6f * density;
                coreAlpha = 1f;
                spawnAcc += dt * 14f;
                while (spawnAcc >= 1f) {
                    spawnAcc -= 1f;
                    spawnSpark();
                }
                break;
            }
            default:
                break;
        }
        updateParticles(dt);
    }

    private void spawnExhaust() {
        if (palive >= MAXP) return;
        int i = palive++;
        px[i] = geomCx + (random.nextFloat() - 0.5f) * geomR * 1.1f;
        py[i] = geomCy - liftPx + geomR * 0.45f;
        pvx[i] = (random.nextFloat() - 0.5f) * 280f;
        pvy[i] = 120f + random.nextFloat() * 260f;
        pmax[i] = 0.45f + random.nextFloat() * 0.35f;
        plife[i] = pmax[i];
        psz[i] = 2f + random.nextFloat() * 3.5f;
        pcol[i] = EXHAUST_COLORS[random.nextInt(EXHAUST_COLORS.length)];
    }

    private void spawnSpark() {
        if (palive >= MAXP) return;
        int i = palive++;
        double a = random.nextDouble() * Math.PI * 2;
        px[i] = geomCx + (float) Math.cos(a) * geomR * 0.95f;
        py[i] = geomCy + (float) Math.sin(a) * geomR * 0.95f;
        pvx[i] = (float) Math.cos(a) * 30f;
        pvy[i] = -40f - random.nextFloat() * 40f;
        pmax[i] = 0.9f + random.nextFloat() * 0.5f;
        plife[i] = pmax[i];
        psz[i] = 1.5f + random.nextFloat() * 2f;
        pcol[i] = EXHAUST_COLORS[random.nextInt(EXHAUST_COLORS.length)];
    }

    private void updateParticles(float dt) {
        float drag = (float) Math.pow(0.96, dt * 60f);
        for (int i = palive - 1; i >= 0; i--) {
            px[i] += pvx[i] * dt;
            py[i] += pvy[i] * dt;
            pvx[i] *= drag;
            pvy[i] *= drag;
            pvy[i] += 170f * dt;
            plife[i] -= dt;
            if (plife[i] <= 0) {
                int last = --palive;
                px[i] = px[last]; py[i] = py[last];
                pvx[i] = pvx[last]; pvy[i] = pvy[last];
                plife[i] = plife[last]; pmax[i] = pmax[last];
                psz[i] = psz[last]; pcol[i] = pcol[last];
            }
        }
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) { if (launching) startLoop(); }
        else stopLoop();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (launching) startLoop();
    }

    @Override protected void onDetachedFromWindow() {
        stopLoop();
        super.onDetachedFromWindow();
    }

    // ---------------- interacción ----------------

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (launching) return true;
        int a = e.getAction();
        if (a == MotionEvent.ACTION_DOWN) {
            Cine.pressFeedback(this);
        } else if (a == MotionEvent.ACTION_UP) {
            Cine.releasePress(this);
            if (listener != null) listener.onTap();
            return true;
        } else if (a == MotionEvent.ACTION_CANCEL) {
            Cine.releasePress(this);
        }
        return true;
    }

    // ---------------- dibujo ----------------

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (geomR <= 0) return;
        float cx = geomCx, cy = geomCy, R = geomR;

        // anillo exterior
        ringPaint.setColor(Color.argb(255, 24, 30, 66));
        canvas.drawCircle(cx, cy, R, ringPaint);
        if (launching && phase != PHASE_ORBIT) {
            ringPaint.setColor(Color.argb(255, 53, 224, 255));
            canvas.drawArc(cx - R, cy - R, cx + R, cy + R, -90f,
                    360f * Math.min(1f, launchProg), false, ringPaint);
        } else if (phase == PHASE_ORBIT) {
            // pulso suave de órbita: 2 anillos expansivos tenues
            float t = (orbitT % 2.4f) / 2.4f;
            for (int i = 0; i < 2; i++) {
                float ph = t + i * 0.5f;
                if (ph >= 1f) ph -= 1f;
                orbitPaint.setColor(Color.argb((int) (70 * (1f - ph)), 53, 224, 255));
                canvas.drawCircle(cx, cy, R + ph * 26f * density, orbitPaint);
            }
            ringPaint.setColor(Color.argb(255, 53, 224, 255));
            canvas.drawCircle(cx, cy, R, ringPaint);
        } else if (connected) {
            ringPaint.setColor(Color.argb(255, 61, 255, 156));
            canvas.drawCircle(cx, cy, R, ringPaint);
        }

        // cuerpo con degradado (pre-creado) + resplandor de lanzamiento
        float cyB = cy - liftPx;
        bodyPaint.setAlpha((int) (255 * coreAlpha));
        bodyPaint.setShader(connected ? connGrad : bodyGrad);
        canvas.drawCircle(cx, cyB, R - 12f, bodyPaint);
        bodyPaint.setShader(null);
        if (launching && glowGrad != null) {
            float intensity = phase == PHASE_LIFTOFF
                    ? 0.55f + 0.35f * Math.abs((float) Math.sin(phaseT * 18.0))
                    : 0.18f;
            glowPaint.setShader(glowGrad);
            glowPaint.setAlpha((int) (255 * intensity * coreAlpha));
            canvas.drawCircle(cx, cyB, R - 12f, glowPaint);
            glowPaint.setShader(null);
        }
        bodyPaint.setAlpha(255);

        // icono: rayo / check
        iconPaint.setAlpha((int) (255 * coreAlpha));
        float s = R * 0.42f;
        if (connected) {
            canvas.drawLine(cx - s * 0.7f, cyB, cx - s * 0.1f, cyB + s * 0.6f, iconPaint);
            canvas.drawLine(cx - s * 0.1f, cyB + s * 0.6f, cx + s * 0.8f, cyB - s * 0.6f, iconPaint);
        } else {
            float bx = cx + s * 0.15f;
            canvas.drawLine(bx + s * 0.35f, cyB - s * 0.9f, bx - s * 0.35f, cyB + s * 0.1f, iconPaint);
            canvas.drawLine(bx - s * 0.35f, cyB + s * 0.1f, bx + s * 0.1f, cyB + s * 0.1f, iconPaint);
            canvas.drawLine(bx + s * 0.1f, cyB + s * 0.1f, bx - s * 0.35f, cyB + s * 0.95f, iconPaint);
        }
        iconPaint.setAlpha(255);

        // partículas de escape / chispas
        for (int i = 0; i < palive; i++) {
            float f = plife[i] / pmax[i];
            if (f < 0f) f = 0f;
            partPaint.setColor(pcol[i]);
            partPaint.setAlpha((int) (255 * f));
            canvas.drawCircle(px[i], py[i], psz[i] * f + 0.5f, partPaint);
        }
    }
}
