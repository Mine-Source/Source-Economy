package com.economycore.core;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

/** messages.yml access. Missing keys fall back to the key itself. */
public final class Msg {
    private static YamlConfiguration cfg = new YamlConfiguration();
    private static YamlConfiguration defaults = new YamlConfiguration();
    private static String prefix = "&6[Eco] &7";

    private Msg() {}

    static void load(EconomyCore pl) {
        cfg = YamlConfiguration.loadConfiguration(new File(pl.getDataFolder(), "messages.yml"));
        try (InputStreamReader r = new InputStreamReader(pl.getResource("messages.yml"), StandardCharsets.UTF_8)) {
            defaults = YamlConfiguration.loadConfiguration(r);
        } catch (Exception ignored) {}
        prefix = cfg.getString("prefix", "&6[Eco] &7");
    }

    public static String color(String s) {
        return s == null ? "" : ChatColor.translateAlternateColorCodes('&', s);
    }

    public static String strip(String s) {
        return s == null ? "" : ChatColor.stripColor(color(s));
    }

    public static String raw(String key, Object... args) {
        String s = cfg.getString(key, defaults.getString(key, key));
        for (int i = 0; i < args.length; i++) s = s.replace("{" + i + "}", String.valueOf(args[i]));
        return color(s);
    }

    public static String get(String key, Object... args) {
        return color(prefix) + raw(key, args);
    }

    public static void send(CommandSender to, String key, Object... args) {
        if (to != null) to.sendMessage(get(key, args));
    }

    public static void error(CommandSender to, Throwable t) {
        t = Db.unwrap(t);
        if (t instanceof EcoException e) send(to, e.key(), e.args());
        else send(to, "internal-error");
    }
}
