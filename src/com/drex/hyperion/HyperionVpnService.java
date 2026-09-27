package com.drex.hyperion;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * VPN local de Hyperion: interfaz TUN que captura solo el tráfico DNS,
 * lo filtra contra la Blocklist y lo resuelve vía 1.1.1.1 / 8.8.8.8.
 * El resto del tráfico del teléfono sigue su ruta directa normal.
 *
 * Arquitectura honesta (Hyperion 2.0): NO hay servidor VPN remoto
 * desplegado; el modo "túnel remoto" no existe y ningún país se
 * presenta como conectado. El modo principal es este túnel local.
 */
public class HyperionVpnService extends VpnService {
    private static final int NOTIF_ID = 1001;
    private static final String CHANNEL = "hyperion_vpn";

    /** Intent explícito para detener el servicio (acción "Desconectar" de la notificación). */
    public static final String ACTION_DISCONNECT = "com.drex.hyperion.ACTION_DISCONNECT";

    public static final AtomicBoolean running = new AtomicBoolean(false);
    public static final VpnStats stats = new VpnStats();
    public static volatile long connectedSince = 0;

    // ---- Cuarentena de red (contrato con el worker de antivirus) ----
    private static final String Q_PREFS = "hyperion_q";
    private static final Set<Integer> QUARANTINED =
            Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());
    private static volatile Context appCtx;
    private static final AtomicBoolean qLoaded = new AtomicBoolean(false);

    /**
     * Marca o desmarca un UID de app en cuarentena de red.
     * Mientras esté en cuarentena, DnsEngine responde NXDOMAIN a todas
     * sus consultas DNS (la app queda sin resolución de nombres).
     * Persiste en SharedPreferences "hyperion_q".
     */
    public static void setUidQuarantined(int uid, boolean q) {
        loadQuarantineIfNeeded();
        if (q) QUARANTINED.add(uid);
        else QUARANTINED.remove(uid);
        persistQuarantine();
    }

    /** true si el UID está en cuarentena de red. */
    public static boolean isUidQuarantined(int uid) {
        loadQuarantineIfNeeded();
        return QUARANTINED.contains(uid);
    }

    private static void loadQuarantineIfNeeded() {
        if (qLoaded.get()) return;
        synchronized (HyperionVpnService.class) {
            if (qLoaded.get()) return;
            if (appCtx != null) {
                try {
                    SharedPreferences sp = appCtx.getSharedPreferences(Q_PREFS, Context.MODE_PRIVATE);
                    for (String s : sp.getStringSet("uids", new HashSet<String>())) {
                        try { QUARANTINED.add(Integer.parseInt(s)); } catch (Exception ignored) {}
                    }
                } catch (Exception ignored) {}
            }
            qLoaded.set(true);
        }
    }

    private static void persistQuarantine() {
        if (appCtx == null) return; // sin contexto: solo memoria hasta el próximo arranque
        try {
            Set<String> s = new HashSet<>();
            for (Integer uid : QUARANTINED) s.add(String.valueOf(uid));
            appCtx.getSharedPreferences(Q_PREFS, Context.MODE_PRIVATE)
                    .edit().putStringSet("uids", s).apply();
        } catch (Exception ignored) {}
    }

    private ParcelFileDescriptor tun;
    private ExecutorService pool;
    private volatile boolean loop;
    private DnsEngine dnsEngine;

    // ---- Eventos DNS (contrato con el bloqueador de anuncios / ahorro) ----
    private static volatile com.drex.hyperion.blocker.DnsEventListener dnsListener;

    /**
     * Listeners ADICIONALES (multiplex): el slot legacy de
     * {@link #setDnsEventListener} sigue siendo de un solo consumidor
     * (DataSaver), pero pantallas como el "Registro DNS en vivo" se suscriben
     * aquí sin pisarlo.
     */
    private static final java.util.concurrent.CopyOnWriteArrayList<
            com.drex.hyperion.blocker.DnsEventListener> extraDnsListeners =
            new java.util.concurrent.CopyOnWriteArrayList<
                    com.drex.hyperion.blocker.DnsEventListener>();

    /** Registra el listener de eventos DNS (lo usa DataSaver del escudo). */
    public static void setDnsEventListener(com.drex.hyperion.blocker.DnsEventListener l) {
        dnsListener = l;
    }

    /** Suscribe un listener adicional sin tocar el slot legacy. */
    public static void addDnsEventListener(com.drex.hyperion.blocker.DnsEventListener l) {
        if (l != null) extraDnsListeners.addIfAbsent(l);
    }

    /** Da de baja un listener adicional. */
    public static void removeDnsEventListener(com.drex.hyperion.blocker.DnsEventListener l) {
        if (l != null) extraDnsListeners.remove(l);
    }

    /** Invocado por DnsEngine por cada consulta DNS procesada por el túnel. */
    static void fireDnsEvent(String domain, int uid, boolean blocked, String category) {
        com.drex.hyperion.blocker.DnsEventListener l = dnsListener;
        if (l != null) {
            try { l.onDnsEvent(domain, uid, blocked, category); }
            catch (Exception ignored) {}
        }
        for (com.drex.hyperion.blocker.DnsEventListener e : extraDnsListeners) {
            try { e.onDnsEvent(domain, uid, blocked, category); }
            catch (Exception ignored) {}
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        appCtx = getApplicationContext();
        loadQuarantineIfNeeded();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Desconexión explícita desde la notificación (o la UI): detener limpio.
        if (intent != null && ACTION_DISCONNECT.equals(intent.getAction())) {
            requestShutdown();
            return START_NOT_STICKY;
        }
        // Barrido Hyperion 2.2: el sistema reinicia los servicios START_STICKY con
        // intent null tras matar el proceso (p. ej. al barrer la app de recientes).
        // Ese reinicio reconectaba el VPN solo ("el botón DESCONECTAR no funciona").
        // El túnel solo se establece por petición explícita del usuario.
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running.get()) return START_NOT_STICKY;
        Builder b = new Builder();
        b.setSession("Hyperion Shield");
        b.addAddress("10.8.0.2", 32);
        // Nuestro DNS "virtual": las queries del sistema vienen al TUN por estas rutas
        b.addDnsServer("10.8.0.1");
        b.addRoute("10.8.0.1", 32);
        // Secuestra también DNS hardcodeados en apps (8.8.8.8, 1.1.1.1, etc.)
        // y las IPs de proveedores DoH/DoT conocidos: su tráfico 443/853
        // entra al TUN y DnsEngine lo rechaza con RST inmediato para forzar
        // el fallback al DNS del sistema (nuestro filtro).
        // NOTA: la resolución propia del motor usa sockets protect()ed
        // (fuera del VPN), así que estas rutas NO rompen el upstream.
        for (String ip : new String[]{
                "8.8.8.8", "8.8.4.4", "1.1.1.1", "1.0.0.1",
                "9.9.9.9", "149.112.112.112",
                "208.67.222.222", "208.67.220.220",
                "94.140.14.14", "94.140.15.15",
                "185.228.168.168", "185.228.169.168"}) {
            b.addRoute(ip, 32);
        }
        b.setMtu(1500);
        try {
            tun = b.establish();
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (tun == null) { stopSelf(); return START_NOT_STICKY; }

        startForeground(NOTIF_ID, buildNotification());
        stats.reset();
        connectedSince = System.currentTimeMillis();
        running.set(true);
        loop = true;
        dnsEngine = new DnsEngine(this);
        pool = Executors.newCachedThreadPool();
        new Thread(new Runnable() {
            @Override public void run() { readLoop(); }
        }, "hyperion-tun").start();
        // Barrido Hyperion 2.2: START_NOT_STICKY (antes STICKY). Un servicio STICKY
        // resucita tras muerte del proceso y reconectaba el VPN sin pedirlo
        // (reconexión fantasma). Muerto queda muerto hasta que el usuario toque.
        return START_NOT_STICKY;
    }

    /** Detiene el túnel de forma limpia: cierra el TUN para que el loop de lectura salga. */
    private void requestShutdown() {
        loop = false;
        try { if (tun != null) tun.close(); } catch (Exception ignored) {}
        stopSelf();
    }

    @Override
    public void onRevoke() {
        // El sistema revocó el permiso VPN: apagado limpio, sin reconexión fantasma.
        requestShutdown();
        super.onRevoke();
    }

    private void readLoop() {
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(tun.getFileDescriptor());
            out = new FileOutputStream(tun.getFileDescriptor());
            final FileOutputStream fout = out;
            byte[] buf = new byte[32767];
            while (loop) {
                int len;
                try {
                    len = in.read(buf);
                } catch (Exception e) { break; }
                if (len <= 0) continue;
                final byte[] pkt = Arrays.copyOf(buf, len);
                try {
                    pool.execute(new Runnable() {
                        @Override public void run() {
                            dnsEngine.handlePacket(pkt, fout, stats);
                        }
                    });
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            try { if (out != null) out.close(); } catch (Exception ignored) {}
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        loop = false;
        running.set(false);
        if (pool != null) pool.shutdownNow();
        try { if (tun != null) tun.close(); } catch (Exception ignored) {}
        tun = null;
        // guardar sesión en el historial
        long end = System.currentTimeMillis();
        if (connectedSince > 0 && end - connectedSince > 2000) {
            HistoryStore.addVpnSession(this, connectedSince, end,
                    stats.queries.get(), stats.blocked.get());
        }
        connectedSince = 0;
        stopForeground(true);
        super.onDestroy();
    }

    private Notification buildNotification() {
        NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "Hyperion Shield", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Acción grande y obvia: Desconectar (intent explícito al servicio).
        Intent disc = new Intent(this, HyperionVpnService.class);
        disc.setAction(ACTION_DISCONNECT);
        PendingIntent discPi = PendingIntent.getService(
                this, 1, disc, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Action disconnectAction = new Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Desconectar", discPi).build();

        Notification.Builder nb = new Notification.Builder(this)
                .setContentTitle("Hyperion Shield activo")
                .setContentText("Filtrando DNS y bloqueando rastreadores")
                .setSmallIcon(R.drawable.ic_launcher_hyperion)
                .setContentIntent(pi)
                .addAction(disconnectAction)
                .setOngoing(true);
        if (Build.VERSION.SDK_INT >= 26) nb.setChannelId(CHANNEL);
        return nb.build();
    }
}
