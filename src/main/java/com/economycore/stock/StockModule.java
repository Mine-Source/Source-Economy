package com.economycore.stock;

import com.economycore.company.CompanyModule;
import com.economycore.company.CompanyService;
import com.economycore.core.Cmds;
import com.economycore.core.Module;
import com.economycore.core.Tasks;
import org.bukkit.entity.Player;

public final class StockModule extends Module {
    private StockMenus menus;
    private StockService svc;

    @Override public String id() { return "stocks"; }
    @Override public String title() { return "&aBolsa de valores"; }
    @Override public String icon() { return "NETHER_STAR"; }
    @Override public String description() { return "Acciones, IPO, órdenes, índice y portafolios"; }

    public StockService service() { return svc; }

    public StockMenus menus() { return menus; }

    @Override
    public void enable() {
        CompanyService cs;
        if (pl.module("company") instanceof CompanyModule cm) cs = cm.service();
        else {
            cs = new CompanyService(pl);
            try { cs.seedDefaults(); } catch (Exception e) { pl.getLogger().warning("Company seed: " + e); }
        }
        svc = new StockService(pl, cs);
        menus = new StockMenus(pl, svc);
        long tick = Math.max(5, pl.getConfig().getLong("stocks.tick-seconds", 30)) * 20L;
        pl.track(Tasks.timer(tick, tick, svc::tick));
        long snap = Math.max(1, pl.getConfig().getLong("stocks.snapshot-minutes", 15)) * 1200L;
        pl.track(Tasks.timer(snap, snap, svc::snapshot));
        Cmds.register(pl, "stocks", "economycore.use", true, (s, a) -> open((Player) s, ((Player) s)::closeInventory));
    }

    @Override
    public void open(Player p, Runnable back) { menus.main(p, back); }
}
