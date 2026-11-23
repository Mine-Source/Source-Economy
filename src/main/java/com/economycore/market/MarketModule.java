package com.economycore.market;

import com.economycore.core.Cmds;
import com.economycore.core.Module;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bukkit.entity.Player;

public final class MarketModule extends Module {
    private MarketHub hub;

    @Override public String id() { return "markets"; }
    @Override public String title() { return "&bMercados"; }
    @Override public String icon() { return "BARREL"; }
    @Override public String description() { return "Mercados personalizados con horarios y rotaciones"; }

    @Override
    public void enable() {
        hub = MarketHub.of(pl);
        Cmds.register(pl, "markets", "economycore.use", true, (s, a) -> {
            Player p = (Player) s;
            if (a.length > 0) hub.menus.open(p, a[0].toLowerCase(), () -> hub.menus.list(p, p::closeInventory));
            else hub.menus.list(p, p::closeInventory);
        }, (s, a) -> {
            if (a.length != 1) return null;
            java.util.List<String> ids = new CopyOnWriteArrayList<>();
            hub.service.markets().thenAccept(l -> l.forEach(m -> ids.add(m.id())));
            return Cmds.filter(ids, a[0]);
        });
    }

    @Override
    public void open(Player p, Runnable back) { hub.menus.list(p, back); }
}
