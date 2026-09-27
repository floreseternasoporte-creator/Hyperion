package com.drex.hyperion.av;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Revisión del certificado firmante de cada app:
 *  - FLAG_DEBUGGABLE (APK firmado en modo debug: típico de malware casero)
 *  - SHA-256 del certificado firmante contra la base de firmas
 *  - sharedUserId="android.uid.system" en apps que NO son del sistema
 *      (suplantación de identidad de sistema)
 * Todo suma puntos al score con razones en español.
 */
public class CertCheck {

    public static class Result {
        public int score = 0;
        public final List<String> reasons = new ArrayList<>();
        public String certSha256 = null;
        public boolean debuggable = false;
        public boolean systemUidSpoof = false;
    }

    @SuppressWarnings("deprecation")
    public static Result check(Context ctx, PackageInfo pi, SignatureDb db) {
        Result r = new Result();
        if (pi == null || pi.applicationInfo == null) return r;
        PackageManager pm = ctx.getPackageManager();
        boolean system = (pi.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;

        // 1) APK debuggable
        if ((pi.applicationInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            r.debuggable = true;
            r.score += 25;
            r.reasons.add("APK compilado en modo debug (típico de malware no publicado en tiendas)");
        }

        // 2) Huella SHA-256 del certificado firmante
        try {
            Signature[] sigs = (pi.signatures != null && pi.signatures.length > 0)
                    ? pi.signatures
                    : querySignatures(pm, pi.packageName);
            if (sigs != null) {
                for (Signature sig : sigs) {
                    String sha = sha256Hex(sig.toByteArray());
                    if (r.certSha256 == null) r.certSha256 = sha;
                    if (db != null && db.matches(sha)) {
                        r.score = Math.max(r.score, 100);
                        String fam = db.familyOf(sha);
                        r.reasons.add(0, "Certificado firmante en la base de malware"
                                + (fam != null ? " (" + fam + ")" : ""));
                        break;
                    }
                }
            }
        } catch (Exception ignored) { }

        // 3) sharedUserId de sistema en app no-sistema
        try {
            if (!system && "android.uid.system".equals(pi.sharedUserId)) {
                r.systemUidSpoof = true;
                r.score += 45;
                r.reasons.add("Declara sharedUserId de sistema sin ser app del sistema (suplantación)");
            }
        } catch (Exception ignored) { }

        return r;
    }

    @SuppressWarnings("deprecation")
    private static Signature[] querySignatures(PackageManager pm, String pkg) {
        try {
            PackageInfo withSigs = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
            return withSigs != null ? withSigs.signatures : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
