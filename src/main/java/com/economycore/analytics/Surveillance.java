package com.economycore.analytics;

import com.economycore.core.Alerts;
import com.economycore.core.EconomyCore;
import com.economycore.core.Players;
import com.economycore.core.Sql;
import com.economycore.core.Tx;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Detects stock price manipulation: wash trading between two accounts and price moves driven by one account. */
public final class Surveillance {
    private final EconomyCore pl;

    public Surveillance(EconomyCore pl) { this.pl = pl; }

    private record Trade(String company, String buyer, String seller, BigDecimal price, int qty, long ts) {}

    public void run() {
        if (!pl.getConfig().getBoolean("analytics.manipulation.enabled", true)) return;
        int windowMin = Math.max(5, pl.getConfig().getInt("analytics.manipulation.window-minutes", 60));
        int washMin = Math.max(2, pl.getConfig().getInt("analytics.manipulation.wash-min-trades", 6));
        BigDecimal movePct = new BigDecimal(pl.getConfig().getString("analytics.manipulation.price-move-percent", "15"));
        BigDecimal dominance = new BigDecimal(pl.getConfig().getString("analytics.manipulation.dominance-percent", "60"));
        int minTrades = Math.max(3, pl.getConfig().getInt("analytics.manipulation.min-trades", 5));
        long now = System.currentTimeMillis();
        pl.db().tx(tx -> {
            List<Trade> trades = Sql.list(tx, "SELECT company,buyer,seller,price,qty,ts FROM stock_trades WHERE ts>? AND seller<>'IPO' ORDER BY ts ASC LIMIT 20000",
                    rs -> new Trade(rs.getString(1), rs.getString(2), rs.getString(3), new BigDecimal(rs.getString(4)), rs.getInt(5), rs.getLong(6)), now - windowMin * 60_000L);
            Map<String, List<Trade>> byCompany = new HashMap<>();
            for (Trade t : trades) byCompany.computeIfAbsent(t.company(), k -> new java.util.ArrayList<>()).add(t);
            for (Map.Entry<String, List<Trade>> e : byCompany.entrySet()) {
                String co = e.getKey();
                List<Trade> list = e.getValue();
                String ticker = Sql.one(tx, "SELECT ticker FROM companies WHERE id=?", rs -> rs.getString(1), co);
                if (ticker == null) ticker = co.toUpperCase();
                washTrading(tx, ticker, list, washMin, now);
                priceDriven(tx, ticker, list, minTrades, movePct, dominance, now);
            }
            return null;
        });
    }

    private void washTrading(Tx tx, String ticker, List<Trade> list, int washMin, long now) throws SQLException {
        Map<String, int[]> pairs = new HashMap<>(); // key -> {count, qty, dirMask}
        Map<String, String[]> who = new HashMap<>();
        for (Trade t : list) {
            boolean ab = t.buyer().compareTo(t.seller()) < 0;
            String a = ab ? t.buyer() : t.seller();
            String b = ab ? t.seller() : t.buyer();
            String k = a + "|" + b;
            int[] v = pairs.computeIfAbsent(k, x -> new int[3]);
            v[0]++;
            v[1] += t.qty();
            v[2] |= ab ? 1 : 2;
            who.putIfAbsent(k, new String[]{a, b});
        }
        for (Map.Entry<String, int[]> e : pairs.entrySet()) {
            int[] v = e.getValue();
            boolean roundTrip = v[2] == 3;
            if (v[0] < washMin || (!roundTrip && v[0] < washMin * 2)) continue;
            String[] ab = who.get(e.getKey());
            String kind = "manip-wash-" + ticker + "-" + sh(ab[0]) + "-" + sh(ab[1]);
            if (recent(tx, kind, now)) continue;
            Alerts.raise(tx, "WARN", kind, "Posible wash trading en " + ticker + ": " + Players.name(tx, ab[0]) + " <-> " + Players.name(tx, ab[1])
                    + " (" + v[0] + " operaciones, " + v[1] + " acciones" + (roundTrip ? ", en ambos sentidos" : "") + ")");
        }
    }

    private void priceDriven(Tx tx, String ticker, List<Trade> list, int minTrades, BigDecimal movePct, BigDecimal dominance, long now) throws SQLException {
        if (list.size() < minTrades) return;
        BigDecimal first = list.get(0).price();
        BigDecimal last = list.get(list.size() - 1).price();
        if (first.signum() <= 0) return;
        BigDecimal change = last.subtract(first).multiply(BigDecimal.valueOf(100)).divide(first, 2, RoundingMode.HALF_UP);
        if (change.abs().compareTo(movePct) < 0) return;
        Map<String, Long> vol = new HashMap<>();
        long total = 0;
        for (Trade t : list) {
            vol.merge(t.buyer(), (long) t.qty(), Long::sum);
            vol.merge(t.seller(), (long) t.qty(), Long::sum);
            total += t.qty();
        }
        if (total <= 0) return;
        for (Map.Entry<String, Long> e : vol.entrySet()) {
            BigDecimal share = BigDecimal.valueOf(e.getValue() * 100L).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
            if (share.compareTo(dominance) < 0) continue;
            String kind = "manip-price-" + ticker + "-" + sh(e.getKey());
            if (recent(tx, kind, now)) continue;
            Alerts.raise(tx, "WARN", kind, "Posible manipulación de precio en " + ticker + ": " + Players.name(tx, e.getKey()) + " participa en el " + share
                    + "% del volumen y el precio se movió " + (change.signum() > 0 ? "+" : "") + change + "%");
        }
    }

    private static String sh(String s) { return s.length() > 8 ? s.substring(0, 8) : s; }

    private boolean recent(Tx tx, String kind, long now) throws SQLException {
        return Sql.count(tx, "SELECT COUNT(*) FROM alerts WHERE kind=? AND ts>?", kind, now - 6 * 3_600_000L) > 0;
    }
}
