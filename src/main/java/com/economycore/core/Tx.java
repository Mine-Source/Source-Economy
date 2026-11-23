package com.economycore.core;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One database transaction. Cache updates and after-commit hooks only apply when the commit succeeds. */
public final class Tx {
    private final Connection c;
    private final Map<String, BigDecimal> cache = new HashMap<>();
    private final List<Runnable> after = new ArrayList<>();

    Tx(Connection c) { this.c = c; }

    public Connection c() { return c; }

    void cache(String key, BigDecimal v) { cache.put(key, v); }

    /** Runs on the main thread once the transaction has committed. */
    public void afterCommit(Runnable r) { after.add(r); }

    void finish() {
        Ledger.CACHE.putAll(cache);
        for (Runnable r : after) Tasks.sync(r);
    }
}
