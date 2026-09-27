package com.drex.hyperion.blocker;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import com.drex.hyperion.HyperionVpnService;
import com.drex.hyperion.MainActivity;
import com.drex.hyperion.R;
import com.drex.hyperion.ui.Cine;

import java.util.List;
import java.util.Locale;

/**
 * ShieldController — dashboard "Escudo" de Hyperion 2.0 (Track 5).
 *
 * <p>Muestra: contador gigante de peticiones bloqueadas (count-up),
 * anillo de progreso hacia la meta de 1 GB ahorrado, toggles por categoría,
 * "Apps que más consumían", botón "Actualizar listas" y la nota honesta
 * fija. El estado de protección se lee de
 * {@code HyperionVpnService.running}.</p>
 *
 * <p>Textos hardcodeados en Java (contrato del track).</p>
 */
public class ShieldController {
    private final MainActivity activity;
    private final View root;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean visible;

    private TextView status;
    private TextView counter;
    private RingView ring;
    private FrameLayout ringBox;
    private TextView ringPct;
    private TextView saved;
    private TextView projection;
    private TextView listsInfo;
    private Switch tglAds, tglTrackers, tglThreats, tglMiners;
    private TextView nAds, nTrackers, nThreats, nMiners;
    private Button updateBtn;
    private TextView updateStatus;
    private LinearLayout appsBox;
    private TextView appsEmpty;

    private long displayedBlocked = -1;
    private String lastAppsSig = "";

    public ShieldController(MainActivity activity, LayoutInflater inflater, View root) {
        this.activity = activity;
        this.root = root;

        status = root.findViewById(R.id.shield_status);
        counter = root.findViewById(R.id.shield_counter);
        ringBox = root.findViewById(R.id.shield_ring_box);
        ring = new RingView(activity);
        ring.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        ringBox.addView(ring, 0); // detrás del % central
        ringPct = root.findViewById(R.id.shield_ring_pct);
        saved = root.findViewById(R.id.shield_saved);
        projection = root.findViewById(R.id.shield_projection);
        listsInfo = root.findViewById(R.id.shield_lists);
        tglAds = root.findViewById(R.id.shield_tgl_ads);
        tglTrackers = root.findViewById(R.id.shield_tgl_trackers);
        tglThreats = root.findViewById(R.id.shield_tgl_threats);
        tglMiners = root.findViewById(R.id.shield_tgl_miners);
        nAds = root.findViewById(R.id.shield_n_ads);
        nTrackers = root.findViewById(R.id.shield_n_trackers);
        nThreats = root.findViewById(R.id.shield_n_threats);
        nMiners = root.findViewById(R.id.shield_n_miners);
        updateBtn = root.findViewById(R.id.shield_update);
        updateStatus = root.findViewById(R.id.shield_update_status);
        appsBox = root.findViewById(R.id.shield_apps);
        appsEmpty = root.findViewById(R.id.shield_apps_empty);

        wireToggle(tglAds, AdBlocker.CAT_ADS);
        wireToggle(tglTrackers, AdBlocker.CAT_TRACKER);
        wireToggle(tglThreats, AdBlocker.CAT_MALWARE);
        wireToggle(tglMiners, AdBlocker.CAT_MINER);

        updateBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                onUpdateLists();
            }
        });

        // Entrada cinematográfica escalonada (contrato Cine del worker de UI)
        int[] ids = {
                R.id.shield_header, R.id.shield_counter_card, R.id.shield_ring_card,
                R.id.shield_toggles_card, R.id.shield_update_card,
                R.id.shield_apps_card, R.id.shield_honest_card
        };
        for (int i = 0; i < ids.length; i++) {
            Cine.fadeSlideIn(root.findViewById(ids[i]), i * 70L);
        }

        DataSaver.get(activity).ensureRegistered();
        refreshStatic();
        poll.run();
    }

    private void wireToggle(Switch sw, final String category) {
        final AdBlocker ab = AdBlocker.get(activity);
        sw.setChecked(ab.isEnabled(category));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean on) {
                ab.setEnabled(category, on);
                refreshStatic();
            }
        });
    }

    private void onUpdateLists() {
        updateBtn.setEnabled(false);
        updateStatus.setText("Descargando listas…");
        updateStatus.setTextColor(0xFF8A90B8);
        AdBlocker.get(activity).updateFromNetwork(new AdBlocker.UpdateListener() {
            @Override public void onResult(final boolean ok, final String message) {
                handler.post(new Runnable() {
                    @Override public void run() {
                        updateBtn.setEnabled(true);
                        updateStatus.setText(message);
                        updateStatus.setTextColor(ok ? 0xFF3DFF9C : 0xFFFF4D5E);
                        refreshStatic();
                    }
                });
            }
        });
    }

    private void refreshStatic() {
        AdBlocker ab = AdBlocker.get(activity);
        int[] counts = ab.getListCounts();
        listsInfo.setText((counts[0] + counts[1] + counts[2])
                + " dominios · listas " + ab.getDbVersion());
        DataSaver ds = DataSaver.get(activity);
        long[] byCat = ds.getBlockedByCategory();
        nAds.setText(fmtNum(byCat[0]) + " bloqueados");
        nTrackers.setText(fmtNum(byCat[1]) + " bloqueados");
        nThreats.setText(fmtNum(byCat[2]) + " bloqueados");
        nMiners.setText(fmtNum(byCat[3]) + " bloqueados");
    }

    public void setVisible(boolean v) {
        visible = v;
        if (v) {
            DataSaver.get(activity).ensureRegistered();
            refreshStatic();
            refreshApps(true);
        } else {
            DataSaver.get(activity).flush();
        }
    }

    public void destroy() {
        handler.removeCallbacks(poll);
        DataSaver.get(activity).flush();
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            DataSaver ds = DataSaver.get(activity);

            // Estado de protección (lo aplica el modo VPN local)
            if (HyperionVpnService.running.get()) {
                status.setText("Protección activa");
                status.setTextColor(0xFF3DFF9C);
            } else {
                status.setText("Protección inactiva");
                status.setTextColor(0xFF8A90B8);
            }

            // Contador gigante con count-up
            long blocked = ds.getBlockedCount();
            if (displayedBlocked < 0) {
                displayedBlocked = blocked;
                counter.setText(fmtNum(blocked));
            } else if (blocked != displayedBlocked) {
                animateCounter(displayedBlocked, blocked);
                displayedBlocked = blocked;
            }

            // Anillo hacia la meta de 1 GB
            long savedBytes = ds.getSavedBytes();
            float frac = Math.min(1f, savedBytes / (float) DataSaver.GOAL_BYTES);
            ring.setProgressAnimated(frac);
            ringPct.setText(String.format(Locale.US, "%d%%", Math.round(frac * 100)));
            saved.setText(fmtBytes(savedBytes) + " ahorrados de 1 GB · estimado");

            long days = ds.projectDaysToGoal(DataSaver.GOAL_BYTES);
            if (days < 0) {
                projection.setText("Midiendo tu ritmo…");
            } else if (days == 0) {
                projection.setText("¡Meta de 1 GB alcanzada!");
            } else if (days == 1) {
                projection.setText("A este ritmo llegas en 1 día");
            } else {
                projection.setText("A este ritmo llegas en " + days + " días");
            }

            refreshApps(false);
            handler.postDelayed(this, 2000);
        }
    };

    private void animateCounter(long from, long to) {
        ValueAnimator va = ValueAnimator.ofFloat(from, to);
        va.setDuration(1200);
        va.setInterpolator(Cine.EASE);
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                counter.setText(fmtNum(Math.round((Float) a.getAnimatedValue())));
            }
        });
        va.start();
    }

    private void refreshApps(boolean force) {
        DataSaver ds = DataSaver.get(activity);
        List<DataSaver.AppStat> top = ds.getTopApps(5);
        StringBuilder sig = new StringBuilder();
        for (DataSaver.AppStat a : top) {
            sig.append(a.packageName).append(':').append(a.blocked).append(';');
        }
        String s = sig.toString();
        if (!force && s.equals(lastAppsSig)) return;
        lastAppsSig = s;

        appsBox.removeAllViews();
        if (top.isEmpty()) {
            appsEmpty.setVisibility(View.VISIBLE);
            return;
        }
        appsEmpty.setVisibility(View.GONE);
        LayoutInflater inf = LayoutInflater.from(activity);
        for (DataSaver.AppStat a : top) {
            View v = inf.inflate(R.layout.item_blocked_app, appsBox, false);
            TextView name = v.findViewById(R.id.app_name);
            TextView sub = v.findViewById(R.id.app_sub);
            TextView bytes = v.findViewById(R.id.app_bytes);
            name.setText(a.label);
            sub.setText(fmtNum(a.blocked) + " bloqueos · " + a.packageName);
            bytes.setText(fmtBytes(a.bytes));
            appsBox.addView(v);
            Cine.fadeSlideIn(v, 0);
        }
    }

    private static String fmtNum(long n) {
        return String.format(Locale.US, "%,d", n);
    }

    private static String fmtBytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.US, "%.1f KB", b / 1024f);
        if (b < 1024 * 1024 * 1024) return String.format(Locale.US, "%.1f MB", b / 1048576f);
        return String.format(Locale.US, "%.2f GB", b / 1073741824f);
    }

    // ------------------------------------------------------------------
    // Anillo de progreso (vista propia del track; animada con Cine.EASE)
    // ------------------------------------------------------------------

    /**
     * Anillo de progreso dibujado a mano (se instancia desde Java porque
     * las clases anidadas no son referenciables en XML).
     */
    public static class RingView extends View {
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();
        private float progress; // 0..1
        private ValueAnimator anim;

        public RingView(Context ctx) {
            super(ctx);
            init();
        }

        public RingView(Context ctx, AttributeSet attrs) {
            super(ctx, attrs);
            init();
        }

        private void init() {
            bgPaint.setStyle(Paint.Style.STROKE);
            bgPaint.setColor(0x2235E0FF);
            bgPaint.setStrokeCap(Paint.Cap.ROUND);
            fgPaint.setStyle(Paint.Style.STROKE);
            fgPaint.setColor(0xFF35E0FF);
            fgPaint.setStrokeCap(Paint.Cap.ROUND);
        }

        /** Anima el progreso hacia el objetivo con el easing cinematográfico. */
        public void setProgressAnimated(float target) {
            target = Math.max(0f, Math.min(1f, target));
            if (Math.abs(target - progress) < 0.0005f) return;
            if (anim != null) anim.cancel();
            final float from = progress;
            final float to = target;
            anim = ValueAnimator.ofFloat(from, to);
            anim.setDuration(900);
            anim.setInterpolator(Cine.EASE);
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator a) {
                    progress = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            anim.start();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight();
            float stroke = Math.min(w, h) * 0.09f;
            bgPaint.setStrokeWidth(stroke);
            fgPaint.setStrokeWidth(stroke);
            float pad = stroke / 2f + 2f;
            oval.set(pad, pad, w - pad, h - pad);
            canvas.drawArc(oval, 0, 360, false, bgPaint);
            if (progress > 0) {
                canvas.drawArc(oval, -90, 360 * progress, false, fgPaint);
            }
        }
    }
}
