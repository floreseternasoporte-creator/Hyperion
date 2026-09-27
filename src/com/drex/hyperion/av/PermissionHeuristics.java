package com.drex.hyperion.av;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Heurística de combinaciones de permisos peligrosas (motor compartido entre
 * el escáner manual y la protección en tiempo real).
 * Los pesos están calibrados contra familias reales: troyanos bancarios
 * (accesibilidad + overlay), spyware (micrófono/cámara + red), robo de OTP
 * (SMS + red) y droppers (instalar paquetes).
 */
public final class PermissionHeuristics {
    private PermissionHeuristics() { }

    /**
     * Puntúa el PackageInfo y añade las razones (en español) al StringBuilder.
     * Devuelve puntos de riesgo (0-100+). Las apps del sistema descuentan 20.
     */
    public static int score(PackageInfo pi, StringBuilder why) {
        if (pi == null || pi.applicationInfo == null) return 0;
        Set<String> perms = new HashSet<>();
        if (pi.requestedPermissions != null)
            perms.addAll(Arrays.asList(pi.requestedPermissions));
        boolean net = perms.contains("android.permission.INTERNET");
        boolean system = (pi.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;

        int score = 0;
        if (why == null) why = new StringBuilder();
        int base = why.length();

        if (perms.contains("android.permission.BIND_ACCESSIBILITY_SERVICE") && net) {
            score += 45; add(why, "Accesibilidad + Internet (troyanos bancarios)");
        }
        if ((perms.contains("android.permission.RECEIVE_SMS")
                || perms.contains("android.permission.READ_SMS")) && net) {
            score += 40; add(why, "Lee SMS + Internet (robo de códigos/OTP)");
        }
        if (perms.contains("android.permission.SEND_SMS")) {
            score += 25; add(why, "Puede enviar SMS (cargos premium)");
        }
        if (perms.contains("android.permission.RECORD_AUDIO") && net) {
            score += 35; add(why, "Micrófono + Internet (spyware)");
        }
        if (perms.contains("android.permission.CAMERA")
                && perms.contains("android.permission.RECORD_AUDIO") && net) {
            score += 40; add(why, "Cámara + micrófono + Internet (espionaje)");
        }
        if (perms.contains("android.permission.READ_CONTACTS") && net) {
            score += 25; add(why, "Contactos + Internet (exfiltración)");
        }
        if (perms.contains("android.permission.READ_CALL_LOG") && net) {
            score += 25; add(why, "Historial de llamadas + Internet");
        }
        if (perms.contains("android.permission.SYSTEM_ALERT_WINDOW") && net) {
            score += 25; add(why, "Ventanas sobre otras apps + Internet (overlay bancario)");
        }
        if (perms.contains("android.permission.REQUEST_INSTALL_PACKAGES") && net) {
            score += 25; add(why, "Instala otras apps + Internet (dropper)");
        }
        if (perms.contains("android.permission.BIND_DEVICE_ADMIN") && net) {
            score += 40; add(why, "Administrador del dispositivo + Internet");
        }
        if (perms.contains("android.permission.READ_PHONE_STATE") && net) {
            score += 10; add(why, "Estado del teléfono + Internet");
        }

        if (system) score = Math.max(0, score - 20);
        if (why.length() == base && score == 0) {
            // sin hallazgos: no añadir nada
        }
        return score;
    }

    private static void add(StringBuilder why, String reason) {
        if (why.length() > 0) why.append("; ");
        why.append(reason);
    }
}
