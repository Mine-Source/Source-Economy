package com.economycore.bank;

import com.economycore.core.Acct;
import com.economycore.core.Audit;
import com.economycore.core.EcoException;
import com.economycore.core.EconomyCore;
import com.economycore.core.Ledger;
import com.economycore.core.Money;
import com.economycore.core.Players;
import com.economycore.core.Sql;
import com.economycore.core.Tx;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class BankService {
    private final EconomyCore pl;

    public BankService(EconomyCore pl) { this.pl = pl; }

    public record Account(String id, String name, String type, String owner, String currency, long created, boolean interest, BigDecimal balance, String role) {
        public boolean canWithdraw() { return "OWNER".equals(role) || "ADMIN".equals(role); }
    }

    static final String SEL = "SELECT a.*, COALESCE((SELECT amount FROM balances b WHERE b.owner_type='BANK' AND b.owner_id=a.id AND b.currency=a.currency),'0') AS bal, ";

    static Account map(ResultSet rs) throws SQLException {
        return new Account(rs.getString("id"), rs.getString("name"), rs.getString("type"), rs.getString("owner"), rs.getString("currency"), rs.getLong("created"),
                rs.getInt("interest_enabled") == 1, new BigDecimal(rs.getString("bal")), rs.getString("role"));
    }

    public CompletableFuture<List<Account>> mine(UUID u) {
        return pl.db().tx(tx -> Sql.list(tx, SEL + "m.role AS role FROM bank_accounts a JOIN bank_members m ON m.account=a.id WHERE m.uuid=? ORDER BY a.created", BankService::map, u.toString()));
    }

    public CompletableFuture<List<Account>> all() {
        return pl.db().tx(tx -> Sql.list(tx, SEL + "'ADMIN' AS role FROM bank_accounts a ORDER BY a.created", BankService::map));
    }

    public CompletableFuture<Account> get(String id, UUID viewer, boolean admin) {
        return pl.db().tx(tx -> load(tx, id, viewer, admin));
    }

    private Account load(Tx tx, String id, UUID viewer, boolean admin) throws SQLException {
        Account a = Sql.one(tx, SEL + "COALESCE((SELECT role FROM bank_members m WHERE m.account=a.id AND m.uuid=?), '') AS role FROM bank_accounts a WHERE a.id=?", BankService::map, viewer.toString(), id);
        if (a == null) throw new EcoException("bank-not-found", id);
        if (admin && a.role().isEmpty()) return new Account(a.id(), a.name(), a.type(), a.owner(), a.currency(), a.created(), a.interest(), a.balance(), "ADMIN");
        if (a.role().isEmpty()) throw new EcoException("no-permission");
        return a;
    }

    public CompletableFuture<String> create(UUID owner, String ownerName, String name, String type, String currency) {
        int max = pl.getConfig().getInt("bank.max-accounts", 5);
        BigDecimal fee = Money.dec(pl.getConfig().getString("bank.creation-fee", "0"), BigDecimal.ZERO);
        return pl.db().tx(tx -> {
            if (Sql.count(tx, "SELECT COUNT(*) FROM bank_accounts WHERE owner=?", owner.toString()) >= max) throw new EcoException("bank-limit", max);
            if (fee.signum() > 0) Ledger.transfer(tx, Acct.player(owner), Acct.TREASURY, currency, fee, "BANK_FEE", name);
            Long mx = Sql.one(tx, "SELECT MAX(CAST(SUBSTR(id,2) AS INTEGER)) FROM bank_accounts", rs -> rs.getLong(1));
            String id = "B" + ((mx == null || mx < 1000 ? 1000 : mx) + 1);
            Sql.exec(tx, "INSERT INTO bank_accounts(id,name,type,owner,currency,created) VALUES(?,?,?,?,?,?)", id, name, type, owner, currency, System.currentTimeMillis());
            Sql.exec(tx, "INSERT INTO bank_members(account,uuid,name,role) VALUES(?,?,?,'OWNER')", id, owner, ownerName);
            return id;
        });
    }

    public CompletableFuture<Void> deposit(UUID u, String id, BigDecimal amt) {
        return pl.db().tx(tx -> {
            Account a = load(tx, id, u, false);
            Ledger.transfer(tx, Acct.player(u), Acct.bank(id), a.currency(), amt, "BANK_DEPOSIT", a.name());
            return null;
        });
    }

    public CompletableFuture<Void> withdraw(UUID u, String id, BigDecimal amt) {
        return pl.db().tx(tx -> {
            Account a = load(tx, id, u, false);
            if (!a.canWithdraw()) throw new EcoException("bank-no-withdraw");
            Ledger.transfer(tx, Acct.bank(id), Acct.player(u), a.currency(), amt, "BANK_WITHDRAW", a.name());
            return null;
        });
    }

    /** target is another bank account id (B1234) or a player name. */
    public CompletableFuture<String> transfer(UUID u, String id, String target, BigDecimal amt) {
        return pl.db().tx(tx -> {
            Account a = load(tx, id, u, false);
            if (!a.canWithdraw()) throw new EcoException("bank-no-withdraw");
            Account other = target.matches("(?i)B\\d+") ? Sql.one(tx, SEL + "'' AS role FROM bank_accounts a WHERE a.id=?", BankService::map, target.toUpperCase()) : null;
            if (other != null) {
                if (!other.currency().equals(a.currency())) throw new EcoException("bank-currency-mismatch");
                if (other.id().equals(a.id())) throw new EcoException("pay-self");
                Ledger.transfer(tx, Acct.bank(id), Acct.bank(other.id()), a.currency(), amt, "BANK_TRANSFER", a.name() + " -> " + other.name());
                return other.name();
            }
            Players.Ref r = Players.require(tx, target);
            Ledger.transfer(tx, Acct.bank(id), Acct.player(r.uuid()), a.currency(), amt, "BANK_TRANSFER", a.name() + " -> " + r.name());
            return r.name();
        });
    }

    public CompletableFuture<List<String[]>> members(String id) {
        return pl.db().tx(tx -> Sql.list(tx, "SELECT uuid,name,role FROM bank_members WHERE account=? ORDER BY role", rs -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}, id));
    }

    public CompletableFuture<Void> addMember(UUID actor, String id, String name) {
        return pl.db().tx(tx -> {
            Account a = load(tx, id, actor, false);
            if (!a.role().equals("OWNER")) throw new EcoException("no-permission");
            if (!a.type().equals("SHARED")) throw new EcoException("bank-not-shared");
            Players.Ref r = Players.require(tx, name);
            Sql.exec(tx, "INSERT OR IGNORE INTO bank_members(account,uuid,name,role) VALUES(?,?,?,'MEMBER')", id, r.uuid(), r.name());
            return null;
        });
    }

    public CompletableFuture<Void> setRole(UUID actor, String id, String member, String role) {
        return pl.db().tx(tx -> {
            Account a = load(tx, id, actor, false);
            if (!a.role().equals("OWNER")) throw new EcoException("no-permission");
            if (member.equals(a.owner())) throw new EcoException("bank-owner-fixed");
            if (role == null) Sql.exec(tx, "DELETE FROM bank_members WHERE account=? AND uuid=?", id, member);
            else Sql.exec(tx, "UPDATE bank_members SET role=? WHERE account=? AND uuid=?", role, id, member);
            return null;
        });
    }

    /** Owner closes the account; remaining funds go to the owner's wallet. */
    public CompletableFuture<Void> close(UUID actor, String id, boolean admin) {
        return pl.db().tx(tx -> {
            Account a = load(tx, id, actor, admin);
            if (!a.role().equals("OWNER") && !admin) throw new EcoException("no-permission");
            if (a.balance().signum() > 0) Ledger.transfer(tx, Acct.bank(id), Acct.player(a.owner()), a.currency(), a.balance(), "BANK_CLOSE", a.name());
            Sql.exec(tx, "DELETE FROM bank_members WHERE account=?", id);
            Sql.exec(tx, "DELETE FROM bank_accounts WHERE id=?", id);
            if (admin) Audit.log(tx, actor.toString(), "bank.close", id, a.name());
            return null;
        });
    }

    public CompletableFuture<Void> toggleInterest(String actor, String id) {
        return pl.db().tx(tx -> {
            Sql.exec(tx, "UPDATE bank_accounts SET interest_enabled=1-interest_enabled WHERE id=?", id);
            Audit.log(tx, actor, "bank.interest.toggle", id, "");
            return null;
        });
    }

    public record Line(long ts, String type, boolean incoming, BigDecimal amount, String note) {}

    public CompletableFuture<List<Line>> statement(String id) {
        return pl.db().tx(tx -> Sql.list(tx, "SELECT ts,type,from_type,from_id,amount,note FROM transactions WHERE (from_type='BANK' AND from_id=?) OR (to_type='BANK' AND to_id=?) ORDER BY id DESC LIMIT 200",
                rs -> new Line(rs.getLong(1), rs.getString(2), !(rs.getString(3).equals("BANK") && rs.getString(4).equals(id)), new BigDecimal(rs.getString(5)), rs.getString(6)), id, id));
    }

    /** Periodic interest. Source is the system (mint) or the treasury, per config. */
    public void payInterest() {
        pl.db().tx(tx -> {
            BigDecimal pp = Money.dec(pl.getConfig().getString("bank.interest.personal-percent", "0.5"), BigDecimal.ZERO);
            BigDecimal sp = Money.dec(pl.getConfig().getString("bank.interest.shared-percent", "0.25"), BigDecimal.ZERO);
            BigDecimal cap = Money.dec(pl.getConfig().getString("bank.interest.max-balance", "1000000"), null);
            boolean treasury = pl.getConfig().getString("bank.interest.source", "SYSTEM").equalsIgnoreCase("TREASURY");
            for (Account a : Sql.list(tx, SEL + "'' AS role FROM bank_accounts a WHERE a.interest_enabled=1", BankService::map)) {
                if (a.balance().signum() <= 0) continue;
                BigDecimal base = cap != null ? a.balance().min(cap) : a.balance();
                BigDecimal interest = Money.scale(a.currency(), Money.pct(base, a.type().equals("SHARED") ? sp : pp));
                if (interest.signum() <= 0) continue;
                Acct src = treasury ? Acct.TREASURY : Acct.SYSTEM;
                if (treasury && Ledger.bal(tx, src, a.currency()).compareTo(interest) < 0) continue;
                Ledger.transfer(tx, src, Acct.bank(a.id()), a.currency(), interest, "BANK_INTEREST", a.name());
            }
            return null;
        });
    }
}
