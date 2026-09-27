package com.drex.hyperion.av;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Inspección real del bytecode: abre el APK como ZIP con java.util.zip.ZipFile,
 * extrae cada classes.dex (puede haber varios: classes.dex, classes2.dex, …)
 * y busca como BYTES las firmas de APIs típicas de malware Android.
 * No usa heurísticas inventadas: si la cadena aparece en el dex, el código
 * realmente referencia esa API.
 */
public class DexInspector {

    /** Patrón de bytes → descripción + peso (puntos de riesgo). */
    private static class Sig {
        final byte[] bytes;
        final String label;
        final int weight;
        Sig(String text, String label, int weight) {
            // las referencias del dex están en ASCII/MUTF-8; basta con ASCII
            this.bytes = text.getBytes(java.nio.charset.Charset.forName("US-ASCII"));
            this.label = label;
            this.weight = weight;
        }
    }

    private static final Sig[] SIGS = new Sig[] {
        new Sig("Landroid/telephony/SmsManager", "Usa SmsManager (envío/lectura de SMS)", 18),
        new Sig("sendTextMessage", "Llama sendTextMessage (enviar SMS por código)", 20),
        new Sig("getDeviceId", "Lee el IMEI con getDeviceId", 12),
        new Sig("getSubscriberId", "Lee el IMSI con getSubscriberId", 12),
        new Sig("BIND_ACCESSIBILITY_SERVICE", "Registra servicio de accesibilidad", 22),
        new Sig("accessibility", "Referencias a accesibilidad", 10),
        new Sig("DevicePolicyManager", "Usa DevicePolicyManager (admin del dispositivo)", 18),
        new Sig("Runtime;->exec", "Ejecuta comandos nativos (Runtime.exec)", 16),
        new Sig("Ljavax/crypto", "Usa javax.crypto (cifrado: posible ransomware/exfiltración)", 8),
    };

    /** Tamaño máximo de dex a leer por entrada (64 MB): límite honesto anti-DoS. */
    private static final long MAX_DEX_BYTES = 64L * 1024 * 1024;

    public static class Finding {
        public final String label;
        public final int weight;
        Finding(String label, int weight) { this.label = label; this.weight = weight; }
    }

    /**
     * Inspecciona el APK y devuelve los hallazgos (uno por firma como máximo).
     * Si el APK no se puede abrir, devuelve lista vacía (no inventa nada).
     */
    public static List<Finding> inspect(String apkPath) {
        List<Finding> out = new ArrayList<>();
        if (apkPath == null) return out;
        ZipFile zf = null;
        try {
            zf = new ZipFile(apkPath);
            boolean[] hit = new boolean[SIGS.length];
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (!name.endsWith(".dex")) continue;
                if (e.getSize() > MAX_DEX_BYTES) continue;
                byte[] dex = readAll(zf.getInputStream(e), e.getSize());
                if (dex == null) continue;
                for (int i = 0; i < SIGS.length; i++) {
                    if (!hit[i] && indexOf(dex, SIGS[i].bytes) >= 0) {
                        hit[i] = true;
                        out.add(new Finding(SIGS[i].label, SIGS[i].weight));
                    }
                }
            }
        } catch (Exception ignored) {
            // APK ilegible: sin hallazgos (mejor que falsos positivos)
        } finally {
            if (zf != null) try { zf.close(); } catch (Exception ignored) { }
        }
        return out;
    }

    /** Suma de pesos de los hallazgos. */
    public static int totalWeight(List<Finding> findings) {
        int s = 0;
        for (Finding f : findings) s += f.weight;
        return s;
    }

    private static byte[] readAll(InputStream in, long size) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(
                    size > 0 && size < Integer.MAX_VALUE ? (int) size : 8192);
            byte[] buf = new byte[65536];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_DEX_BYTES) { in.close(); return null; }
                bos.write(buf, 0, n);
            }
            in.close();
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** Búsqueda ingenua de subarreglo de bytes. */
    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
