package com.drex.hyperion.av;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;

/** Utilidad: SHA-256 hexadecimal de un fichero. */
public final class HashUtil {
    private HashUtil() { }

    public static String sha256OfFile(File f) {
        if (f == null) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            InputStream in = new DigestInputStream(new FileInputStream(f), md);
            byte[] buf = new byte[65536];
            while (in.read(buf) > 0) { /* digest */ }
            in.close();
            byte[] d = md.digest();
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
