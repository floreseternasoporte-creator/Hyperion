package com.drex.hyperion;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Lista local de dominios de rastreadores, anuncios y malware (coincidencia por sufijo). */
public final class Blocklist {
    private Blocklist() {}

    /**
     * Dominios bootstrap de DNS cifrado (DoH/DoT). Fuente de verdad en código:
     * {@code blocker.PacketUtil#DOH_DOMAINS} (el test verifica que coincide
     * con {@code res/raw/doh.txt}). Se usan cuando AdBlocker no está
     * disponible (fallback sin Android).
     */
    public static final String[] DOH_DOMAINS =
            com.drex.hyperion.blocker.PacketUtil.DOH_DOMAINS;

    private static final Set<String> DOH_SET = new HashSet<>(Arrays.asList(DOH_DOMAINS));

    private static final String[] DOMAINS = {
        // rastreadores / anuncios
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "google-analytics.com", "googletagmanager.com", "googletagservices.com",
        "adservice.google.com", "ads.yahoo.com", "advertising.com",
        "adsystem.com", "amazon-adsystem.com", "aax.amazon-adsystem.com",
        "facebook.net", "fbcdn.net", "analytics.facebook.com",
        "ads-twitter.com", "static.ads-twitter.com", "analytics.twitter.com",
        "ads.linkedin.com", "snap.licdn.com", "ads.pinterest.com",
        "bat.bing.com", "c.bing.com", "ads.microsoft.com",
        "doubleverify.com", "moatads.com", "moat.com", "criteo.com",
        "criteo.net", "rubiconproject.com", "pubmatic.com", "openx.net",
        "openx.com", "smartadserver.com", "adnxs.com", "adsrvr.org",
        "mathtag.com", "media.net", "taboola.com", "outbrain.com",
        "revcontent.com", "mgid.com", "adblade.com", "bidvertiser.com",
        "popads.net", "popcash.net", "adcash.com", "propellerads.com",
        "exoclick.com", "trafficjunky.net", "juicyads.com",
        "hotjar.com", "fullstory.com", "crazyegg.com", "mixpanel.com",
        "segment.io", "amplitude.com", "branch.io", "appsflyer.com",
        "adjust.com", "kochava.com", "singular.net", "tenjin.com",
        "app-measurement.com", "crashlytics.com", "appboy.com",
        "braze.com", "onesignal.com", "pushwoosh.com", "airship.com",
        "mparticle.com", "tealiumiq.com", "ensighten.com",
        // minería / maliciosos conocidos
        "coinhive.com", "coin-hive.com", "cryptoloot.pro", "minero.cc",
        "2mdn.net", "adform.net", "adform.com",
        // dominios de malware / phishing frecuentes (muestra)
        "malwaredomainlist.com", "vxvault.net", "malc0de.com",
        // ---- Telemetría de SO y SDKs (endpoints públicos de analítica, NO esenciales) ----
        // Documentación honesta: son endpoints de telemetría/analítica conocidos
        // públicamente. NO se incluyen endpoints críticos para el funcionamiento
        // del teléfono (FCM, Play Store, APIs de apps): bloquearlos rompería cosas.
        // Microsoft Windows / Office
        "vortex.data.microsoft.com", "vortex-win.data.microsoft.com",
        "settings.data.microsoft.com", "telemetry.microsoft.com",
        "self.events.data.microsoft.com", "watson.telemetry.microsoft.com",
        "telecommand.telemetry.microsoft.com",
        // Amazon (métricas de dispositivo)
        "device-metrics-us.amazon.com", "device-metrics-us-2.amazon.com",
        // Xiaomi / MIUI (telemetría del sistema)
        "data.mistat.xiaomi.com", "tracking.miui.com", "api.ad.xiaomi.com",
        // TikTok (logging de analítica)
        "log.tiktokv.com", "mon.tiktokv.com",
        // Samsung (ads/telemetría)
        "samsungads.com", "config.samsungads.com",
        // Otros SDKs de telemetría móvil conocidos
        "graph-analytics.facebook.com", "telemetry.revenuecat.com",
        "ingest.sentry.io", "app.bugsnag.com", "notify.bugsnag.com",
    };

    private static final Set<String> SET = new HashSet<>(Arrays.asList(DOMAINS));

    /** true si el dominio (o su padre) está en la lista. */
    public static boolean isBlocked(String domain) {
        if (domain == null) return false;
        String d = domain.toLowerCase(Locale.US);
        if (d.endsWith(".")) d = d.substring(0, d.length() - 1);
        while (true) {
            if (SET.contains(d)) return true;
            int dot = d.indexOf('.');
            if (dot < 0) return false;
            d = d.substring(dot + 1);
        }
    }

    public static int size() { return DOMAINS.length; }

    /** true si el dominio (o su padre) es un bootstrap DoH/DoT conocido. */
    public static boolean isDohHost(String domain) {
        if (domain == null) return false;
        String d = domain.toLowerCase(Locale.US);
        if (d.endsWith(".")) d = d.substring(0, d.length() - 1);
        while (true) {
            if (DOH_SET.contains(d)) return true;
            int dot = d.indexOf('.');
            if (dot < 0) return false;
            d = d.substring(dot + 1);
        }
    }

    public static int dohSize() { return DOH_DOMAINS.length; }
}
