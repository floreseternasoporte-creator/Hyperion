package com.drex.hyperion.av;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

import java.io.File;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * Cuarentena real en dos capas:
 *
 * (a) RED: bloquea el tráfico de la app a través del VPN de Hyperion.
 *     CONTRATO con el worker de VPN: HyperionVpnService debe exponer el
 *     método estático
 *         public static void setUidQuarantined(int uid, boolean quarantined)
 *     que añade/quita el uid del set de uids bloqueados que el túnel
 *     consulta por paquete saliente.
 *     Mientras ese método no exista, se usa reflexión con try/catch como
 *     puente temporal: la app compila y funciona igual, y el estado de
 *     cuarentena se persiste en SharedPreferences para que el worker de VPN
 *     pueda leerlo cuando implemente el método.
 *
 * (b) DESINSTALACIÓN: hoja de confirmación honesta que explica qué va a
 *     pasar, intent ACTION_DELETE (el correcto para desinstalar), botón
 *     alternativo "Abrir ajustes de la app" (ACTION_APPLICATION_DETAILS_SETTINGS)
 *     como respaldo, y trato honesto para apps del sistema (no se pueden
 *     desinstalar: solo cuarentena de red).
 */
public final class Quarantine {
    private Quarantine() { }

    private static final String PREFS = "hyperion_quarantine";
    private static final String KEY_UIDS = "quarantined_pkgs";

    // ------------------------------------------------------------------ red

    /**
     * Activa/desactiva la cuarentena de red de un paquete.
     * @return true si el puente con el VPN aceptó el cambio; false si el
     *         método aún no existe (se guarda el estado para cuando exista).
     */
    public static boolean setNetworkQuarantined(Context ctx, String pkg, boolean on) {
        int uid = uidOf(ctx, pkg);
        boolean bridged = callVpnBridge(uid, on);
        // persistir siempre: el worker de VPN puede leerlo al implementar el puente
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> set = new HashSet<>(p.getStringSet(KEY_UIDS, new HashSet<String>()));
        if (on) set.add(pkg); else set.remove(pkg);
        p.edit().putStringSet(KEY_UIDS, set).apply();
        return bridged;
    }

    public static boolean isNetworkQuarantined(Context ctx, String pkg) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY_UIDS, new HashSet<String>()).contains(pkg);
    }

    /** Paquetes en cuarentena (para que el worker de VPN los lea). */
    public static Set<String> quarantinedPackages(Context ctx) {
        return new HashSet<>(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY_UIDS, new HashSet<String>()));
    }

    /**
     * Llamada directa a HyperionVpnService.setUidQuarantined(int, boolean).
     * El worker de VPN implementó el método según el contrato, así que el
     * puente por reflexión ya no es necesario.
     */
    private static boolean callVpnBridge(int uid, boolean on) {
        if (uid < 0) return false;
        try {
            com.drex.hyperion.HyperionVpnService.setUidQuarantined(uid, on);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static int uidOf(Context ctx, String pkg) {
        try {
            return ctx.getPackageManager().getApplicationInfo(pkg, 0).uid;
        } catch (Exception e) {
            return -1;
        }
    }

    // ------------------------------------------------------- desinstalación

    /** ¿Es app del sistema? Esas no se pueden desinstalar. */
    public static boolean isSystemApp(Context ctx, String pkg) {
        try {
            ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(pkg, 0);
            return (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Hoja de confirmación honesta antes de desinstalar.
     * Explica qué pasará, usa ACTION_DELETE y ofrece "Abrir ajustes de la app"
     * como respaldo. En apps del sistema explica que no se puede desinstalar
     * y ofrece solo la cuarentena de red.
     */
    public static void confirmAndUninstall(final Activity activity,
                                          final String pkg, final String label) {
        final boolean system = isSystemApp(activity, pkg);
        final boolean quarantined = isNetworkQuarantined(activity, pkg);

        AlertDialog.Builder b = new AlertDialog.Builder(activity);
        if (system) {
            b.setTitle("App del sistema");
            b.setMessage("«" + label + "» es una app del sistema: Android no permite "
                    + "desinstalarla. Lo honesto que sí puedes hacer es ponerla en "
                    + "cuarentena de red para que no pueda enviar ni recibir datos.");
            b.setPositiveButton(quarantined ? "Quitar cuarentena" : "Cuarentena de red",
                    new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            boolean on = !quarantined;
                            boolean bridged = setNetworkQuarantined(activity, pkg, on);
                            Toast.makeText(activity,
                                    on ? (bridged ? "App en cuarentena de red: sin acceso a internet"
                                            : "Cuarentena guardada (el módulo VPN la aplicará)")
                                            : "Cuarentena de red retirada",
                                    Toast.LENGTH_LONG).show();
                        }
                    });
            b.setNegativeButton("Cerrar", null);
        } else {
            b.setTitle("Desinstalar «" + label + "»");
            b.setMessage("Se abrirá el desinstalador de Android. Allí verás una "
                    + "confirmación del sistema: al aceptar, la app y sus datos se "
                    + "eliminarán por completo del teléfono. Hyperion no puede "
                    + "desinstalarla en silencio: Android siempre pide tu confirmación.");
            b.setPositiveButton("Desinstalar",
                    new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            // Intent correcto de desinstalación
                            Intent i = new Intent(Intent.ACTION_DELETE,
                                    Uri.parse("package:" + pkg));
                            try {
                                activity.startActivity(i);
                            } catch (Exception e) {
                                openAppSettings(activity, pkg);
                            }
                        }
                    });
            b.setNeutralButton("Abrir ajustes de la app",
                    new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            openAppSettings(activity, pkg);
                        }
                    });
            b.setNegativeButton("Cancelar", null);
        }
        b.show();
    }

    /** Respaldo: abre la pantalla de información de la app en Ajustes. */
    public static void openAppSettings(Activity activity, String pkg) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + pkg));
            activity.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(activity, "No se pudo abrir los ajustes",
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** Confirmación antes de borrar un archivo marcado como amenaza. */
    public static void confirmAndDeleteFile(final Activity activity,
                                           final File file,
                                           final Runnable onDeleted) {
        new AlertDialog.Builder(activity)
                .setTitle("Borrar archivo")
                .setMessage("«" + file.getName() + "» se eliminará permanentemente "
                        + "del almacenamiento. Esta acción no se puede deshacer.")
                .setPositiveButton("Borrar", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (file.delete()) {
                            Toast.makeText(activity, "Archivo eliminado",
                                    Toast.LENGTH_SHORT).show();
                            if (onDeleted != null) onDeleted.run();
                        } else {
                            Toast.makeText(activity,
                                    "No se pudo borrar (quizá otra app lo está usando)",
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    /** Intenta pedir al PackageManager los detalles (para etiquetas). */
    public static String labelOf(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(
                    pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return pkg;
        }
    }
}
