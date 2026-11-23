package com.economycore.pshop;

import com.economycore.core.Cmds;
import com.economycore.core.Module;
import org.bukkit.entity.Player;

public final class PShopModule extends Module {
    private PShopMenus menus;

    @Override public String id() { return "playershops"; }
    @Override public String title() { return "&aTiendas de jugadores"; }
    @Override public String icon() { return "CHEST_MINECART"; }
    @Override public String description() { return "Crea tu tienda con precios y stock"; }

    @Override
    public void enable() {
        menus = new PShopMenus(pl, new PShopService(pl));
        Cmds.register(pl, "pshop", "economycore.use", true, (s, a) -> open((Player) s, ((Player) s)::closeInventory));
    }

    @Override
    public void open(Player p, Runnable back) { menus.list(p, back); }
}
