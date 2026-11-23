package com.economycore.core;

import java.math.BigDecimal;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Registers players, grants starting balances, warms the balance cache and delivers pending items. */
public final class JoinListener implements Listener {
    private final EconomyCore pl;

    public JoinListener(EconomyCore pl) { this.pl = pl; }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { prepare(e.getPlayer()); }

    public void prepare(Player p) {
        UUID id = p.getUniqueId();
        Acct a = Acct.player(id);
        pl.db().tx(tx -> {
            Players.touch(tx, p);
            for (Currency c : pl.currencies().all()) {
                long has = Sql.count(tx, "SELECT COUNT(*) FROM balances WHERE owner_type='PLAYER' AND owner_id=? AND currency=?", id.toString(), c.id());
                if (has == 0) {
                    if (c.startBalance().signum() > 0) Ledger.transfer(tx, Acct.SYSTEM, a, c.id(), c.startBalance(), "START", "starting balance");
                    else Ledger.set(tx, a, c.id(), BigDecimal.ZERO);
                } else {
                    Ledger.add(tx, a, c.id(), BigDecimal.ZERO);
                }
            }
            return null;
        }).whenComplete((r, t) -> Tasks.sync(() -> { if (p.isOnline()) Mailbox.deliver(p); }));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        String prefix = "PLAYER:" + e.getPlayer().getUniqueId() + ":";
        Ledger.CACHE.keySet().removeIf(k -> k.startsWith(prefix));
    }

    public void prepareAll() { for (Player p : Bukkit.getOnlinePlayers()) prepare(p); }
}
