package com.drex.hyperion.blocker;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;

/**
 * DnsHealth — diagnóstico del "DNS privado" de Android (DoT, puerto 853).
 *
 * <p>Si el usuario tiene el DNS privado en modo "hostname" (estricto), TODO
 * el DNS del sistema viaja cifrado (DoT) y <b>evade por completo</b> el
 * filtro del túnel local — salvo que la IP del proveedor caiga en nuestras
 * rutas /32, en cuyo caso el DoT se rechaza y el DNS estricto deja de
 * resolver (pantalla de "sin internet"). En ambos casos la guía correcta
 * es pedirle que lo desactive: Hyperion YA filtra el DNS en claro.</p>
 *
 * <p>En modo "opportunistic" (automático) Android cae a DNS en claro cuando
 * el servidor del VPN no habla DoT, así que no hay bypass: no se molesta
 * al usuario.</p>
 */
public final class DnsHealth {
    private DnsHealth() {}

    /** "off" | "opportunistic" | "hostname" (o null si no se pudo leer). */
    public static String privateDnsMode(Context ctx) {
        try {
            return Settings.Global.getString(
                    ctx.getContentResolver(), "private_dns_mode");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * true si el DNS privado está en modo estricto (hostname): el filtro de
     * Hyperion queda evadido o roto y hay que guiar al usuario a Ajustes.
     */
    public static boolean isPrivateDnsStrict(Context ctx) {
        return "hostname".equals(privateDnsMode(ctx));
    }

    /**
     * Intent a la pantalla de DNS privado. El action
     * "android.settings.PRIVATE_DNS_SETTINGS" no es una constante pública del
     * SDK y no resuelve en todos los fabricantes: se intenta y, si no
     * resuelve, se cae a "Red e Internet" (ACTION_WIRELESS_SETTINGS, pública
     * y documentada), donde vive el ajuste en todos los Android 9+.
     */
    public static Intent privateDnsSettingsIntent(Context ctx) {
        Intent i = new Intent("android.settings.PRIVATE_DNS_SETTINGS");
        try {
            PackageManager pm = ctx.getPackageManager();
            if (pm != null && i.resolveActivity(pm) != null) return i;
        } catch (Exception ignored) {}
        return new Intent(Settings.ACTION_WIRELESS_SETTINGS);
    }
}
