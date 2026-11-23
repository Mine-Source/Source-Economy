package com.economycore.admin;

import com.economycore.core.Cmds;
import com.economycore.core.Currency;
import com.economycore.core.EconomyCore;
import com.economycore.core.Module;
import com.economycore.core.Money;
import com.economycore.core.Msg;
import com.economycore.core.Tasks;
import com.economycore.eco.EconomyModule;
import com.economycore.gui.Btn;
import com.economycore.gui.ButtonMenu;
import com.economycore.gui.Items;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /eco: the central menu that links every module. */
public final class AdminHub {
    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
    private final EconomyCore pl;

    public AdminHub(EconomyCore pl) { this.pl = pl; }

    public void registerCommand() {
        Cmds.register(pl, "eco", "economycore.use", false, this::command, (s, a) -> {
            if (a.length == 1) {
                List<String> o = new ArrayList<>(Arrays.asList("give", "take", "set", "reload", "backup", "open"));
                return Cmds.filter(o, a[0]);
            }
            if (a.length == 2 && (a[0].equals("give") || a[0].equals("take") || a[0].equals("set"))) return Cmds.players(a[1]);
            if (a.length == 2 && a[0].equals("open")) return Cmds.filter(new ArrayList<>(pl.modules().keySet()), a[1]);
            return null;
        });
    }

    private void command(CommandSender s, String[] a) {
        if (a.length == 0) {
            if (!(s instanceof Player p)) { Msg.send(s, "players-only"); return; }
            open(p);
            return;
        }
        String sub = a[0].toLowerCase();
        if (sub.equals("open") && a.length > 1 && s instanceof Player p) {
            Module m = pl.module(a[1].toLowerCase());
            if (m == null) { Msg.send(s, "module-disabled"); return; }
            m.open(p, () -> open(p));
            return;
        }
        if (!s.hasPermission("economycore.admin")) { Msg.send(s, "no-permission"); return; }
        switch (sub) {
            case "reload" -> { pl.reloadAll(); Msg.send(s, "reloaded"); }
            case "backup" -> pl.db().backup("economy-manual").whenComplete((f, t) -> Tasks.sync(() -> {
                if (t != null) Msg.send(s, "backup-failed"); else Msg.send(s, "backup-done", f.getName());
            }));
            case "give", "take", "set" -> {
                if (a.length < 3) { Msg.send(s, "usage-eco"); return; }
                BigDecimal amt = Money.parse(a[2]);
                Currency c = a.length > 3 ? pl.currencies().get(a[3]) : pl.currencies().def();
                if (amt == null) { Msg.send(s, "invalid-amount"); return; }
                if (c == null) { Msg.send(s, "unknown-currency", a[3]); return; }
                EconomyModule em = (EconomyModule) pl.module("economy");
                em.service().admin(s.getName(), sub, a[1], c.id(), amt).whenComplete((r, t) -> Tasks.sync(() -> {
                    if (t != null) Msg.error(s, t); else Msg.send(s, "admin-balance-done", a[1], Money.fmt(c.id(), r));
                }));
            }
            default -> Msg.send(s, "usage-eco");
        }
    }

    public void open(Player p) {
        boolean admin = p.hasPermission("economycore.admin");
        new ButtonMenu(pl, p, admin ? "&6&lEconomyCore &8- &cAdministración" : "&6&lEconomyCore", 5, true, () -> {
            List<Btn> l = new ArrayList<>();
            int i = 0;
            for (Module m : pl.modules().values()) {
                if (i >= SLOTS.length) break;
                if (!p.hasPermission(m.permission())) continue;
                l.add(Btn.of(SLOTS[i++], Items.of(m.icon(), m.title(), "&7" + m.description(), "&eClic para abrir"), () -> m.open(p, () -> open(p))));
            }
            l.add(Btn.of(40, Items.of(Material.BOOK, "&7EconomyCore v" + pl.getDescription().getVersion(), "&7Módulos activos: &f" + pl.modules().size(), admin ? "&aModo administrador" : "&7Modo jugador"), () -> {}));
            return l;
        }).open();
    }
}
