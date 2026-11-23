package com.economycore.eco;

import com.economycore.core.Cmds;
import com.economycore.core.Currency;
import com.economycore.core.Module;
import com.economycore.core.Money;
import com.economycore.core.Msg;
import com.economycore.core.Tasks;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.entity.Player;

public final class EconomyModule extends Module {
    private EconomyService service;
    private EconomyMenus menus;

    @Override public String id() { return "economy"; }
    @Override public String title() { return "&6Economía"; }
    @Override public String icon() { return "GOLD_INGOT"; }
    @Override public String description() { return "Balances, pagos, historial y ranking"; }

    public EconomyService service() { return service; }
    public EconomyMenus menus() { return menus; }

    @Override
    public void enable() {
        service = new EconomyService(pl);
        menus = new EconomyMenus(pl, service);

        Cmds.register(pl, "balance", "economycore.use", false, (s, a) -> {
            if (a.length == 0) {
                if (!(s instanceof Player p)) { Msg.send(s, "players-only"); return; }
                menus.showBalance(p);
                return;
            }
            if (!s.hasPermission("economycore.admin")) { Msg.send(s, "no-permission"); return; }
            service.balancesByName(a[0]).whenComplete((m, t) -> Tasks.sync(() -> {
                if (t != null) { Msg.error(s, t); return; }
                s.sendMessage(Msg.get("balance-of", a[0]));
                m.forEach((k, v) -> s.sendMessage(Msg.raw("balance-line", pl.currencies().getOrDefault(k).name(), Money.fmt(k, v))));
            }));
        }, (s, a) -> a.length == 1 ? Cmds.players(a[0]) : null);

        Cmds.register(pl, "pay", "economycore.pay", true, (s, a) -> {
            Player p = (Player) s;
            if (a.length < 2) { Msg.send(s, "usage-pay"); return; }
            BigDecimal amt = Money.parse(a[1]);
            Currency c = a.length > 2 ? pl.currencies().get(a[2]) : pl.currencies().def();
            if (amt == null || amt.signum() <= 0) { Msg.send(s, "invalid-amount"); return; }
            if (c == null) { Msg.send(s, "unknown-currency", a[2]); return; }
            menus.doPay(p, a[0], c.id(), amt);
        }, (s, a) -> {
            if (a.length == 1) return Cmds.players(a[0]);
            if (a.length == 3) {
                List<String> ids = new ArrayList<>();
                for (Currency c : pl.currencies().all()) ids.add(c.id());
                return Cmds.filter(ids, a[2]);
            }
            return null;
        });

        Cmds.register(pl, "baltop", "economycore.use", true, (s, a) -> {
            Currency c = a.length > 0 ? pl.currencies().get(a[0]) : pl.currencies().def();
            if (c == null) { Msg.send(s, "unknown-currency", a[0]); return; }
            menus.top((Player) s, c.id(), () -> ((Player) s).closeInventory());
        });

        Cmds.register(pl, "mailbox", "economycore.use", true, (s, a) -> menus.mailbox((Player) s, () -> ((Player) s).closeInventory()));
    }

    @Override
    public void open(Player p, Runnable back) { menus.main(p, back); }
}
