package com.drex.hyperion.ui;

import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;

/**
 * CINE — sistema de movimiento único de Hyperion.
 *
 * <p>Toda animación de la app usa estos easings y helpers: estética cinematográfica
 * estilo SpaceX (aceleraciones suaves, despegues con peso, nada brusco). El rebote
 * elástico (Overshoot) existe solo como acento puntual vía {@link #popAccent}.</p>
 *
 * <p>Paleta: negro espacial #05070F, índigo #4D5DFF / #2F33B8, cian #35E0FF.</p>
 */
public final class Cine {

    private Cine() { /* utilidad estática */ }

    // ------------------------------------------------------------------
    // Easings (contrato — otros módulos dependen de estos campos)
    // ------------------------------------------------------------------

    /** cubic-bezier(0.4, 0, 0.2, 1): easing estándar cinematográfico (Material standard). */
    public static final Interpolator EASE = new PathInterpolator(0.4f, 0f, 0.2f, 1f);

    /** cubic-bezier(0, 0, 0.2, 1): salida desacelerada para entradas y releases. */
    public static final Interpolator EASE_OUT = new PathInterpolator(0f, 0f, 0.2f, 1f);

    /** Acento elástico puntual (NO usar como easing general). */
    private static final Interpolator POP = new OvershootInterpolator(2f);

    // ------------------------------------------------------------------
    // Contrato base
    // ------------------------------------------------------------------

    /**
     * Entrada cinematográfica: alpha 0→1 + translationY 24→0, 450ms, EASE, con delay.
     */
    public static void fadeSlideIn(android.view.View v, long delayMs) {
        if (v == null) return;
        v.animate().cancel();
        v.setAlpha(0f);
        v.setTranslationY(24f);
        v.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(450)
                .setStartDelay(delayMs)
                .setInterpolator(EASE)
                .withEndAction(null)
                .start();
    }

    /**
     * Feedback de presión: escala a 0.96 con EASE_OUT (120ms).
     * Liberar con {@link #releasePress}.
     */
    public static void pressFeedback(android.view.View v) {
        if (v == null) return;
        v.animate().cancel();
        v.animate()
                .scaleX(0.96f)
                .scaleY(0.96f)
                .setDuration(120)
                .setStartDelay(0)
                .setInterpolator(EASE_OUT)
                .withEndAction(null)
                .start();
    }

    /**
     * Secuencia de lanzamiento: comprime (scaleY 0.9, 180ms, EASE_OUT) →
     * despega (translationY -40, alpha→0.6, 350ms, EASE) → ejecuta onPeak →
     * regresa (posición/alpha/escala originales, 450ms, EASE).
     */
    public static void launchSequence(final android.view.View v, final Runnable onPeak) {
        if (v == null) {
            if (onPeak != null) onPeak.run();
            return;
        }
        v.animate().cancel();
        v.setScaleY(1f);
        v.animate()
                .scaleY(0.9f)
                .setDuration(180)
                .setStartDelay(0)
                .setInterpolator(EASE_OUT)
                .withEndAction(new Runnable() {
                    @Override public void run() {
                        v.animate()
                                .translationY(-40f)
                                .alpha(0.6f)
                                .setDuration(350)
                                .setStartDelay(0)
                                .setInterpolator(EASE)
                                .withEndAction(new Runnable() {
                                    @Override public void run() {
                                        if (onPeak != null) onPeak.run();
                                        v.animate()
                                                .translationY(0f)
                                                .alpha(1f)
                                                .scaleY(1f)
                                                .setDuration(450)
                                                .setStartDelay(0)
                                                .setInterpolator(EASE)
                                                .withEndAction(null)
                                                .start();
                                    }
                                })
                                .start();
                    }
                })
                .start();
    }

    // ------------------------------------------------------------------
    // Extras para el coordinador (cableado en MainActivity / pantallas)
    // ------------------------------------------------------------------

    /**
     * Transición cinematográfica entre pestañas: la saliente se desvanece
     * (fade + slide 24px + escala 0.97, 220ms, EASE_OUT) y la entrante aparece
     * (fade + slide 30px + escala 0.97→1, 420ms, EASE). Controla visibility.
     */
    public static void switchTab(final View incoming, final View outgoing) {
        if (incoming == null) return;
        if (outgoing != null && outgoing != incoming
                && outgoing.getVisibility() == View.VISIBLE) {
            outgoing.animate().cancel();
            outgoing.animate()
                    .alpha(0f)
                    .translationY(24f)
                    .scaleX(0.97f)
                    .scaleY(0.97f)
                    .setDuration(220)
                    .setInterpolator(EASE_OUT)
                    .withEndAction(new Runnable() {
                        @Override public void run() {
                            outgoing.setVisibility(View.GONE);
                            outgoing.setAlpha(1f);
                            outgoing.setTranslationY(0f);
                            outgoing.setScaleX(1f);
                            outgoing.setScaleY(1f);
                            enterTab(incoming);
                        }
                    })
                    .start();
        } else {
            if (outgoing != null && outgoing != incoming) {
                outgoing.setVisibility(View.GONE);
            }
            enterTab(incoming);
        }
    }

    private static void enterTab(View v) {
        v.setVisibility(View.VISIBLE);
        v.animate().cancel();
        v.setAlpha(0f);
        v.setTranslationY(30f);
        v.setScaleX(0.97f);
        v.setScaleY(0.97f);
        v.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(420)
                .setInterpolator(EASE)
                .withEndAction(null)
                .start();
    }

    /** Desliza el indicador de pestaña con EASE (380ms). */
    public static void slideIndicator(View indicator, float targetX) {
        if (indicator == null) return;
        indicator.animate().cancel();
        indicator.animate()
                .translationX(targetX)
                .setDuration(380)
                .setInterpolator(EASE)
                .withEndAction(null)
                .start();
    }

    /** Libera la presión: vuelve a escala 1 con EASE (220ms). */
    public static void releasePress(View v) {
        if (v == null) return;
        v.animate().cancel();
        v.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(220)
                .setStartDelay(0)
                .setInterpolator(EASE)
                .withEndAction(null)
                .start();
    }

    /**
     * Acento elástico PUNTUAL (p. ej. icono de pestaña activa): pop 0.8→1
     * con Overshoot, 320ms. No usar como easing general.
     */
    public static void popAccent(View v) {
        if (v == null) return;
        v.animate().cancel();
        v.setScaleX(0.8f);
        v.setScaleY(0.8f);
        v.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(320)
                .setStartDelay(0)
                .setInterpolator(POP)
                .withEndAction(null)
                .start();
    }
}
