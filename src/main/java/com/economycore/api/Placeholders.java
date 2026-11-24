package com.economycore.api;

import com.economycore.core.EconomyCore;
import com.economycore.core.Tasks;
import java.util.logging.Level;
import org.bukkit.Bukkit;

/** Registers the PlaceholderAPI expansion only when PlaceholderAPI is installed. */
public final class Placeholders {
    private Placeholders() {}

    public static void tryRegister(EconomyCore pl) {
        if (!pl.getConfig().getBoolean("placeholders.enabled", true)) return;
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) return;
        try {
            PlaceholderData data = new PlaceholderData(pl);
            data.refresh();
            long sec = Math.max(5, pl.getConfig().getLong("placeholders.refresh-seconds", 30));
            pl.track(Tasks.timer(sec * 20L, sec * 20L, data::refresh));
            new PapiExpansion(pl, data).register();
            pl.getLogger().info("PlaceholderAPI detectado: placeholders %economycore_*% registrados.");
        } catch (Throwable t) {
            pl.getLogger().log(Level.WARNING, "No se pudo registrar PlaceholderAPI", t);
        }
    }
}
