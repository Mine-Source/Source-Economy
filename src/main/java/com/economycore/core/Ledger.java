package com.economycore.core;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Atomic money movements. Every method must run inside a Tx (DB thread). */
public final class Ledger {
    private Ledger() {}

    /** Write-through cache of committed balances (key = type:id:currency). */
    public static final Map<String, BigDecimal> CACHE = new ConcurrentHashMap<>();

    public record Settlement(BigDecimal gross, BigDecimal commission, BigDecimal tax, BigDecimal net) {}

    public static BigDecimal cached(UUID u, String cur) {
        BigDecimal b = CACHE.get(Acct.player(u).key(cur));
        return b == null ? BigDecimal.ZERO : b;
    }

    public static BigDecimal bal(Tx tx, Acct a, String cur) throws SQLException {
        String s = Sql.one(tx, "SELECT amount FROM balances WHERE owner_type=? AND owner_id=? AND currency=?", rs -> rs.getString(1), a.type(), a.id(), cur);
        return s == null ? BigDecimal.ZERO : new BigDecimal(s);
    }

    /** Adds delta (may be negative). Non-system accounts can never go below zero. */
    public static BigDecimal add(Tx tx, Acct a, String cur, BigDecimal delta) throws SQLException {
        BigDecimal now = bal(tx, a, cur).add(Money.scale(cur, delta));
        if (now.signum() < 0 && !a.isSystem()) throw new EcoException("insufficient-funds");
        Sql.exec(tx, "INSERT INTO balances(owner_type,owner_id,currency,amount) VALUES(?,?,?,?) ON CONFLICT(owner_type,owner_id,currency) DO UPDATE SET amount=excluded.amount",
                a.type(), a.id(), cur, now);
        tx.cache(a.key(cur), now);
        return now;
    }

    public static void set(Tx tx, Acct a, String cur, BigDecimal value) throws SQLException {
        BigDecimal v = Money.scale(cur, value);
        if (v.signum() < 0) throw new EcoException("invalid-amount");
        Sql.exec(tx, "INSERT INTO balances(owner_type,owner_id,currency,amount) VALUES(?,?,?,?) ON CONFLICT(owner_type,owner_id,currency) DO UPDATE SET amount=excluded.amount",
                a.type(), a.id(), cur, v);
        tx.cache(a.key(cur), v);
    }

    public static void record(Tx tx, String type, Acct from, Acct to, String cur, BigDecimal amt, BigDecimal fee, BigDecimal tax, String note) throws SQLException {
        Sql.exec(tx, "INSERT INTO transactions(ts,type,from_type,from_id,to_type,to_id,currency,amount,fee,tax,note) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                System.currentTimeMillis(), type, from.type(), from.id(), to.type(), to.id(), cur, amt, fee, tax, note);
        BigDecimal th = Money.dec(EconomyCore.get().getConfig().getString("analytics.alerts.large-transfer", "1000000"), null);
        if (th != null && amt.compareTo(th) >= 0 && !from.isSystem() && !to.isSystem()) {
            Alerts.raise(tx, "WARN", "large-transfer", type + ": " + Money.plain(cur, amt) + " " + from.type() + ":" + shortId(from) + " -> " + to.type() + ":" + shortId(to));
        }
    }

    private static String shortId(Acct a) { return a.id().length() > 8 ? a.id().substring(0, 8) : a.id(); }

    public static void transfer(Tx tx, Acct from, Acct to, String cur, BigDecimal amt, String type, String note) throws SQLException {
        amt = Money.scale(cur, amt);
        if (amt.signum() <= 0) throw new EcoException("invalid-amount");
        add(tx, from, cur, amt.negate());
        add(tx, to, cur, amt);
        record(tx, type, from, to, cur, amt, BigDecimal.ZERO, BigDecimal.ZERO, note);
    }

    /**
     * Payer pays gross; payee receives gross minus commission and progressive tax; the fees go to the treasury.
     * taxSubject null means no tax is applied.
     */
    public static Settlement settle(Tx tx, Acct payer, Acct payee, String cur, BigDecimal gross, BigDecimal commissionPct,
                                    String taxKind, UUID taxSubject, String type, String note) throws SQLException {
        gross = Money.scale(cur, gross);
        if (gross.signum() <= 0) throw new EcoException("invalid-amount");
        BigDecimal commission = commissionPct == null || commissionPct.signum() <= 0 ? BigDecimal.ZERO : Money.scale(cur, Money.pct(gross, commissionPct));
        BigDecimal tax = BigDecimal.ZERO;
        if (taxKind != null && taxSubject != null) tax = Money.scale(cur, Taxes.compute(tx, taxKind, cur, gross, taxSubject));
        BigDecimal fees = commission.add(tax);
        if (fees.compareTo(gross) > 0) { fees = gross; tax = gross.subtract(commission).max(BigDecimal.ZERO); }
        BigDecimal net = gross.subtract(fees);
        add(tx, payer, cur, gross.negate());
        if (net.signum() > 0) add(tx, payee, cur, net);
        if (fees.signum() > 0) add(tx, Acct.TREASURY, cur, fees);
        record(tx, type, payer, payee, cur, gross, commission, tax, note);
        return new Settlement(gross, commission, tax, net);
    }
}
