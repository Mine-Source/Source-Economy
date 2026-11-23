package com.economycore.core;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;

/** Keeps user files intact on update: only adds keys that are missing. */
public final class Cfg {
    private Cfg() {}

    public static void mergeDefaults(EconomyCore pl, String res) {
        File f = new File(pl.getDataFolder(), res);
        if (!f.exists()) {
            pl.saveResource(res, false);
            return;
        }
        try (InputStream in = pl.getResource(res)) {
            if (in == null) return;
            YamlConfiguration user = YamlConfiguration.loadConfiguration(f);
            YamlConfiguration def = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            boolean changed = false;
            for (String k : def.getKeys(true)) {
                if (def.isConfigurationSection(k)) continue;
                if (!user.contains(k)) {
                    user.set(k, def.get(k));
                    changed = true;
                }
            }
            if (changed) user.save(f);
        } catch (Exception e) {
            pl.getLogger().warning("Could not merge defaults for " + res + ": " + e.getMessage());
        }
    }
}
