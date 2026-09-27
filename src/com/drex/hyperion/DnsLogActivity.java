package com.drex.hyperion;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import com.drex.hyperion.blocker.DnsHealth;
import com.drex.hyperion.blocker.DnsLogStore;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Registro DNS en vivo (Hyperion 2.2): muestra en tiempo real cada consulta
 * DNS procesada por el motor del túnel — dominio, permitida/bloqueada y
 * categoría — alimentada por el {@link DnsLogStore} (eventos REALES del
 * DnsEngine, nada simulado).
 *
 * <p>Además detecta el DNS privado de Android en modo estricto (DoT) y guía
 * al usuario a desactivarlo, y recuerda el ajuste de "DNS seguro" de Chrome:
 * los dos bypass que evaden el filtro.</p>
 */
public class DnsLogActivity extends Activity {

    private ListView list;
    private TextView stats;
    private Button pauseBtn;
    private ArrayAdapter<String> adapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private final Map<Integer, String> appCache = new HashMap<Integer, String>();
    private boolean paused = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_dns_log);

        list = findViewById(R.id.dns_log_list);
        stats = findViewById(R.id.dns_log_stats);
        pauseBtn = findViewById(R.id.dns_log_pause);
        Button clearBtn = findViewById(R.id.dns_log_clear);

        adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, new ArrayList<String>());
        list.setAdapter(adapter);

        pauseBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                paused = !paused;
                pauseBtn.setText(paused ? "Reanudar" : "Pausar");
            }
        });
        clearBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                DnsLogStore.get().clear();
                adapter.clear();
                refreshStats();
            }
        });

        // Banner de DNS privado estricto (solo si aplica)
        View banner = findViewById(R.id.dns_log_pdns_banner);
        if (DnsHealth.isPrivateDnsStrict(this)) {
            banner.setVisibility(View.VISIBLE);
            findViewById(R.id.dns_log_pdns_btn).setOnClickListener(
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            try {
                                startActivity(DnsHealth.privateDnsSettingsIntent(
                                        DnsLogActivity.this));
                            } catch (Exception ignored) {}
                        }
                    });
        } else {
            banner.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        DnsLogStore.get().start();   // suscribe el listener adicional
        refreshStats();
        handler.post(refreshTick);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshTick);
        DnsLogStore.get().stop();    // libera la suscripción
        super.onPause();
    }

    private final Runnable refreshTick = new Runnable() {
        @Override public void run() {
            if (!paused) refreshList();
            handler.postDelayed(this, 1000);
        }
    };

    private void refreshStats() {
        DnsLogStore s = DnsLogStore.get();
        stats.setText(s.blockedTotal() + " bloqueadas · "
                + s.allowedTotal() + " permitidas");
    }

    private void refreshList() {
        List<DnsLogStore.Entry> entries = DnsLogStore.get().snapshot();
        adapter.clear();
        for (DnsLogStore.Entry e : entries) {
            String mark = e.blocked ? "✕" : "✓";
            String cat = e.category != null ? " [" + e.category + "]" : "";
            String app = appLabel(e.uid);
            adapter.add(mark + " " + fmt.format(new Date(e.timestampMs)) + " "
                    + e.domain + cat + (app != null ? " · " + app : ""));
        }
        adapter.notifyDataSetChanged();
        if (adapter.getCount() > 0) list.setSelection(adapter.getCount() - 1);
        refreshStats();
    }

    /** Etiqueta de app para el UID (cacheada; null si no se resuelve). */
    private String appLabel(int uid) {
        if (uid < 0) return null;
        String cached = appCache.get(uid);
        if (cached != null) return cached.isEmpty() ? null : cached;
        String label = null;
        try {
            PackageManager pm = getPackageManager();
            String name = pm.getNameForUid(uid);
            if (name != null) {
                int colon = name.indexOf(':');       // sharedUid: "a:b"
                if (colon > 0) name = name.substring(0, colon);
                int comma = name.indexOf(',');
                if (comma > 0) name = name.substring(0, comma);
                label = name;
            }
        } catch (Exception ignored) {}
        appCache.put(uid, label != null ? label : "");
        return label;
    }
}
