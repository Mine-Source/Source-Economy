package com.economycore.core;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

public final class Tasks {
    private static EconomyCore plugin;

    private Tasks() {}

    static void init(EconomyCore p) { plugin = p; }

    public static void sync(Runnable r) {
        if (plugin == null || !plugin.isEnabled()) return;
        try { Bukkit.getScheduler().runTask(plugin, r); } catch (Exception ignored) {}
    }

    public static void async(Runnable r) {
        if (plugin == null || !plugin.isEnabled()) return;
        try { Bukkit.getScheduler().runTaskAsynchronously(plugin, r); } catch (Exception ignored) {}
    }

    public static void later(long ticks, Runnable r) {
        if (plugin == null || !plugin.isEnabled()) return;
        try { Bukkit.getScheduler().runTaskLater(plugin, r, ticks); } catch (Exception ignored) {}
    }

    public static BukkitTask timer(long delayTicks, long periodTicks, Runnable r) {
        return Bukkit.getScheduler().runTaskTimer(plugin, r, delayTicks, periodTicks);
    }
}
