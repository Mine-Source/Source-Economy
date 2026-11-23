package com.economycore.market;

import com.economycore.core.Cmds;
import com.economycore.core.Module;
import com.economycore.core.Msg;
import com.economycore.core.Tasks;
import org.bukkit.entity.Player;

public final class ShopModule extends Module {
    private MarketHub hub;

    @Override public String id() { return "shop"; }
    @Override public String title() { return "&aTienda"; }
    @Override public String icon() { return "EMERALD"; }
    @Override public String description() { return "Compra, vende y SellAll"; }

    @Override
    public void enable() {
        hub = MarketHub.of(pl);
        Cmds.register(pl, "shop", "economycore.use", true, (s, a) -> open((Player) s, () -> ((Player) s).closeInventory()));
        Cmds.register(pl, "sellall", "economycore.use", true, (s, a) -> hub.menus.sellAll((Player) s, () -> ((Player) s).closeInventory()));
    }

    @Override
    public void open(Player p, Runnable back) {
        hub.service.shop().thenAccept(m -> Tasks.sync(() -> {
            if (m == null) { Msg.send(p, "market-not-found"); return; }
            if (!hub.menus.canAccess(p, m)) { Msg.send(p, "no-permission"); return; }
            hub.menus.categories(p, m, back);
        }));
    }
}
