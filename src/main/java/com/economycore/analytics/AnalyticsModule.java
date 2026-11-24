package com.economycore.analytics;

import com.economycore.core.Cmds;
import com.economycore.core.Module;
import com.economycore.core.Tasks;
import org.bukkit.entity.Player;

public final class AnalyticsModule extends Module {
    private AnalyticsService svc;
    private AnalyticsMenus menus;

    @Override public String id() { return "analytics"; }
    @Override public String title() { return "&bAnalítica"; }
    @Override public String icon() { return "COMPASS"; }
    @Override public String description() { return "Inflación, circulación, rankings, estadísticas y alertas"; }

    @Override
    public void enable() {
        svc = new AnalyticsService(pl);
        menus = new AnalyticsMenus(pl, svc);
        long t = Math.max(1, pl.getConfig().getLong("analytics.snapshot-minutes", 10)) * 1200L;
        pl.track(Tasks.timer(600L, t, svc::snapshot));
        Surveillance sv = new Surveillance(pl);
        long st = Math.max(1, pl.getConfig().getLong("analytics.manipulation.interval-minutes", 5)) * 1200L;
        pl.track(Tasks.timer(1200L, st, sv::run));
        Cmds.register(pl, "ecostats", "economycore.use", true, (s, a) -> open((Player) s, ((Player) s)::closeInventory));
    }

    @Override
    public void open(Player p, Runnable back) { menus.main(p, back); }
}
