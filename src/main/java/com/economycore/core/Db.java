package com.economycore.core;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

/** SQLite access. A single writer thread serialises every transaction, which makes each one atomic and race free. */
public final class Db {
    private final EconomyCore plugin;
    private final ExecutorService exec;
    private Connection conn;
    private File file;

    public Db(EconomyCore plugin) {
        this.plugin = plugin;
        this.exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "EconomyCore-DB");
            t.setDaemon(false);
            return t;
        });
    }

    public File backupDir() {
        File d = new File(plugin.getDataFolder(), "backups");
        d.mkdirs();
        return d;
    }

    public void open() throws Exception {
        File dir = new File(plugin.getDataFolder(), "data");
        dir.mkdirs();
        file = new File(dir, "economy.db");
        Class.forName("org.sqlite.JDBC");
        conn = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement s = conn.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=NORMAL");
            s.execute("PRAGMA foreign_keys=OFF");
            s.execute("PRAGMA busy_timeout=5000");
        }
        Migrations.run(this, conn);
    }

    /** Blocking access to the raw connection; only valid during startup before async use begins. */
    public Connection raw() { return conn; }

    public <T> CompletableFuture<T> tx(SqlFn<T> fn) {
        CompletableFuture<T> f = new CompletableFuture<>();
        try {
            exec.execute(() -> runTx(fn, f));
        } catch (RejectedExecutionException e) {
            f.completeExceptionally(e);
        }
        return f;
    }

    private <T> void runTx(SqlFn<T> fn, CompletableFuture<T> f) {
        Tx tx = new Tx(conn);
        try {
            conn.setAutoCommit(false);
            T r = fn.apply(tx);
            conn.commit();
            tx.finish();
            f.complete(r);
        } catch (Throwable t) {
            try { conn.rollback(); } catch (SQLException ignored) {}
            if (!(t instanceof EcoException)) plugin.getLogger().log(Level.SEVERE, "Database error", t);
            f.completeExceptionally(t);
        } finally {
            try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }

    /** Runs a transaction and delivers the result (or error) on the main thread. */
    public <T> void run(SqlFn<T> fn, Consumer<T> ok, Consumer<Throwable> err) {
        tx(fn).whenComplete((r, t) -> Tasks.sync(() -> {
            if (t != null) err.accept(unwrap(t));
            else ok.accept(r);
        }));
    }

    public static Throwable unwrap(Throwable t) {
        while ((t instanceof java.util.concurrent.CompletionException || t instanceof java.util.concurrent.ExecutionException) && t.getCause() != null) t = t.getCause();
        return t;
    }

    /** Creates a consistent snapshot using VACUUM INTO (safe with WAL). */
    public CompletableFuture<File> backup(String label) {
        CompletableFuture<File> f = new CompletableFuture<>();
        try {
            exec.execute(() -> {
                try {
                    File out = new File(backupDir(), label + "-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".db");
                    try (Statement s = conn.createStatement()) {
                        s.execute("VACUUM INTO '" + out.getAbsolutePath().replace("'", "''") + "'");
                    }
                    rotate();
                    f.complete(out);
                } catch (Throwable t) {
                    plugin.getLogger().log(Level.WARNING, "Backup failed", t);
                    f.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            f.completeExceptionally(e);
        }
        return f;
    }

    /** Used by migrations during open(), already on the calling thread. */
    void backupNow(String label) {
        try {
            File out = new File(backupDir(), label + "-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".db");
            try (Statement s = conn.createStatement()) {
                s.execute("VACUUM INTO '" + out.getAbsolutePath().replace("'", "''") + "'");
            }
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Pre-migration backup failed", t);
        }
    }

    private void rotate() {
        int keep = plugin.getConfig().getInt("database.backup.keep", 20);
        File[] files = backupDir().listFiles((d, n) -> n.startsWith("economy-") && n.endsWith(".db"));
        if (files == null || files.length <= keep) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < files.length - keep; i++) files[i].delete();
    }

    public void shutdown() {
        exec.shutdown();
        try {
            if (!exec.awaitTermination(20, TimeUnit.SECONDS)) exec.shutdownNow();
        } catch (InterruptedException e) {
            exec.shutdownNow();
            Thread.currentThread().interrupt();
        }
        try { if (conn != null) conn.close(); } catch (SQLException ignored) {}
    }
}
