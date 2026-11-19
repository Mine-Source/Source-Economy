package com.economycore.core;

import com.economycore.api.LedgerEntry;
import com.economycore.api.PurchaseRecord;
import com.economycore.api.PurchaseState;

import java.sql.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Almacenamiento JDBC para SQLite y MySQL/MariaDB. El plugin aporta el ConnectionProvider (DriverManager o HikariCP).
 * SQLite: transacciones serializadas con un lock en proceso. MySQL: filas bloqueadas con SELECT ... FOR UPDATE y reintento ante deadlock.
 * Los upserts son UPDATE + INSERT (portables) dentro de la transacción.
 */
public final class JdbcStorage implements Storage {
    public enum Dialect { SQLITE, MYSQL }
    @FunctionalInterface public interface ConnectionProvider { Connection get() throws SQLException; }

    private final ConnectionProvider provider;
    private final Dialect dialect;
    private final ReentrantLock sqliteLock = new ReentrantLock();

    public JdbcStorage(ConnectionProvider provider, Dialect dialect) {
        this.provider = provider; this.dialect = dialect;
        try (Connection c = provider.get(); Statement s = c.createStatement()) {
            for (String ddl : schema(dialect)) {
                try { s.execute(ddl); }
                catch (SQLException e) { // CREATE INDEX no admite IF NOT EXISTS en MySQL: se ignora "ya existe"
                    String m = String.valueOf(e.getMessage()).toLowerCase();
                    if (!(ddl.startsWith("CREATE INDEX") && (m.contains("exists") || m.contains("duplicate")))) throw e;
                }
            }
        } catch (SQLException e) { throw new StorageException("No se pudo crear el esquema", e); }
    }

    /** Sentencias DDL (públicas para poder validarlas/auditarlas). */
    public static List<String> schema(Dialect d) {
        String id = d == Dialect.SQLITE ? "INTEGER PRIMARY KEY AUTOINCREMENT" : "BIGINT AUTO_INCREMENT PRIMARY KEY";
        String tail = d == Dialect.SQLITE ? "" : " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
        return List.of(
            "CREATE TABLE IF NOT EXISTS ec_balances(account VARCHAR(64) NOT NULL, currency VARCHAR(32) NOT NULL, amount BIGINT NOT NULL, PRIMARY KEY(account,currency))" + tail,
            "CREATE TABLE IF NOT EXISTS ec_operations(op_id VARCHAR(128) NOT NULL PRIMARY KEY, created_at BIGINT NOT NULL)" + tail,
            "CREATE TABLE IF NOT EXISTS ec_ledger(id " + id + ", op_id VARCHAR(128) NOT NULL, account VARCHAR(64) NOT NULL, currency VARCHAR(32) NOT NULL, delta BIGINT NOT NULL, balance_after BIGINT NOT NULL, reason VARCHAR(255) NOT NULL, ts BIGINT NOT NULL)" + tail,
            "CREATE INDEX ec_ledger_account ON ec_ledger(account, id)",
            "CREATE TABLE IF NOT EXISTS ec_purchases(id VARCHAR(128) NOT NULL PRIMARY KEY, market_id VARCHAR(64) NOT NULL, product_id VARCHAR(64) NOT NULL, buyer VARCHAR(64) NOT NULL, quantity INT NOT NULL, currency VARCHAR(32) NOT NULL, total BIGINT NOT NULL, tax BIGINT NOT NULL, payee VARCHAR(64) NOT NULL, state VARCHAR(24) NOT NULL, attempts INT NOT NULL, note VARCHAR(255) NOT NULL, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL)" + tail,
            "CREATE INDEX ec_purchases_state ON ec_purchases(state, updated_at)",
            "CREATE INDEX ec_purchases_buyer ON ec_purchases(buyer, created_at)",
            "CREATE TABLE IF NOT EXISTS ec_counters(k VARCHAR(190) NOT NULL PRIMARY KEY, v BIGINT NOT NULL)" + tail);
    }

    @Override public <T> T tx(Function<Tx, T> work) {
        SQLException last = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            if (dialect == Dialect.SQLITE) sqliteLock.lock();
            try (Connection c = provider.get()) {
                c.setAutoCommit(false);
                try {
                    T out = work.apply(new JdbcTx(c));
                    c.commit();
                    return out;
                } catch (RuntimeException e) {
                    try { c.rollback(); } catch (SQLException ignored) {}
                    Throwable cause = e instanceof UncheckedSql u ? u.getCause() : e.getCause();
                    if (cause instanceof SQLException se && retryable(se)) { last = se; continue; }
                    if (e instanceof UncheckedSql u) throw new StorageException("Error SQL", u.getCause());
                    throw e;
                } catch (SQLException e) {
                    try { c.rollback(); } catch (SQLException ignored) {}
                    if (retryable(e)) { last = e; continue; }
                    throw new StorageException("Error SQL", e);
                }
            } catch (SQLException e) {
                throw new StorageException("Conexión fallida", e);
            } finally { if (dialect == Dialect.SQLITE) sqliteLock.unlock(); }
        }
        throw new StorageException("Transacción abortada tras reintentos", last);
    }

    private static boolean retryable(SQLException e) {
        String st = e.getSQLState();
        return "40001".equals(st) || "40P01".equals(st) || e.getErrorCode() == 1213 || e.getErrorCode() == 1205;
    }
    private static boolean isDuplicate(SQLException e) {
        String st = e.getSQLState(), m = String.valueOf(e.getMessage()).toLowerCase();
        return (st != null && st.startsWith("23")) || m.contains("unique") || m.contains("duplicate");
    }
    @Override public void close() {}

    private static final class UncheckedSql extends RuntimeException { UncheckedSql(SQLException e) { super(e); } }

    private final class JdbcTx implements Tx {
        private final Connection c;
        private final String lockSuffix = dialect == Dialect.MYSQL ? " FOR UPDATE" : "";
        JdbcTx(Connection c) { this.c = c; }

        private PreparedStatement ps(String sql, Object... args) throws SQLException {
            PreparedStatement p = c.prepareStatement(sql);
            for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]);
            return p;
        }
        private <R> R q(String sql, SqlFn<ResultSet, R> f, Object... args) {
            try (PreparedStatement p = ps(sql, args); ResultSet rs = p.executeQuery()) { return f.apply(rs); }
            catch (SQLException e) { throw new UncheckedSql(e); }
        }
        private int u(String sql, Object... args) {
            try (PreparedStatement p = ps(sql, args)) { return p.executeUpdate(); } catch (SQLException e) { throw new UncheckedSql(e); }
        }

        public long balance(String a, String cur) {
            return q("SELECT amount FROM ec_balances WHERE account=? AND currency=?" + lockSuffix, rs -> rs.next() ? rs.getLong(1) : 0L, a, cur);
        }
        public void setBalance(String a, String cur, long v) {
            if (u("UPDATE ec_balances SET amount=? WHERE account=? AND currency=?", v, a, cur) == 0)
                u("INSERT INTO ec_balances(account,currency,amount) VALUES(?,?,?)", a, cur, v);
        }
        public long totalBalance(String cur) { return q("SELECT COALESCE(SUM(amount),0) FROM ec_balances WHERE currency=?", rs -> { rs.next(); return rs.getLong(1); }, cur); }
        public boolean hasOp(String id) { return q("SELECT 1 FROM ec_operations WHERE op_id=?", ResultSet::next, id); }
        public void claimOp(String id, long now) {
            try (PreparedStatement p = ps("INSERT INTO ec_operations(op_id,created_at) VALUES(?,?)", id, now)) { p.executeUpdate(); }
            catch (SQLException e) { if (isDuplicate(e)) throw new DuplicateOperationException(id); throw new UncheckedSql(e); }
        }
        public void addLedger(LedgerEntry e) {
            u("INSERT INTO ec_ledger(op_id,account,currency,delta,balance_after,reason,ts) VALUES(?,?,?,?,?,?,?)",
                e.opId(), e.account(), e.currency(), e.delta(), e.balanceAfter(), trunc(e.reason(), 255), e.timestamp());
        }
        public List<LedgerEntry> ledger(String account, int limit) {
            return q("SELECT op_id,account,currency,delta,balance_after,reason,ts FROM ec_ledger WHERE account=? ORDER BY id DESC LIMIT " + Math.max(1, limit), rs -> {
                List<LedgerEntry> l = new ArrayList<>();
                while (rs.next()) l.add(new LedgerEntry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getLong(5), rs.getString(6), rs.getLong(7)));
                return l;
            }, account);
        }
        private static final String PCOLS = "id,market_id,product_id,buyer,quantity,currency,total,tax,payee,state,attempts,note,created_at,updated_at";
        private PurchaseRecord row(ResultSet r) throws SQLException {
            return new PurchaseRecord(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getInt(5), r.getString(6),
                r.getLong(7), r.getLong(8), r.getString(9), PurchaseState.valueOf(r.getString(10)), r.getInt(11), r.getString(12), r.getLong(13), r.getLong(14));
        }
        public Optional<PurchaseRecord> purchase(String id) {
            return q("SELECT " + PCOLS + " FROM ec_purchases WHERE id=?" + lockSuffix, rs -> rs.next() ? Optional.of(row(rs)) : Optional.<PurchaseRecord>empty(), id);
        }
        public void putPurchase(PurchaseRecord r) {
            int n = u("UPDATE ec_purchases SET market_id=?,product_id=?,buyer=?,quantity=?,currency=?,total=?,tax=?,payee=?,state=?,attempts=?,note=?,created_at=?,updated_at=? WHERE id=?",
                r.marketId(), r.productId(), r.buyer(), r.quantity(), r.currency(), r.total(), r.tax(), r.payee(), r.state().name(), r.attempts(), trunc(r.note(), 255), r.createdAt(), r.updatedAt(), r.id());
            if (n == 0) u("INSERT INTO ec_purchases(" + PCOLS + ") VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                r.id(), r.marketId(), r.productId(), r.buyer(), r.quantity(), r.currency(), r.total(), r.tax(), r.payee(), r.state().name(), r.attempts(), trunc(r.note(), 255), r.createdAt(), r.updatedAt());
        }
        public List<PurchaseRecord> purchasesIn(Set<PurchaseState> states, long before, int limit) {
            if (states.isEmpty()) return List.of();
            String in = String.join(",", Collections.nCopies(states.size(), "?"));
            Object[] args = new Object[states.size() + 1]; int i = 0;
            for (PurchaseState s : states) args[i++] = s.name();
            args[i] = before;
            return q("SELECT " + PCOLS + " FROM ec_purchases WHERE state IN (" + in + ") AND updated_at<? ORDER BY updated_at LIMIT " + Math.max(1, limit), rs -> {
                List<PurchaseRecord> l = new ArrayList<>(); while (rs.next()) l.add(row(rs)); return l;
            }, args);
        }
        public List<PurchaseRecord> purchasesOf(String buyer, int limit) {
            return q("SELECT " + PCOLS + " FROM ec_purchases WHERE buyer=? ORDER BY created_at DESC LIMIT " + Math.max(1, limit), rs -> {
                List<PurchaseRecord> l = new ArrayList<>(); while (rs.next()) l.add(row(rs)); return l;
            }, buyer);
        }
        public OptionalLong counter(String key) {
            return q("SELECT v FROM ec_counters WHERE k=?" + lockSuffix, rs -> rs.next() ? OptionalLong.of(rs.getLong(1)) : OptionalLong.empty(), key);
        }
        public void setCounter(String key, long v) {
            if (u("UPDATE ec_counters SET v=? WHERE k=?", v, key) == 0) u("INSERT INTO ec_counters(k,v) VALUES(?,?)", key, v);
        }
    }

    @FunctionalInterface private interface SqlFn<A, R> { R apply(A a) throws SQLException; }
    private static String trunc(String s, int n) { return s == null ? "" : s.length() <= n ? s : s.substring(0, n); }
}
