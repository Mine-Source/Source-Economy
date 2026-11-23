package com.economycore.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/** Lambda-based command registration. */
public final class Cmds {
    private Cmds() {}

    public static void register(EconomyCore pl, String name, String permission, boolean playerOnly,
                                BiConsumer<CommandSender, String[]> fn, BiFunction<CommandSender, String[], List<String>> tab) {
        PluginCommand c = pl.getCommand(name);
        if (c == null) return;
        c.setExecutor((CommandSender s, Command cmd, String label, String[] args) -> {
            if (permission != null && !s.hasPermission(permission)) { Msg.send(s, "no-permission"); return true; }
            if (playerOnly && !(s instanceof Player)) { Msg.send(s, "players-only"); return true; }
            try { fn.accept(s, args); } catch (Exception e) { Msg.error(s, e); }
            return true;
        });
        c.setTabCompleter((CommandSender s, Command cmd, String alias, String[] args) -> {
            List<String> r = tab == null ? null : tab.apply(s, args);
            return r == null ? Collections.emptyList() : r;
        });
    }

    public static void register(EconomyCore pl, String name, String permission, boolean playerOnly, BiConsumer<CommandSender, String[]> fn) {
        register(pl, name, permission, playerOnly, fn, null);
    }

    public static void disabled(EconomyCore pl, String name) {
        PluginCommand c = pl.getCommand(name);
        if (c != null) c.setExecutor((CommandExecutor) (s, cmd, l, a) -> { Msg.send(s, "module-disabled"); return true; });
    }

    public static List<String> filter(List<String> opts, String prefix) {
        List<String> out = new ArrayList<>();
        String p = prefix == null ? "" : prefix.toLowerCase();
        for (String o : opts) if (o.toLowerCase().startsWith(p)) out.add(o);
        return out;
    }

    public static List<String> players(String prefix) {
        List<String> n = new ArrayList<>();
        for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) n.add(p.getName());
        return filter(n, prefix);
    }

    public static Player player(CommandSender s) { return (Player) s; }
}
