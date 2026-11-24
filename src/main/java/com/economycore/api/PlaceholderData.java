package com.economycore.api;

import com.economycore.core.EconomyCore;
import com.economycore.core.Ledger;
import com.economycore.core.Money;
import com.economycore.core.Sql;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Snapshot of rankings, stock prices and treasury refreshed in the background so placeholders never touch the database. */
public final class PlaceholderData {
    public record Top(String name, BigDecimal amount) {}
    public record Stock(String ticker, BigDecimal price, BigDecimal prev, long volume24) {
        public BigDecimal changePct() {
            return prev == null || prev.signum() <= 0 ? BigDecimal.ZERO : price.subtract(prev).multiply(BigDecimal.valueOf(100)).divide(prev, 2, RoundingMode.HALF_UP);
        }
    }

    private final EconomyCore pl;
    public final Map<String, List<Top>> tops = new ConcurrentHashMap<>();
    public final Map<String, Integer> ranks = new ConcurrentHashMap<>();       // cur:uuid -> rank
    public final Map<String, Stock> stocks = new ConcurrentHashMap<>();        // TICKER -> stock
    public final Map<String, BigDecimal> treasury = new ConcurrentHashMap<>();
    public volatile BigDecimal index = BigDecimal.ZERO;

    public PlaceholderData(EconomyCore pl) { this.pl = pl; }

    public void refresh() {
        List<UUID> online = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) online.add(p.getUniqueId());
        List<String> curs = new ArrayList<>();
        pl.currencies().all().forEach(c -> curs.add(c.id()));
        Map<UUID, Map<String, BigDecimal>> bals = new HashMap<>();
        for (UUID u : online) {
            Map<String, BigDecimal> m = new HashMap<>();
            for (String c : curs) m.put(c, Ledger.cached(u, c));
            bals.put(u, m);
        }
        pl.db().tx(tx -> {
            for (String c : curs) {
                tops.put(c, Sql.list(tx, "SELECT p.name,b.amount FROM balances b JOIN players p ON p.uuid=b.owner_id WHERE b.owner_type='PLAYER' AND b.currency=? ORDER BY CAST(b.amount AS REAL) DESC LIMIT 10",
                        rs -> new Top(rs.getString(1), new BigDecimal(rs.getString(2))), c));
                BigDecimal tr = Ledger.bal(tx, com.economycore.core.Acct.TREASURY, c);
                treasury.put(c, tr);
                for (UUID u : online) {
                    long above = Sql.count(tx, "SELECT COUNT(*) FROM balances WHERE owner_type='PLAYER' AND currency=? AND CAST(amount AS REAL) > CAST(? AS REAL)", c, bals.get(u).get(c).toPlainString());
                    ranks.put(c + ":" + u, (int) above + 1);
                }
            }
            long since = System.currentTimeMillis() - 86_400_000L;
            Map<String, Long> vol = new HashMap<>();
            for (String[] r : Sql.list(tx, "SELECT company,SUM(qty) FROM stock_trades WHERE ts>? GROUP BY company", rs -> new String[]{rs.getString(1), String.valueOf(rs.getLong(2))}, since)) vol.put(r[0], Long.parseLong(r[1]));
            Map<String, Stock> fresh = new HashMap<>();
            for (String[] r : Sql.list(tx, "SELECT id,ticker,price,prev_price FROM companies WHERE listed=1", rs -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)})) {
                fresh.put(r[1].toUpperCase(), new Stock(r[1].toUpperCase(), new BigDecimal(r[2]), r[3] == null ? null : new BigDecimal(r[3]), vol.getOrDefault(r[0], 0L)));
            }
            stocks.keySet().retainAll(fresh.keySet());
            stocks.putAll(fresh);
            String idx = Sql.one(tx, "SELECT value FROM meta WHERE key='stock_index_last'", rs -> rs.getString(1));
            index = Money.dec(idx, BigDecimal.valueOf(1000));
            return null;
        });
    }
}
