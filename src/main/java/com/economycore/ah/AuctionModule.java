package com.economycore.ah;

import com.economycore.core.Cmds;
import com.economycore.core.Module;
import com.economycore.core.Money;
import com.economycore.core.Msg;
import com.economycore.core.Tasks;
import java.math.BigDecimal;
import java.util.Arrays;
import org.bukkit.entity.Player;

public final class AuctionModule extends Module {
    private AuctionService svc;
    private AuctionMenus menus;

    @Override public String id() { return "auction"; }
    @Override public String title() { return "&dCasa de subastas"; }
    @Override public String icon() { return "GOLD_BLOCK"; }
    @Override public String description() { return "BIN, subastas, pujas y favoritos"; }

    @Override
    public void enable() {
        svc = new AuctionService(pl);
        menus = new AuctionMenus(pl, svc);
        pl.track(Tasks.timer(200L, 200L, svc::settleExpired));
        Cmds.register(pl, "ah", "economycore.use", true, (s, a) -> {
            Player p = (Player) s;
            if (a.length > 0 && a[0].equalsIgnoreCase("sell")) {
                if (!p.hasPermission("economycore.ah.sell")) { Msg.send(p, "no-permission"); return; }
                if (a.length < 2) { Msg.send(p, "usage-ah-sell"); return; }
                BigDecimal price = Money.parse(a[1]);
                int hours = 24;
                if (a.length > 2) {
                    try { hours = Integer.parseInt(a[2]); } catch (NumberFormatException e) { Msg.send(p, "invalid-number"); return; }
                }
                int max = pl.getConfig().getInt("auction-house.max-duration-hours", 168);
                if (price == null || hours < 1 || hours > max) { Msg.send(p, "invalid-number"); return; }
                String type = a.length > 3 && a[3].toLowerCase().startsWith("a") ? "AUCTION" : "BIN";
                menus.create(p, type, price, null, hours, null);
                return;
            }
            menus.browse(p, AuctionMenus.defaultFilter(), p::closeInventory);
        }, (s, a) -> a.length == 1 ? Cmds.filter(Arrays.asList("sell"), a[0]) : a.length == 4 ? Cmds.filter(Arrays.asList("bin", "auction"), a[3]) : null);
    }

    @Override
    public void open(Player p, Runnable back) { menus.browse(p, AuctionMenus.defaultFilter(), back); }
}
