package com.drex.hyperion;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Estadísticas del túnel VPN (thread-safe). */
public class VpnStats {
    public final AtomicLong queries = new AtomicLong();
    public final AtomicLong blocked = new AtomicLong();
    public final AtomicLong bytesIn = new AtomicLong();
    public final AtomicLong bytesOut = new AtomicLong();
    public final AtomicLong dropped = new AtomicLong();
    public final AtomicInteger cacheHits = new AtomicInteger();

    public void reset() {
        queries.set(0); blocked.set(0);
        bytesIn.set(0); bytesOut.set(0);
        dropped.set(0); cacheHits.set(0);
    }
}
