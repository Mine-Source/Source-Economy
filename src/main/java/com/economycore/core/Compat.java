package com.economycore.core;

import org.bukkit.Bukkit;
import org.bukkit.Material;

/** Version helpers so a single jar can run across 1.21+ releases. */
public final class Compat {
    private Compat() {}

    private static int major = 1, minor = 21, patch = 0;

    static void init() {
        try {
            String v = Bukkit.getBukkitVersion().split("-")[0];
            String[] p = v.split("\\.");
            major = Integer.parseInt(p[0]);
            minor = Integer.parseInt(p[1]);
            patch = p.length > 2 ? Integer.parseInt(p[2]) : 0;
        } catch (Exception ignored) {}
    }

    public static String version() { return major + "." + minor + "." + patch; }

    public static boolean atLeast(int mj, int mn) { return major > mj || (major == mj && minor >= mn); }

    public static boolean isPaper() {
        try { Class.forName("io.papermc.paper.configuration.Configuration"); return true; } catch (ClassNotFoundException e) { return false; }
    }

    /** First material found among names, or fallback. */
    public static Material mat(Material fallback, String... names) {
        for (String n : names) {
            Material m = Material.matchMaterial(n);
            if (m != null && !m.isAir()) return m;
        }
        return fallback;
    }

    public static Material mat(String name) { return mat(Material.STONE, name); }
}
