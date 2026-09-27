package com.drex.hyperion;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewPropertyAnimator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.drex.hyperion.ui.Cine;

/**
 * Presentación inicial con slides (solo la primera vez).
 * Parallax cinematográfico que sigue al dedo: cada capa (icono, título,
 * descripción, puntos) se desplaza a distinta velocidad mientras se arrastra.
 */
public class OnboardingActivity extends Activity {
    private static final String PREFS = "hyperion_prefs";

    private final int[] icons = {
            R.drawable.ic_launcher_hyperion,
            R.drawable.ic_tab_vpn,
            R.drawable.ic_tab_speed,
            R.drawable.ic_tab_security,
    };
    private final String[] titles = {
            "Hyperion",
            "Escudo VPN",
            "Velocidad real",
            "Centinela",
    };
    private final String[] descs = {
            "VPN ultrarrápido, acelerador de internet y antivirus en una sola app. Todo con estética de misión espacial.",
            "Túnel local con DNS ultrarrápido (1.1.1.1 · 8.8.8.8) que bloquea rastreadores, anuncios y dominios maliciosos.",
            "Mide tu ping, bajada y subida con un velocímetro de cabina, y optimiza tu conexión con un toque.",
            "Antivirus que audita permisos peligrosos, detecta firmas maliciosas y escanea tus descargas.",
    };

    private int page;
    private float density;
    private ImageView icon;
    private TextView title, desc, btnSkip, btnNext;
    private LinearLayout dots;

    public static boolean isDone(Activity a) {
        return a.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("onboarding_done", false);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_onboarding);
        density = getResources().getDisplayMetrics().density;
        icon = findViewById(R.id.slide_icon);
        title = findViewById(R.id.slide_title);
        desc = findViewById(R.id.slide_desc);
        dots = findViewById(R.id.dots);
        btnSkip = findViewById(R.id.btn_skip);
        btnNext = findViewById(R.id.btn_next);

        btnSkip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finishOnboarding(); }
        });
        btnNext.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (page < icons.length - 1) showPage(page + 1, 1);
                else finishOnboarding();
            }
        });
        // deslizar con el dedo + parallax
        findViewById(android.R.id.content).setOnTouchListener(new SwipeListener());
        showPage(0, 0);
    }

    /**
     * @param p   página a mostrar
     * @param dir dirección de entrada: 1 = siguiente (entra por la derecha),
     *            -1 = anterior (por la izquierda), 0 = inicial (fade+slide vertical)
     */
    private void showPage(int p, int dir) {
        page = p;
        icon.setImageResource(icons[p]);
        title.setText(titles[p]);
        desc.setText(descs[p]);
        btnNext.setText(p == icons.length - 1 ? R.string.onboard_start : R.string.onboard_next);
        btnSkip.setVisibility(p == icons.length - 1 ? View.INVISIBLE : View.VISIBLE);

        // entrada cinematográfica escalonada
        if (dir == 0) {
            Cine.fadeSlideIn(icon, 0);
            Cine.fadeSlideIn(title, 80);
            Cine.fadeSlideIn(desc, 160);
        } else {
            float fromX = (dir > 0 ? 70f : -70f) * density;
            enterCine(icon, 0, fromX, true);
            enterCine(title, 70, fromX * 0.8f, false);
            enterCine(desc, 140, fromX * 0.6f, false);
        }

        dots.removeAllViews();
        for (int i = 0; i < icons.length; i++) {
            View d = new View(this);
            int s = (int) (8 * density);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
            lp.setMargins(s / 2, 0, s / 2, 0);
            d.setLayoutParams(lp);
            d.setBackgroundResource(i == p ? R.drawable.dot_active : R.drawable.dot_idle);
            dots.addView(d);
            if (i == p) Cine.popAccent(d); // acento elástico puntual
        }
    }

    private void enterCine(View v, long delay, float fromX, boolean withScale) {
        v.animate().cancel();
        v.setAlpha(0f);
        v.setTranslationX(fromX);
        v.setTranslationY(0f);
        if (withScale) {
            v.setScaleX(0.92f);
            v.setScaleY(0.92f);
        } else {
            v.setScaleX(1f);
            v.setScaleY(1f);
        }
        ViewPropertyAnimator a = v.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(450)
                .setStartDelay(delay)
                .setInterpolator(Cine.EASE);
        if (withScale) a.scaleX(1f).scaleY(1f);
        a.start();
    }

    /** El contenido vuelve a su sitio con EASE_OUT si el arrastre no cambió de página. */
    private void springBack() {
        View[] vs = {icon, title, desc, dots};
        for (View v : vs) {
            v.animate().cancel();
            v.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(280)
                    .setInterpolator(Cine.EASE_OUT)
                    .start();
        }
    }

    private void finishOnboarding() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        prefs.edit().putBoolean("onboarding_done", true).apply();
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    /** Arrastre con parallax: cada capa sigue al dedo a distinta velocidad. */
    private class SwipeListener implements View.OnTouchListener {
        private float downX;
        private boolean dragging;

        @Override public boolean onTouch(View v, MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    dragging = false;
                    break;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getX() - downX;
                    if (!dragging && Math.abs(dx) > 14f * density) {
                        dragging = true;
                        // el dedo toma el control: cancelar animaciones de entrada
                        icon.animate().cancel();
                        title.animate().cancel();
                        desc.animate().cancel();
                    }
                    if (dragging) {
                        float maxShift = v.getWidth() * 0.35f;
                        float cl = Math.max(-maxShift, Math.min(maxShift, dx));
                        // parallax: el icono vuela más, el texto menos
                        icon.setTranslationX(cl * 0.55f);
                        title.setTranslationX(cl * 0.38f);
                        desc.setTranslationX(cl * 0.22f);
                        dots.setTranslationX(cl * 0.15f);
                        float fade = 1f - Math.min(1f,
                                Math.abs(cl) / (v.getWidth() * 1.2f)) * 0.35f;
                        icon.setAlpha(fade);
                        title.setAlpha(fade);
                        desc.setAlpha(fade);
                    }
                    break;
                }
                case MotionEvent.ACTION_UP: {
                    float dx = e.getX() - downX;
                    if (dragging) {
                        if (dx < -80 && page < icons.length - 1) showPage(page + 1, 1);
                        else if (dx > 80 && page > 0) showPage(page - 1, -1);
                        else springBack();
                    }
                    dragging = false;
                    break;
                }
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) springBack();
                    dragging = false;
                    break;
                default:
                    break;
            }
            return false; // los botones siguen recibiendo sus eventos
        }
    }
}
