package com.economycore.treasury;

import com.economycore.core.Acct;
import com.economycore.core.Alerts;
import com.economycore.core.Audit;
import com.economycore.core.EcoException;
import com.economycore.core.EconomyCore;
import com.economycore.core.Ledger;
import com.economycore.core.Money;
import com.economycore.core.Msg;
import com.economycore.core.Players;
import com.economycore.core.Sql;
import com.economycore.core.Taxes;
import com.economycore.core.Tx;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class TreasuryService {
    private final EconomyCore pl;

    public record Budget(long id, String name, String currency, BigDecimal allocated, BigDecimal spent) {
        public BigDecimal left() { return allocated.subtract(spent); }
    }

    public record Subsidy(long id, String name, String targetType, String targetId, String currency, BigDecimal amount, int intervalMin, long lastPaid, boolean active, long budgetId) {}

    public record Reward(long id, String name, String trigger, String key, String currency, BigDecimal amount, String source, int cooldown, boolean active) {}

    public record Project(long id, String name, String description, String currency, BigDecimal cost, String payeeType, String payeeId, String creator, long created, long votingEnds,
                          String status, int yes, int no) {}

    private final Map<String, Long> rewardCooldown = new ConcurrentHashMap<>();
    private volatile List<Reward> rewardCache = new ArrayList<>();

    public TreasuryService(EconomyCore pl) { this.pl = pl; }

    // ---------- mappers ----------
    private static Budget budget(ResultSet rs) throws SQLException {
        return new Budget(rs.getLong("id"), rs.getString("name"), rs.getString("currency"), Sql.decOr0(rs, "allocated"), Sql.decOr0(rs, "spent"));
    }

    private static Subsidy subsidy(ResultSet rs) throws SQLException {
        return new Subsidy(rs.getLong("id"), rs.getString("name"), rs.getString("target_type"), rs.getString("target_id"), rs.getString("currency"), Sql.decOr0(rs, "amount"),
                rs.getInt("interval_min"), rs.getLong("last_paid"), rs.getInt("active") == 1, rs.getLong("budget_id"));
    }

    private static Reward reward(ResultSet rs) throws SQLException {
        return new Reward(rs.getLong("id"), rs.getString("name"), rs.getString("trigger_type"), rs.getString("trigger_key"), rs.getString("currency"), Sql.decOr0(rs, "amount"),
                rs.getString("source"), rs.getInt("cooldown_sec"), rs.getInt("active") == 1);
    }

    private static Project project(ResultSet rs) throws SQLException {
        return new Project(rs.getLong("id"), rs.getString("name"), rs.getString("description") == null ? "" : rs.getString("description"), rs.getString("currency"), Sql.decOr0(rs, "cost"),
                rs.getString("payee_type"), rs.getString("payee_id"), rs.getString("creator"), rs.getLong("created"), rs.getLong("voting_ends"), rs.getString("status"),
                rs.getInt("yes_votes"), rs.getInt("no_votes"));
    }

    // ---------- treasury overview ----------
    public CompletableFuture<Map<String, BigDecimal>> balances() {
        return pl.db().tx(tx -> {
            Map<String, BigDecimal> m = new LinkedHashMap<>();
            for (var c : pl.currencies().all()) m.put(c.id(), Ledger.bal(tx, Acct.TREASURY, c.id()));
            return m;
        });
    }

    /** Collected by the treasury during the last `sinceMs`, grouped by transaction type (all currencies of `cur`). */
    public CompletableFuture<Map<String, BigDecimal>> collection(String cur, long since) {
        return pl.db().tx(tx -> {
            Map<String, BigDecimal> m = new LinkedHashMap<>();
            for (String[] r : Sql.list(tx, "SELECT type, fee, tax, amount, to_type FROM transactions WHERE ts>=? AND currency=? AND (to_type='TREASURY' OR from_type='TREASURY' OR CAST(fee AS REAL)>0 OR CAST(tax AS REAL)>0)",
                    rs -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)}, since, cur)) {
                BigDecimal fee = Money.dec(r[1], BigDecimal.ZERO).add(Money.dec(r[2], BigDecimal.ZERO));
                if (r[4].equals("TREASURY")) m.merge(r[0] + " (directo)", Money.dec(r[3], BigDecimal.ZERO), BigDecimal::add);
                else if (fee.signum() > 0) m.merge(r[0], fee, BigDecimal::add);
            }
            return m;
        });
    }

    public CompletableFuture<Void> deposit(Player p, String cur, BigDecimal amt) {
        UUID u = p.getUniqueId();
        return pl.db().tx(tx -> {
            Ledger.transfer(tx, Acct.player(u), Acct.TREASURY, cur, amt, "TREASURY_DEPOSIT", p.getName());
            return null;
        });
    }

    /** Admin: pays an arbitrary player from the treasury (optionally charging a budget). */
    public CompletableFuture<Void> payout(Player admin, String target, String cur, BigDecimal amt, long budgetId, String note) {
        String an = admin.getName();
        return pl.db().tx(tx -> {
            Players.Ref r = Players.require(tx, target);
            spend(tx, budgetId, cur, amt);
            Ledger.transfer(tx, Acct.TREASURY, Acct.player(r.uuid()), cur, amt, "TREASURY_PAYOUT", note);
            Audit.log(tx, an, "treasury.payout", r.name(), Money.plain(cur, amt) + " " + note);
            return null;
        });
    }

    private void spend(Tx tx, long budgetId, String cur, BigDecimal amt) throws SQLException {
        if (budgetId <= 0) return;
        Budget b = Sql.one(tx, "SELECT * FROM treasury_budgets WHERE id=?", TreasuryService::budget, budgetId);
        if (b == null) throw new EcoException("budget-gone");
        if (!b.currency().equals(cur)) throw new EcoException("budget-currency");
        if (b.left().compareTo(amt) < 0) throw new EcoException("budget-exceeded");
        Sql.exec(tx, "UPDATE treasury_budgets SET spent=? WHERE id=?", b.spent().add(amt), budgetId);
    }

    // ---------- budgets ----------
    public CompletableFuture<List<Budget>> budgets() {
        return pl.db().tx(tx -> Sql.list(tx, "SELECT * FROM treasury_budgets ORDER BY name", TreasuryService::budget));
    }

    public CompletableFuture<Void> createBudget(Player p, String name, String cur, BigDecimal allocated) {
        return pl.db().tx(tx -> {
            if (name.isBlank() || allocated.signum() <= 0) throw new EcoException("invalid-amount");
            if (Sql.count(tx, "SELECT COUNT(*) FROM treasury_budgets WHERE name=? COLLATE NOCASE", name) > 0) throw new EcoException("budget-exists");
            Sql.exec(tx, "INSERT INTO treasury_budgets(name,currency,allocated,spent,created) VALUES(?,?,?,'0',?)", name, cur, Money.scale(cur, allocated), System.currentTimeMillis());
            Audit.log(tx, p.getName(), "budget.create", name, Money.plain(cur, allocated));
            return null;
        });
    }

    public CompletableFuture<Void> setBudget(Player p, long id, BigDecimal allocated, boolean resetSpent) {
        return pl.db().tx(tx -> {
            Budget b = Sql.one(tx, "SELECT * FROM treasury_budgets WHERE id=?", TreasuryService::budget, id);
            if (b == null) throw new EcoException("budget-gone");
            if (allocated.signum() <= 0) throw new EcoException("invalid-amount");
            Sql.exec(tx, "UPDATE treasury_budgets SET allocated=?, spent=? WHERE id=?", Money.scale(b.currency(), allocated), resetSpent ? BigDecimal.ZERO : b.spent(), id);
            Audit.log(tx, p.getName(), "budget.set", b.name(), Money.plain(b.currency(), allocated));
            return null;
        });
    }

    public CompletableFuture<Void> deleteBudget(Player p, long id) {
        return pl.db().tx(tx -> {
            Sql.exec(tx, "UPDATE subsidies SET budget_id=0 WHERE budget_id=?", id);
            Sql.exec(tx, "DELETE FROM treasury_budgets WHERE id=?", id);
            Audit.log(tx, p.getName(), "budget.delete", String.valueOf(id), "");
            return null;
        });
    }

    // ---------- subsidies ----------
    public CompletableFuture<List<Subsidy>> subsidies() {
        return pl.db().tx(tx -> Sql.list(tx, "SELECT * FROM subsidies ORDER BY id DESC", TreasuryService::subsidy));
    }

    public CompletableFuture<Void> createSubsidy(Player p, String targetType, String target, String cur, BigDecimal amount, int intervalMin, long budgetId) {
        return pl.db().tx(tx -> {
            if (amount.signum() <= 0 || intervalMin <= 0) throw new EcoException("invalid-amount");
            String tid, label;
            switch (targetType) {
                case "PLAYER" -> { Players.Ref r = Players.require(tx, target); tid = r.uuid().toString(); label = r.name(); }
                case "COMPANY" -> {
                    if (Sql.count(tx, "SELECT COUNT(*) FROM companies WHERE id=? AND active=1", target.toLowerCase()) == 0) throw new EcoException("company-gone");
                    tid = target.toLowerCase(); label = tid;
                }
                case "BANK" -> {
                    if (Sql.count(tx, "SELECT COUNT(*) FROM bank_accounts WHERE id=?", target) == 0) throw new EcoException("bank-gone");
                    tid = target; label = tid;
                }
                default -> throw new EcoException("invalid-number");
            }
            Sql.exec(tx, "INSERT INTO subsidies(name,target_type,target_id,currency,amount,interval_min,last_paid,active,budget_id) VALUES(?,?,?,?,?,?,?,1,?)",
                    targetType + ":" + label, targetType, tid, cur, Money.scale(cur, amount), intervalMin, System.currentTimeMillis(), budgetId);
            Audit.log(tx, p.getName(), "subsidy.create", label, Money.plain(cur, amount) + "/" + intervalMin + "min");
            return null;
        });
    }

    public CompletableFuture<Void> toggleSubsidy(long id) {
        return pl.db().tx(tx -> { Sql.exec(tx, "UPDATE subsidies SET active=1-active WHERE id=?", id); return null; });
    }

    public CompletableFuture<Void> deleteSubsidy(Player p, long id) {
        return pl.db().tx(tx -> { Sql.exec(tx, "DELETE FROM subsidies WHERE id=?", id); Audit.log(tx, p.getName(), "subsidy.delete", String.valueOf(id), ""); return null; });
    }

    /** Periodic job: pays due subsidies from the treasury. */
    public void paySubsidies() {
        pl.db().tx(tx -> {
            long now = System.currentTimeMillis();
            for (Subsidy s : Sql.list(tx, "SELECT * FROM subsidies WHERE active=1", TreasuryService::subsidy)) {
                if (s.lastPaid() + s.intervalMin() * 60_000L > now) continue;
                try {
                    Sql.exec(tx, "SAVEPOINT sub");
                    spend(tx, s.budgetId(), s.currency(), s.amount());
                    Acct to = new Acct(s.targetType(), s.targetId());
                    Ledger.transfer(tx, Acct.TREASURY, to, s.currency(), s.amount(), "SUBSIDY", s.name());
                    Sql.exec(tx, "UPDATE subsidies SET last_paid=? WHERE id=?", now, s.id());
                    Sql.exec(tx, "RELEASE sub");
                    if (s.targetType().equals("COMPANY")) Sql.exec(tx, "INSERT INTO company_ledger(company,ts,kind,amount,note) VALUES(?,?,'SUBSIDY',?,?)", s.targetId(), now, s.amount(), "subsidio");
                    if (s.targetType().equals("PLAYER")) {
                        UUID pu = UUID.fromString(s.targetId());
                        tx.afterCommit(() -> { Player pp = Bukkit.getPlayer(pu); if (pp != null) Msg.send(pp, "subsidy-received", Money.fmt(s.currency(), s.amount())); });
                    }
                } catch (EcoException e) {
                    Sql.exec(tx, "ROLLBACK TO sub");
                    Sql.exec(tx, "RELEASE sub");
                    Sql.exec(tx, "UPDATE subsidies SET last_paid=? WHERE id=?", now, s.id());
                    Alerts.raise(tx, "WARN", "subsidy-failed", s.name() + ": " + e.getMessage());
                }
            }
            return null;
        });
    }

    // ---------- rewards ----------
    public CompletableFuture<List<Reward>> rewards() {
        return pl.db().tx(tx -> Sql.list(tx, "SELECT * FROM rewards ORDER BY id DESC", TreasuryService::reward));
    }

    public CompletableFuture<Void> reloadRewards() {
        return pl.db().tx(tx -> Sql.list(tx, "SELECT * FROM rewards WHERE active=1", TreasuryService::reward)).thenAccept(l -> rewardCache = l);
    }

    public CompletableFuture<Void> createReward(Player p, String name, String trigger, String key, String cur, BigDecimal amount, String source, int cooldown) {
        return pl.db().tx(tx -> {
            if (amount.signum() <= 0 || cooldown < 0) throw new EcoException("invalid-amount");
            if (!List.of("JOIN", "KILL_MOB", "BREAK").contains(trigger)) throw new EcoException("invalid-number");
            Sql.exec(tx, "INSERT INTO rewards(name,trigger_type,trigger_key,currency,amount,source,cooldown_sec,active) VALUES(?,?,?,?,?,?,?,1)",
                    name, trigger, key.toUpperCase(), cur, Money.scale(cur, amount), source, cooldown);
            Audit.log(tx, p.getName(), "reward.create", name, trigger + ":" + key);
            return null;
        }).thenCompose(v -> reloadRewards());
    }

    public CompletableFuture<Void> toggleReward(long id) {
        return pl.db().tx(tx -> { Sql.exec(tx, "UPDATE rewards SET active=1-active WHERE id=?", id); return null; }).thenCompose(v -> reloadRewards());
    }

    public CompletableFuture<Void> deleteReward(Player p, long id) {
        return pl.db().tx(tx -> {
            Sql.exec(tx, "DELETE FROM rewards WHERE id=?", id);
            Sql.exec(tx, "DELETE FROM reward_cooldowns WHERE reward_id=?", id);
            Audit.log(tx, p.getName(), "reward.delete", String.valueOf(id), "");
            return null;
        }).thenCompose(v -> reloadRewards());
    }

    /** Called from game events (main thread). key is entity type / material / "" for JOIN. */
    public void trigger(Player p, String trigger, String key) {
        List<Reward> rs = rewardCache;
        if (rs.isEmpty()) return;
        long now = System.currentTimeMillis();
        UUID u = p.getUniqueId();
        for (Reward r : rs) {
            if (!r.trigger().equals(trigger)) continue;
            if (!r.key().equals("*") && !r.key().equalsIgnoreCase(key)) continue;
            String ck = r.id() + ":" + u;
            Long last = rewardCooldown.get(ck);
            if (last != null && now - last < Math.max(r.cooldown(), 1) * 1000L) continue;
            rewardCooldown.put(ck, now);
            pl.db().tx(tx -> {
                Long dbLast = Sql.one(tx, "SELECT last FROM reward_cooldowns WHERE reward_id=? AND player=?", rs2 -> rs2.getLong(1), r.id(), u.toString());
                if (dbLast != null && now - dbLast < r.cooldown() * 1000L) return null;
                Acct from = r.source().equals("SYSTEM") ? Acct.SYSTEM : Acct.TREASURY;
                try {
                    Ledger.transfer(tx, from, Acct.player(u), r.currency(), r.amount(), "REWARD", r.name());
                } catch (EcoException e) {
                    return null;
                }
                Sql.exec(tx, "INSERT INTO reward_cooldowns(reward_id,player,last) VALUES(?,?,?) ON CONFLICT(reward_id,player) DO UPDATE SET last=excluded.last", r.id(), u.toString(), now);
                tx.afterCommit(() -> { Player pp = Bukkit.getPlayer(u); if (pp != null) Msg.send(pp, "reward-received", r.name(), Money.fmt(r.currency(), r.amount())); });
                return null;
            });
        }
    }

    // ---------- projects & votes ----------
    public CompletableFuture<List<Project>> projects() {
        return pl.db().tx(tx -> Sql.list(tx, "SELECT * FROM treasury_projects ORDER BY CASE status WHEN 'VOTING' THEN 0 WHEN 'APPROVED' THEN 1 ELSE 2 END, id DESC LIMIT 300", TreasuryService::project));
    }

    public CompletableFuture<Void> createProject(Player p, boolean admin, String name, String desc, String cur, BigDecimal cost, String payeeName, int votingHours) {
        UUID u = p.getUniqueId();
        return pl.db().tx(tx -> {
            if (name.isBlank() || cost.signum() <= 0 || votingHours <= 0) throw new EcoException("invalid-amount");
            if (!admin) {
                if (!pl.getConfig().getBoolean("treasury.allow-player-proposals", true)) throw new EcoException("no-permission");
                if (Sql.count(tx, "SELECT COUNT(*) FROM treasury_projects WHERE creator=? AND status='VOTING'", u.toString()) >= pl.getConfig().getInt("treasury.max-open-proposals", 2)) throw new EcoException("project-limit");
            }
            Players.Ref payee = admin && payeeName != null && !payeeName.isBlank() ? Players.require(tx, payeeName) : new Players.Ref(u, p.getName());
            long now = System.currentTimeMillis();
            Sql.exec(tx, "INSERT INTO treasury_projects(name,description,currency,cost,payee_type,payee_id,creator,created,voting_ends,status) VALUES(?,?,?,?,'PLAYER',?,?,?,?,'VOTING')",
                    name, desc, cur, Money.scale(cur, cost), payee.uuid().toString(), u.toString(), now, now + votingHours * 3_600_000L);
            return null;
        });
    }

    public CompletableFuture<Void> vote(Player p, long id, boolean yes) {
        UUID u = p.getUniqueId();
        return pl.db().tx(tx -> {
            Project pr = Sql.one(tx, "SELECT * FROM treasury_projects WHERE id=?", TreasuryService::project, id);
            if (pr == null) throw new EcoException("project-gone");
            if (!pr.status().equals("VOTING") || pr.votingEnds() < System.currentTimeMillis()) throw new EcoException("project-closed");
            Sql.exec(tx, "INSERT INTO treasury_votes(project_id,voter,vote,ts) VALUES(?,?,?,?) ON CONFLICT(project_id,voter) DO UPDATE SET vote=excluded.vote, ts=excluded.ts", id, u.toString(), yes ? 1 : 0, System.currentTimeMillis());
            Sql.exec(tx, "UPDATE treasury_projects SET yes_votes=(SELECT COUNT(*) FROM treasury_votes WHERE project_id=? AND vote=1), no_votes=(SELECT COUNT(*) FROM treasury_votes WHERE project_id=? AND vote=0) WHERE id=?", id, id, id);
            return null;
        });
    }

    public CompletableFuture<Void> decide(Player admin, long id, boolean approve) {
        return pl.db().tx(tx -> {
            Project pr = Sql.one(tx, "SELECT * FROM treasury_projects WHERE id=?", TreasuryService::project, id);
            if (pr == null) throw new EcoException("project-gone");
            if (pr.status().equals("FUNDED") || pr.status().equals("REJECTED")) throw new EcoException("project-closed");
            if (approve) { Sql.exec(tx, "UPDATE treasury_projects SET status='APPROVED' WHERE id=?", id); fund(tx, pr); }
            else Sql.exec(tx, "UPDATE treasury_projects SET status='REJECTED' WHERE id=?", id);
            Audit.log(tx, admin.getName(), approve ? "project.approve" : "project.reject", pr.name(), "");
            return null;
        });
    }

    private void fund(Tx tx, Project pr) throws SQLException {
        try {
            Sql.exec(tx, "SAVEPOINT fund");
            Ledger.transfer(tx, Acct.TREASURY, new Acct(pr.payeeType(), pr.payeeId()), pr.currency(), pr.cost(), "PROJECT", pr.name());
            Sql.exec(tx, "UPDATE treasury_projects SET status='FUNDED' WHERE id=?", pr.id());
            Sql.exec(tx, "RELEASE fund");
        } catch (EcoException e) {
            Sql.exec(tx, "ROLLBACK TO fund");
            Sql.exec(tx, "RELEASE fund");
            Alerts.raise(tx, "WARN", "project-unfunded", pr.name() + ": " + e.getMessage());
        }
    }

    /** Periodic: closes finished votes and retries funding of approved projects. */
    public void resolveProjects() {
        pl.db().tx(tx -> {
            long now = System.currentTimeMillis();
            int minVotes = pl.getConfig().getInt("treasury.min-votes", 1);
            for (Project pr : Sql.list(tx, "SELECT * FROM treasury_projects WHERE status IN ('VOTING','APPROVED')", TreasuryService::project)) {
                if (pr.status().equals("APPROVED")) { fund(tx, pr); continue; }
                if (pr.votingEnds() > now) continue;
                if (pr.yes() > pr.no() && pr.yes() + pr.no() >= minVotes) { Sql.exec(tx, "UPDATE treasury_projects SET status='APPROVED' WHERE id=?", pr.id()); fund(tx, pr); }
                else Sql.exec(tx, "UPDATE treasury_projects SET status='REJECTED' WHERE id=?", pr.id());
            }
            return null;
        });
    }

    // ---------- wealth tax ----------
    public void wealthTax() {
        pl.db().tx(tx -> {
            for (var c : pl.currencies().all()) {
                if (Taxes.brackets(tx, "WEALTH", c.id()).isEmpty()) continue;
                for (String[] r : Sql.list(tx, "SELECT owner_id, amount FROM balances WHERE owner_type='PLAYER' AND currency=?", rs -> new String[]{rs.getString(1), rs.getString(2)}, c.id())) {
                    BigDecimal bal = new BigDecimal(r[1]);
                    UUID u = UUID.fromString(r[0]);
                    BigDecimal tax = Money.scale(c.id(), Taxes.compute(tx, "WEALTH", c.id(), bal, u));
                    if (tax.signum() <= 0 || tax.compareTo(bal) > 0) continue;
                    Ledger.add(tx, Acct.player(u), c.id(), tax.negate());
                    Ledger.add(tx, Acct.TREASURY, c.id(), tax);
                    Ledger.record(tx, "WEALTH_TAX", Acct.player(u), Acct.TREASURY, c.id(), tax, BigDecimal.ZERO, tax, "wealth tax");
                }
            }
            return null;
        });
    }
}
