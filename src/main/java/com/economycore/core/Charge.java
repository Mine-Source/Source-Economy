package com.economycore.core;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;

/** Instant (main-thread) charge for events that cannot wait for the database: checks the cache, commits asynchronously. */
public final class Charge {
    private Charge() {}

    private static final Map<UUID, BigDecimal> PENDING = new ConcurrentHashMap<>();

    public static BigDecimal available(UUID u, String cur) {
        return Ledger.cached(u, cur).subtract(PENDING.getOrDefault(u, BigDecimal.ZERO));
    }

    /** Returns false when the player cannot afford it. The money is reserved immediately and moved on the DB thread. */
    public static boolean take(Player p, String cur, BigDecimal amount, Acct dest, String type, String note) {
        EconomyCore pl = EconomyCore.get();
        UUID u = p.getUniqueId();
        BigDecimal a = Money.scale(cur, amount);
        if (a.signum() <= 0) return true;
        if (available(u, cur).compareTo(a) < 0) return false;
        PENDING.merge(u, a, BigDecimal::add);
        pl.db().tx(tx -> { Ledger.transfer(tx, Acct.player(u), dest, cur, a, type, note); return null; })
                .whenComplete((r, t) -> PENDING.compute(u, (k, v) -> { BigDecimal n = v == null ? BigDecimal.ZERO : v.subtract(a); return n.signum() <= 0 ? null : n; }));
        return true;
    }
}
