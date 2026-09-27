package com.drex.hyperion;

import java.util.HashMap;
import java.util.Map;

/**
 * Banderas vectoriales simplificadas (dibujadas geométricamente) para los
 * países con más servidores VPNGate. El resto usa flag_generic + el código
 * ISO en texto (insignia genérica).
 */
public final class FlagDrawables {
    private FlagDrawables() {}

    private static final Map<String, Integer> MAP = new HashMap<>();
    static {
        MAP.put("JP", R.drawable.flag_jp);
        MAP.put("KR", R.drawable.flag_kr);
        MAP.put("US", R.drawable.flag_us);
        MAP.put("TH", R.drawable.flag_th);
        MAP.put("VN", R.drawable.flag_vn);
        MAP.put("RU", R.drawable.flag_ru);
        MAP.put("IN", R.drawable.flag_in);
        MAP.put("DE", R.drawable.flag_de);
        MAP.put("GB", R.drawable.flag_gb);
        MAP.put("FR", R.drawable.flag_fr);
        MAP.put("CA", R.drawable.flag_ca);
        MAP.put("SG", R.drawable.flag_sg);
        MAP.put("BR", R.drawable.flag_br);
        MAP.put("NL", R.drawable.flag_nl);
        MAP.put("AU", R.drawable.flag_au);
    }

    /** true si hay bandera dibujada para este ISO. */
    public static boolean hasFlag(String iso) {
        return iso != null && MAP.containsKey(iso);
    }

    public static int forIso(String iso) {
        Integer id = iso == null ? null : MAP.get(iso);
        return id == null ? R.drawable.flag_generic : id;
    }
}
