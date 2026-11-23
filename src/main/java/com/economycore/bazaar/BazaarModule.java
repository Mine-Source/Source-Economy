package com.economycore.bazaar;

import com.economycore.core.Cmds;
import com.economycore.core.Module;
import org.bukkit.entity.Player;

public final class BazaarModule extends Module {
    private BazaarMenus menus;

    @Override public String id() { return "bazaar"; }
    @Override public String title() { return "&eBazaar"; }
    @Override public String icon() { return "BARREL"; }
    @Override public String description() { return "Órdenes de compra/venta con libro de órdenes"; }

    @Override
    public void enable() {
        BazaarService svc = new BazaarService(pl);
        try { svc.seed(); svc.loadCache(); } catch (Exception e) { pl.getLogger().warning("Bazaar cache: " + e); }
        menus = new BazaarMenus(pl, svc);
        Cmds.register(pl, "bazaar", "economycore.use", true, (s, a) -> open((Player) s, ((Player) s)::closeInventory));
    }

    @Override
    public void open(Player p, Runnable back) { menus.main(p, back); }
}
