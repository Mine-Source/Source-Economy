package com.economycore.analytics;

import com.economycore.core.Alerts;
import com.economycore.core.EconomyCore;
import com.economycore.core.Money;
import com.economycore.core.Sql;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class AnalyticsService {
    private final EconomyCore pl;

    public record Snap(long ts, String currency, BigDecimal supply, BigDecimal players, BigDecimal banks, BigDecimal companies, BigDecimal treasury, BigDecimal escrow, long txCount, BigDecimal txVolume, int accounts) {}

    public record Overview(Snap latest, BigDecimal inflationPct, BigDecimal velocity, List<BigDecimal> supplyHistory, BigDecimal topShare) {}

    public record Rank(String uuid, String name, BigDecimal value) {}

    public record AlertRow(long id, long ts, String level, String kind, String message, boolean ack) {}

    public AnalyticsService(EconomyCore pl) { this.pl = pl; }

    private static Snap snap(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Snap(rs.getLong("ts"), rs.getString("currency"), Money.dec(rs.getString("supply"), BigDecimal.ZERO), Money.dec(rs.getString("players"), BigDecimal.ZERO),
                Money.dec(rs.getString("banks"), BigDecimal.ZERO), Money.dec(rs.getString("companies"), BigDecimal.ZERO), Money.dec(rs.getString("treasury"), BigDecimal.ZERO),
                Money.dec(rs.getString("escrow"), BigDecimal.ZERO), rs.getLong("tx_count"), Money.dec(rs.getString("tx_volume"), BigDecimal.ZERO), rs.getInt("accounts"));
    }

    // ---------- periodic snapshot + alert checks ----------
    public void snapshot() {
        long now = System.currentTimeMillis();
        long window = Math.max(1, pl.getConfig().getLong("analytics.snapshot-minutes", 10)) * 60_000L;
        pl.db().tx(tx -> {
            for (var c : pl.currencies().all()) {
                Map<String, BigDecimal> by = new HashMap<>();
                Map<String, Integer> cnt = new HashMap<>();
                BigDecimal top = BigDecimal.ZERO;
                for (String[] r : Sql.list(tx, "SELECT owner_type, amount FROM balances WHERE currency=?", rs -> new String[]{rs.getString(1), rs.getString(2)}, c.id())) {
                    BigDecimal v = new BigDecimal(r[1]);
                    by.merge(r[0], v, BigDecimal::add);
                    cnt.merge(r[0], 1, Integer::sum);
                    if (r[0].equals("PLAYER") && v.compareTo(top) > 0) top = v;
                }
                BigDecimal pls = by.getOrDefault("PLAYER", BigDecimal.ZERO), bks = by.getOrDefault("BANK", BigDecimal.ZERO), cps = by.getOrDefault("COMPANY", BigDecimal.ZERO),
                        tr = by.getOrDefault("TREASURY", BigDecimal.ZERO), es = by.getOrDefault("ESCROW", BigDecimal.ZERO);
                BigDecimal supply = pls.add(bks).add(cps).add(tr).add(es);
                long since = now - window;
                BigDecimal vol = BigDecimal.ZERO;
                long n = 0;
                for (String a : Sql.list(tx, "SELECT amount FROM transactions WHERE ts>? AND currency=? AND type NOT IN ('START')", rs -> rs.getString(1), since, c.id())) { vol = vol.add(new BigDecimal(a)); n++; }
                Sql.exec(tx, "INSERT OR REPLACE INTO analytics(ts,currency,supply,players,banks,companies,treasury,escrow,tx_count,tx_volume,accounts) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                        now, c.id(), supply, pls, bks, cps, tr, es, n, vol, cnt.getOrDefault("PLAYER", 0));
                checkAlerts(tx, c.id(), supply, tr, top, now);
            }
            Sql.exec(tx, "DELETE FROM analytics WHERE ts<?", now - 90L * 86_400_000L);
            return null;
        });
    }

    private boolean recentAlert(com.economycore.core.Tx tx, String kind, long now) throws java.sql.SQLException {
        return Sql.count(tx, "SELECT COUNT(*) FROM alerts WHERE kind=? AND ts>?", kind, now - 6 * 3_600_000L) > 0;
    }

    private void checkAlerts(com.economycore.core.Tx tx, String cur, BigDecimal supply, BigDecimal treasury, BigDecimal topBal, long now) throws java.sql.SQLException {
        BigDecimal growthLimit = Money.dec(pl.getConfig().getString("analytics.alerts.supply-growth-percent", "10"), null);
        Snap old = Sql.one(tx, "SELECT * FROM analytics WHERE currency=? AND ts<=? ORDER BY ts DESC LIMIT 1", AnalyticsService::snap, cur, now - 86_400_000L);
        if (growthLimit != null && growthLimit.signum() > 0 && old != null && old.supply().signum() > 0) {
            BigDecimal g = supply.subtract(old.supply()).multiply(BigDecimal.valueOf(100)).divide(old.supply(), 2, RoundingMode.HALF_UP);
            if (g.compareTo(growthLimit) > 0 && !recentAlert(tx, "inflation-" + cur, now)) Alerts.raise(tx, "WARN", "inflation-" + cur, "Masa monetaria de " + cur + " +" + g + "% en 24h");
        }
        BigDecimal low = Money.dec(pl.getConfig().getString("analytics.alerts.treasury-low", "0"), BigDecimal.ZERO);
        if (low.signum() > 0 && treasury.compareTo(low) < 0 && !recentAlert(tx, "treasury-low-" + cur, now))
            Alerts.raise(tx, "WARN", "treasury-low-" + cur, "Tesorería de " + cur + " baja: " + Money.plain(cur, treasury));
        BigDecimal conc = Money.dec(pl.getConfig().getString("analytics.alerts.concentration-percent", "50"), BigDecimal.ZERO);
        if (conc.signum() > 0 && supply.signum() > 0 && topBal.multiply(BigDecimal.valueOf(100)).divide(supply, 2, RoundingMode.HALF_UP).compareTo(conc) > 0 && !recentAlert(tx, "concentration-" + cur, now))
            Alerts.raise(tx, "INFO", "concentration-" + cur, "Un jugador posee más del " + conc + "% de " + cur);
    }

    // ---------- reads ----------
    public CompletableFuture<Overview> overview(String cur) {
        return pl.db().tx(tx -> {
            Snap latest = Sql.one(tx, "SELECT * FROM analytics WHERE currency=? ORDER BY ts DESC LIMIT 1", AnalyticsService::snap, cur);
            if (latest == null) return new Overview(null, BigDecimal.ZERO, BigDecimal.ZERO, new ArrayList<>(), BigDecimal.ZERO);
            Snap old = Sql.one(tx, "SELECT * FROM analytics WHERE currency=? AND ts<=? ORDER BY ts DESC LIMIT 1", AnalyticsService::snap, cur, latest.ts() - 86_400_000L);
            if (old == null) old = Sql.one(tx, "SELECT * FROM analytics WHERE currency=? ORDER BY ts ASC LIMIT 1", AnalyticsService::snap, cur);
            BigDecimal infl = BigDecimal.ZERO;
            if (old != null && old.supply().signum() > 0) infl = latest.supply().subtract(old.supply()).multiply(BigDecimal.valueOf(100)).divide(old.supply(), 2, RoundingMode.HALF_UP);
            BigDecimal vol24 = BigDecimal.ZERO;
            for (String a : Sql.list(tx, "SELECT tx_volume FROM analytics WHERE currency=? AND ts>?", rs -> rs.getString(1), cur, latest.ts() - 86_400_000L)) vol24 = vol24.add(new BigDecimal(a));
            BigDecimal vel = latest.supply().signum() > 0 ? vol24.divide(latest.supply(), 4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            List<BigDecimal> hist = Sql.list(tx, "SELECT supply FROM analytics WHERE currency=? ORDER BY ts DESC LIMIT 30", rs -> new BigDecimal(rs.getString(1)), cur);
            java.util.Collections.reverse(hist);
            BigDecimal top = Sql.one(tx, "SELECT MAX(CAST(amount AS REAL)) FROM balances WHERE owner_type='PLAYER' AND currency=?", rs -> BigDecimal.valueOf(rs.getDouble(1)), cur);
            BigDecimal share = top == null || latest.supply().signum() <= 0 ? BigDecimal.ZERO : top.multiply(BigDecimal.valueOf(100)).divide(latest.supply(), 2, RoundingMode.HALF_UP);
            return new Overview(latest, infl, vel, hist, share);
        });
    }

    /** General counters. */
    public CompletableFuture<Map<String, String>> stats() {
        return pl.db().tx(tx -> {
            long now = System.currentTimeMillis();
            Map<String, String> m = new LinkedHashMap<>();
            m.put("Jugadores registrados", String.valueOf(Sql.count(tx, "SELECT COUNT(*) FROM players")));
            m.put("Transacciones (24h)", String.valueOf(Sql.count(tx, "SELECT COUNT(*) FROM transactions WHERE ts>?", now - 86_400_000L)));
            m.put("Transacciones (total)", String.valueOf(Sql.count(tx, "SELECT COUNT(*) FROM transactions")));
            m.put("Subastas activas", String.valueOf(Sql.count(tx, "SELECT COUNT(*) FROM auctions WHERE state='ACTIVE'")));
            m.put("Subastas vendidas", String.valueOf(Sql.count(tx, "SELECT COUNT(*) FROM auctions WHERE state='SOLD'")));
            m.put("Órdenes abiertas (bazaar)", String.valueOf(Sql.count(tx, "SELECT COUNT(*) FROM bazaar_orders WHERE state='OPEN'")));
            m.put("Órdenes abiertas (bolsa)", String.valueOf(Sql.count(tx, "SELECT COUNT(*) FROM stock_orders WHERE state='OPEN'")));
            m.put("Empresas activas", String.valueOf(Sql.count(tx, "SELECT COUNT(*) FROM companies WHERE active=1")));
            m.put("Empresas en bolsa", String.valueOf(Sql.count(tx, "SELECT COUNT(*) FROM companies WHERE active=1 AND listed=1")));
            m.put("Cuentas bancarias", String.valueOf(Sql.count(tx, "SELECT COUNT(*) FROM bank_accounts")));
            m.put("Objetos pendientes en buzones", String.valueOf(Sql.count(tx, "SELECT COUNT(*) FROM pending_items")));
            m.put("Alertas sin leer", String.valueOf(Sql.count(tx, "SELECT COUNT(*) FROM alerts WHERE ack=0")));
            return m;
        });
    }

    public CompletableFuture<Map<String, BigDecimal>> volumeByType(String cur, long since) {
        return pl.db().tx(tx -> {
            Map<String, BigDecimal> m = new LinkedHashMap<>();
            for (String[] r : Sql.list(tx, "SELECT type, amount FROM transactions WHERE ts>? AND currency=?", rs -> new String[]{rs.getString(1), rs.getString(2)}, since, cur))
                m.merge(r[0], new BigDecimal(r[1]), BigDecimal::add);
            List<Map.Entry<String, BigDecimal>> es = new ArrayList<>(m.entrySet());
            es.sort((a, b) -> b.getValue().compareTo(a.getValue()));
            Map<String, BigDecimal> out = new LinkedHashMap<>();
            for (int i = 0; i < Math.min(15, es.size()); i++) out.put(es.get(i).getKey(), es.get(i).getValue());
            return out;
        });
    }

    private static List<Rank> top(Map<String, BigDecimal> m, java.util.function.Function<String, String> names, int n) {
        List<Map.Entry<String, BigDecimal>> es = new ArrayList<>(m.entrySet());
        es.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        List<Rank> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, es.size()); i++) out.add(new Rank(es.get(i).getKey(), names.apply(es.get(i).getKey()), es.get(i).getValue()));
        return out;
    }

    private static Map<String, String> playerNames(com.economycore.core.Tx tx) throws java.sql.SQLException {
        Map<String, String> m = new HashMap<>();
        for (String[] r : Sql.list(tx, "SELECT uuid, name FROM players", rs -> new String[]{rs.getString(1), rs.getString(2)})) m.put(r[0], r[1]);
        return m;
    }

    /** Net worth = cash + owned bank accounts + stock holdings (same currency only). */
    public CompletableFuture<List<Rank>> netWorth(String cur) {
        return pl.db().tx(tx -> {
            Map<String, BigDecimal> w = new HashMap<>();
            for (String[] r : Sql.list(tx, "SELECT owner_id, amount FROM balances WHERE owner_type='PLAYER' AND currency=?", rs -> new String[]{rs.getString(1), rs.getString(2)}, cur))
                w.merge(r[0], new BigDecimal(r[1]), BigDecimal::add);
            for (String[] r : Sql.list(tx, "SELECT a.owner, b.amount FROM bank_accounts a JOIN balances b ON b.owner_type='BANK' AND b.owner_id=a.id AND b.currency=? WHERE a.currency=? AND a.owner IS NOT NULL", rs -> new String[]{rs.getString(1), rs.getString(2)}, cur, cur))
                if (w.containsKey(r[0])) w.merge(r[0], new BigDecimal(r[1]), BigDecimal::add);
            for (String[] r : Sql.list(tx, "SELECT h.owner, h.shares, c.price FROM holdings h JOIN companies c ON c.id=h.company WHERE h.shares>0 AND c.currency=?", rs -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}, cur))
                if (w.containsKey(r[0])) w.merge(r[0], new BigDecimal(r[2]).multiply(new BigDecimal(r[1])), BigDecimal::add);
            Map<String, String> names = playerNames(tx);
            return top(w, u -> names.getOrDefault(u, u.substring(0, 8)), 100);
        });
    }

    public CompletableFuture<List<Rank>> topTraders(String cur, long since) {
        return pl.db().tx(tx -> {
            Map<String, BigDecimal> w = new HashMap<>();
            for (String[] r : Sql.list(tx, "SELECT from_id, to_type, to_id, from_type, amount FROM transactions WHERE ts>? AND currency=? AND type NOT IN ('START','ADMIN_GIVE','ADMIN_TAKE','ADMIN_SET','API_DEPOSIT','API_WITHDRAW','API_TRANSFER','BANK_DEPOSIT','BANK_WITHDRAW','BANK_TRANSFER','BANK_INTEREST','BANK_FEE','BANK_CLOSE','SALARY','SUBSIDY','REWARD','PROJECT','ORDER_RESERVE','ORDER_REFUND','AH_REFUND','AH_BID','TREASURY_DEPOSIT','TREASURY_PAYOUT','COMPANY_CAPITAL','COMPANY_DEPOSIT','COMPANY_WITHDRAW','COMPANY_CLOSE','INCOME','WEALTH_TAX')",
                    rs -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)}, since, cur)) {
                BigDecimal a = new BigDecimal(r[4]);
                if (r[3].equals("PLAYER")) w.merge(r[0], a, BigDecimal::add);
                if (r[1].equals("PLAYER")) w.merge(r[2], a, BigDecimal::add);
            }
            Map<String, String> names = playerNames(tx);
            return top(w, u -> names.getOrDefault(u, u.length() > 8 ? u.substring(0, 8) : u), 100);
        });
    }

    // ---------- alerts ----------
    public CompletableFuture<List<AlertRow>> alerts() {
        return pl.db().tx(tx -> Sql.list(tx, "SELECT * FROM alerts ORDER BY ack ASC, id DESC LIMIT 300",
                rs -> new AlertRow(rs.getLong("id"), rs.getLong("ts"), rs.getString("level"), rs.getString("kind"), rs.getString("message"), rs.getInt("ack") == 1)));
    }

    public CompletableFuture<Void> ack(long id) { return pl.db().tx(tx -> { Sql.exec(tx, "UPDATE alerts SET ack=1 WHERE id=?", id); return null; }); }

    public CompletableFuture<Void> ackAll() { return pl.db().tx(tx -> { Sql.exec(tx, "UPDATE alerts SET ack=1 WHERE ack=0"); return null; }); }
}
