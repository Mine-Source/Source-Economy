package com.economycore.eco;

import com.economycore.core.Acct;
import com.economycore.core.Audit;
import com.economycore.core.Currency;
import com.economycore.core.EcoException;
import com.economycore.core.EconomyCore;
import com.economycore.core.Ledger;
import com.economycore.core.Money;
import com.economycore.core.Players;
import com.economycore.core.Sql;
import com.economycore.core.Taxes;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class EconomyService {
    private final EconomyCore pl;

    public EconomyService(EconomyCore pl) { this.pl = pl; }

    public record PayResult(UUID to, String toName, String currency, BigDecimal amount, BigDecimal fee, BigDecimal tax) {}
    public record TxRow(long id, long ts, String type, String fromType, String fromId, String toType, String toId,
                        String currency, BigDecimal amount, BigDecimal fee, BigDecimal tax, String note, String fromName, String toName) {}
    public record Top(String uuid, String name, BigDecimal amount) {}
    public record AuditRow(long id, long ts, String actor, String action, String target, String detail) {}

    public CompletableFuture<PayResult> pay(Player from, String toName, String curId, BigDecimal amount) {
        UUID fromId = from.getUniqueId();
        UUID taxSubject = Taxes.subject(from);
        String fromName = from.getName();
        return pl.db().tx(tx -> {
            Currency cur = pl.currencies().get(curId);
            if (cur == null) throw new EcoException("unknown-currency", curId);
            if (!cur.transferable()) throw new EcoException("currency-not-transferable", cur.name());
            BigDecimal amt = Money.scale(cur.id(), amount);
            BigDecimal min = Money.dec(pl.getConfig().getString("economy.min-transfer", "0.01"), BigDecimal.ZERO);
            if (amt.signum() <= 0 || amt.compareTo(min) < 0) throw new EcoException("invalid-amount");
            Players.Ref to = Players.require(tx, toName);
            if (to.uuid().equals(fromId)) throw new EcoException("pay-self");
            BigDecimal feePct = Money.dec(pl.getConfig().getString("economy.transfer-fee-percent", "0"), BigDecimal.ZERO);
            BigDecimal fee = feePct.signum() > 0 ? Money.scale(cur.id(), Money.pct(amt, feePct)) : BigDecimal.ZERO;
            BigDecimal tax = Money.scale(cur.id(), Taxes.compute(tx, "TRANSFER", cur.id(), amt, taxSubject));
            Ledger.add(tx, Acct.player(fromId), cur.id(), amt.add(fee).add(tax).negate());
            Ledger.add(tx, Acct.player(to.uuid()), cur.id(), amt);
            if (fee.add(tax).signum() > 0) Ledger.add(tx, Acct.TREASURY, cur.id(), fee.add(tax));
            Ledger.record(tx, "PAY", Acct.player(fromId), Acct.player(to.uuid()), cur.id(), amt, fee, tax, "");
            tx.afterCommit(() -> {
                Player tp = Bukkit.getPlayer(to.uuid());
                if (tp != null) com.economycore.core.Msg.send(tp, "pay-received", Money.fmt(cur.id(), amt), fromName);
            });
            return new PayResult(to.uuid(), to.name(), cur.id(), amt, fee, tax);
        });
    }

    /** op: give | take | set */
    public CompletableFuture<BigDecimal> admin(String actor, String op, String targetName, String curId, BigDecimal amount) {
        return pl.db().tx(tx -> {
            Currency cur = pl.currencies().get(curId);
            if (cur == null) throw new EcoException("unknown-currency", curId);
            Players.Ref t = Players.require(tx, targetName);
            Acct a = Acct.player(t.uuid());
            BigDecimal amt = Money.scale(cur.id(), amount);
            if (amt.signum() < 0 || (!op.equals("set") && amt.signum() == 0)) throw new EcoException("invalid-amount");
            switch (op) {
                case "give" -> Ledger.transfer(tx, Acct.SYSTEM, a, cur.id(), amt, "ADMIN_GIVE", actor);
                case "take" -> Ledger.transfer(tx, a, Acct.SYSTEM, cur.id(), amt, "ADMIN_TAKE", actor);
                case "set" -> {
                    BigDecimal now = Ledger.bal(tx, a, cur.id());
                    int c = amt.compareTo(now);
                    if (c > 0) Ledger.transfer(tx, Acct.SYSTEM, a, cur.id(), amt.subtract(now), "ADMIN_SET", actor);
                    else if (c < 0) Ledger.transfer(tx, a, Acct.SYSTEM, cur.id(), now.subtract(amt), "ADMIN_SET", actor);
                }
                default -> throw new EcoException("internal-error");
            }
            Audit.log(tx, actor, "balance." + op, t.name(), cur.id() + " " + amt.toPlainString());
            return Ledger.bal(tx, a, cur.id());
        });
    }

    public CompletableFuture<Map<String, BigDecimal>> balances(UUID id) {
        return pl.db().tx(tx -> {
            Map<String, BigDecimal> m = new LinkedHashMap<>();
            for (Currency c : pl.currencies().all()) m.put(c.id(), Ledger.bal(tx, Acct.player(id), c.id()));
            return m;
        });
    }

    public CompletableFuture<Map<String, BigDecimal>> balancesByName(String name) {
        return pl.db().tx(tx -> {
            Players.Ref r = Players.require(tx, name);
            Map<String, BigDecimal> m = new LinkedHashMap<>();
            for (Currency c : pl.currencies().all()) m.put(c.id(), Ledger.bal(tx, Acct.player(r.uuid()), c.id()));
            return m;
        });
    }

    private static TxRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TxRow(rs.getLong("id"), rs.getLong("ts"), rs.getString("type"), rs.getString("from_type"), rs.getString("from_id"),
                rs.getString("to_type"), rs.getString("to_id"), rs.getString("currency"), Sql.decOr0(rs, "amount"), Sql.decOr0(rs, "fee"), Sql.decOr0(rs, "tax"), rs.getString("note"), rs.getString("fname"), rs.getString("tname"));
    }

    public CompletableFuture<List<TxRow>> history(UUID id, int limit) {
        return pl.db().tx(tx -> Sql.list(tx, "SELECT t.*, pf.name AS fname, pt.name AS tname FROM transactions t LEFT JOIN players pf ON pf.uuid=t.from_id LEFT JOIN players pt ON pt.uuid=t.to_id WHERE t.from_id=? OR t.to_id=? ORDER BY t.id DESC LIMIT ?", EconomyService::row, id.toString(), id.toString(), limit));
    }

    public CompletableFuture<List<TxRow>> recent(int limit) {
        return pl.db().tx(tx -> Sql.list(tx, "SELECT t.*, pf.name AS fname, pt.name AS tname FROM transactions t LEFT JOIN players pf ON pf.uuid=t.from_id LEFT JOIN players pt ON pt.uuid=t.to_id ORDER BY t.id DESC LIMIT ?", EconomyService::row, limit));
    }

    public CompletableFuture<List<AuditRow>> audit(int limit) {
        return pl.db().tx(tx -> Sql.list(tx, "SELECT * FROM audit ORDER BY id DESC LIMIT ?",
                rs -> new AuditRow(rs.getLong("id"), rs.getLong("ts"), rs.getString("actor"), rs.getString("action"), rs.getString("target"), rs.getString("detail")), limit));
    }

    public CompletableFuture<List<Top>> top(String cur, int n) {
        return pl.db().tx(tx -> Sql.list(tx, "SELECT b.owner_id, COALESCE(p.name, b.owner_id) AS name, b.amount FROM balances b LEFT JOIN players p ON p.uuid=b.owner_id WHERE b.owner_type='PLAYER' AND b.currency=? ORDER BY CAST(b.amount AS REAL) DESC LIMIT ?",
                rs -> new Top(rs.getString(1), rs.getString(2), new BigDecimal(rs.getString(3))), cur, n));
    }
}
