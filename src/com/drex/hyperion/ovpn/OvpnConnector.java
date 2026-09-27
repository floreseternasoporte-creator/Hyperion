/*
 * Puerta de entrada de la UI al túnel OpenVPN remoto.
 *
 * Equivale al papel de OpenVpnApi/LaunchVPN en ics-openvpn, adaptado a
 * Hyperion: la UI no necesita activities intermedias; el permiso de VPN
 * (VpnService.prepare) lo gestiona MainActivity antes de llamar a connect().
 *
 * Exclusión mutua: connect() detiene el modo local (HyperionVpnService)
 * y MainActivity.startVpn() (modo local) detiene este modo remoto.
 */
package com.drex.hyperion.ovpn;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Base64;

import com.drex.hyperion.HyperionVpnService;
import com.drex.hyperion.VpnGateServer;

import java.io.File;
import java.io.FileOutputStream;

public final class OvpnConnector {
    private OvpnConnector() {}

    public static boolean isRunning() {
        return OvpnVpnService.running.get();
    }

    /** Detiene el túnel remoto (si está activo). */
    public static void disconnect(Context ctx) {
        Intent i = new Intent(ctx, OvpnVpnService.class)
                .setAction(OvpnVpnService.ACTION_DISCONNECT);
        ctx.startService(i);
    }

    /**
     * Conecta al servidor VPNGate indicado. Detiene primero el modo local.
     * @return null si todo bien, o mensaje de error legible.
     */
    public static String connect(Context ctx, VpnGateServer server) {
        if (server == null) return "Servidor inválido.";
        if (server.ovpnBase64 == null || server.ovpnBase64.isEmpty())
            return "Este servidor no trae configuración OpenVPN.";

        // Exclusión mutua: un solo túnel a la vez.
        try {
            ctx.stopService(new Intent(ctx, HyperionVpnService.class));
        } catch (Exception ignored) {}

        String cfgPath;
        try {
            byte[] ovpn = Base64.decode(server.ovpnBase64, Base64.DEFAULT);
            File dir = new File(ctx.getFilesDir(), "ovpn");
            if (!dir.exists()) dir.mkdirs();
            String safe = server.hostName.replaceAll("[^A-Za-z0-9._-]", "_");
            File f = new File(dir, safe + ".ovpn");
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(ovpn);
            fos.close();
            cfgPath = f.getAbsolutePath();
        } catch (Exception e) {
            return "No se pudo preparar la configuración: " + e.getMessage();
        }

        Intent i = new Intent(ctx, OvpnVpnService.class)
                .setAction(OvpnVpnService.ACTION_CONNECT)
                .putExtra(OvpnVpnService.EXTRA_LABEL, server.displayName())
                .putExtra(OvpnVpnService.EXTRA_COUNTRY_ISO, server.countryShort)
                .putExtra(OvpnVpnService.EXTRA_CONFIG_PATH, cfgPath);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ctx.startForegroundService(i);
            else
                ctx.startService(i);
        } catch (Exception e) {
            return "No se pudo iniciar el servicio VPN: " + e.getMessage();
        }
        return null;
    }
}
