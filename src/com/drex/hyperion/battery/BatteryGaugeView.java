package com.drex.hyperion.battery;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import com.drex.hyperion.ui.Cine;

/**
 * Medidor circular de batería estilo cabina Hyperion.
 * Arco de 270° con color según nivel (verde > ámbar > rojo), tick de carga
 * y animación del valor con el easing cinematográfico.
 */
public class BatteryGaugeView extends View {
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private float level = -1; // 0..100, -1 = desconocido
    private boolean charging;
    private ValueAnimator anim;

    public BatteryGaugeView(Context ctx) { super(ctx); init(); }
    public BatteryGaugeView(Context ctx, AttributeSet a) { super(ctx, a); init(); }

    private void init() {
        bgPaint.setStyle(Paint.Style.STROKE);
        bgPaint.setColor(0x2235E0FF);
        bgPaint.setStrokeCap(Paint.Cap.ROUND);
        fgPaint.setStyle(Paint.Style.STROKE);
        fgPaint.setStrokeCap(Paint.Cap.ROUND);
        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setColor(0x4435E0FF);
        tickPaint.setStrokeWidth(3f);
    }

    /** Fija el nivel (0-100) con animación; charging dibuja el rayo. */
    public void setLevelAnimated(float target, boolean isCharging) {
        target = Math.max(0f, Math.min(100f, target));
        charging = isCharging;
        if (level < 0) {
            level = target;
            invalidate();
            return;
        }
        if (Math.abs(target - level) < 0.5f) { invalidate(); return; }
        if (anim != null) anim.cancel();
        final float from = level, to = target;
        anim = ValueAnimator.ofFloat(from, to);
        anim.setDuration(1000);
        anim.setInterpolator(Cine.EASE);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                level = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        anim.start();
    }

    private int colorFor(float lvl) {
        if (lvl > 50) return 0xFF3DFF9C;
        if (lvl > 20) return 0xFFFFB020;
        return 0xFFFF4D5E;
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth(), h = getHeight();
        float stroke = Math.min(w, h) * 0.085f;
        bgPaint.setStrokeWidth(stroke);
        fgPaint.setStrokeWidth(stroke);
        float pad = stroke / 2f + 4f;
        oval.set(pad, pad, w - pad, h - pad);
        float cx = w / 2f, cy = h / 2f, r = (Math.min(w, h) - pad * 2) / 2f;

        // ticks de la esfera
        for (int i = 0; i <= 27; i++) {
            double ang = Math.toRadians(135 + i * 10);
            float x1 = cx + (float) Math.cos(ang) * (r - stroke - 8f);
            float y1 = cy + (float) Math.sin(ang) * (r - stroke - 8f);
            float x2 = cx + (float) Math.cos(ang) * (r - stroke - 2f);
            float y2 = cy + (float) Math.sin(ang) * (r - stroke - 2f);
            c.drawLine(x1, y1, x2, y2, tickPaint);
        }

        // arco base 270°
        c.drawArc(oval, 135, 270, false, bgPaint);
        // arco de nivel
        if (level >= 0) {
            fgPaint.setColor(colorFor(level));
            fgPaint.setShadowLayer(18f, 0f, 0f, colorFor(level));
            c.drawArc(oval, 135, 270 * (level / 100f), false, fgPaint);
            fgPaint.clearShadowLayer();
        }
        // rayo de carga
        if (charging && level >= 0) {
            Paint bolt = new Paint(Paint.ANTI_ALIAS_FLAG);
            bolt.setColor(0xFF35E0FF);
            bolt.setStyle(Paint.Style.FILL);
            float bw = r * 0.28f, bh = r * 0.52f;
            android.graphics.Path p = new android.graphics.Path();
            p.moveTo(cx + bw * 0.25f, cy - bh);
            p.lineTo(cx - bw * 0.55f, cy + bh * 0.12f);
            p.lineTo(cx - bw * 0.02f, cy + bh * 0.12f);
            p.lineTo(cx - bw * 0.25f, cy + bh);
            p.lineTo(cx + bw * 0.55f, cy - bh * 0.12f);
            p.lineTo(cx + bw * 0.02f, cy - bh * 0.12f);
            p.close();
            c.drawPath(p, bolt);
        }
    }
}
