package com.economycore.treasury;

import com.economycore.core.Cmds;
import com.economycore.core.Module;
import com.economycore.core.Tasks;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;

public final class TreasuryModule extends Module implements Listener {
    private TreasuryService svc;
    private TreasuryMenus menus;

    @Override public String id() { return "treasury"; }
    @Override public String title() { return "&6Tesorería"; }
    @Override public String icon() { return "GOLD_BLOCK"; }
    @Override public String description() { return "Impuestos, presupuestos, subsidios, recompensas y proyectos"; }

    public TreasuryService service() { return svc; }

    @Override
    public void enable() {
        svc = new TreasuryService(pl);
        menus = new TreasuryMenus(pl, svc);
        svc.reloadRewards();
        pl.getServer().getPluginManager().registerEvents(this, pl);
        pl.track(Tasks.timer(1200L, 1200L, svc::paySubsidies));
        pl.track(Tasks.timer(1200L, 1200L, svc::resolveProjects));
        long hours = pl.getConfig().getLong("treasury.wealth-tax-interval-hours", 24);
        if (hours > 0) pl.track(Tasks.timer(hours * 72000L, hours * 72000L, svc::wealthTax));
        Cmds.register(pl, "treasury", "economycore.use", true, (s, a) -> open((Player) s, ((Player) s)::closeInventory));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { Player pp = e.getPlayer(); Tasks.later(60L, () -> { if (pp.isOnline()) svc.trigger(pp, "JOIN", ""); }); }

    @EventHandler
    public void onKill(EntityDeathEvent e) {
        Player k = e.getEntity().getKiller();
        if (k != null && !(e.getEntity() instanceof Player)) svc.trigger(k, "KILL_MOB", e.getEntityType().name());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) { svc.trigger(e.getPlayer(), "BREAK", e.getBlock().getType().name()); }

    @Override
    public void open(Player p, Runnable back) { menus.main(p, back); }
}
