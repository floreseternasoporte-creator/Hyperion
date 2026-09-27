package com.drex.hyperion.blocker;

import com.drex.hyperion.HyperionVpnService;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DnsLogStore — búfer en anillo de consultas DNS REALES para la pantalla
 * "Registro DNS en vivo" (Hyperion 2.2).
 *
 * <p>Se registra como {@link DnsEventListener} <b>adicional</b> (vía
 * {@code HyperionVpnService.addDnsEventListener}) sin tocar el slot legacy
 * de {@code setDnsEventListener} que usa DataSaver. Cada evento que llega
 * aquí ya fue procesado de verdad por {@code DnsEngine}: nada se simula.</p>
 *
 * <p>Los eventos llegan desde los hilos del pool del VPN: todo acceso al
 * búfer está sincronizado. Capacidad acotada (300) para no crecer en memoria.</p>
 */
public final class DnsLogStore implements DnsEventListener {

    public static final int CAPACITY = 300;

    /** Una consulta real procesada por el motor. */
    public static final class Entry {
        public final long timestampMs;
        public final String domain;     // normalizado, sin punto final
        public final boolean blocked;
        public final String category;   // "ads","tracker","malware","phishing","miner","doh"; null si permitida
        public final int uid;           // UID origen (-1 si no se resolvió)

        Entry(long ts, String d, boolean b, String c, int u) {
            timestampMs = ts; domain = d; blocked = b; category = c; uid = u;
        }
    }

    private static final DnsLogStore INSTANCE = new DnsLogStore();
    public static DnsLogStore get() { return INSTANCE; }

    private final ArrayDeque<Entry> buf = new ArrayDeque<Entry>();
    private final AtomicBoolean registered = new AtomicBoolean(false);
    private final AtomicLong nBlocked = new AtomicLong();
    private final AtomicLong nAllowed = new AtomicLong();

    private DnsLogStore() {}

    /** Empieza a capturar (idempotente). Llamar en onResume de la pantalla. */
    public void start() {
        if (registered.compareAndSet(false, true)) {
            HyperionVpnService.addDnsEventListener(this);
        }
    }

    /** Deja de capturar (idempotente). Llamar en onPause de la pantalla. */
    public void stop() {
        if (registered.compareAndSet(true, false)) {
            HyperionVpnService.removeDnsEventListener(this);
        }
    }

    public boolean isCapturing() { return registered.get(); }

    @Override
    public void onDnsEvent(String domain, int uid, boolean blocked, String category) {
        Entry e = new Entry(System.currentTimeMillis(), domain, blocked, category, uid);
        synchronized (buf) {
            if (buf.size() >= CAPACITY) buf.pollFirst();
            buf.addLast(e);
        }
        if (blocked) nBlocked.incrementAndGet(); else nAllowed.incrementAndGet();
    }

    /** Foto del búfer (de la más vieja a la más nueva). */
    public List<Entry> snapshot() {
        synchronized (buf) { return new ArrayList<Entry>(buf); }
    }

    public void clear() {
        synchronized (buf) { buf.clear(); }
    }

    public long blockedTotal() { return nBlocked.get(); }
    public long allowedTotal() { return nAllowed.get(); }
}
