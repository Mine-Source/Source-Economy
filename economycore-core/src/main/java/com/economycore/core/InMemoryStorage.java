package com.economycore.core;

import com.economycore.api.LedgerEntry;
import com.economycore.api.PurchaseRecord;
import com.economycore.api.PurchaseState;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/** Implementación en memoria (pruebas y modo sin persistencia). Serializa transacciones y revierte ante excepciones. */
public final class InMemoryStorage implements Storage {
    private final ReentrantLock lock = new ReentrantLock();
    private Map<String, Long> balances = new HashMap<>();
    private Set<String> ops = new HashSet<>();
    private List<LedgerEntry> ledger = new ArrayList<>();
    private Map<String, PurchaseRecord> purchases = new LinkedHashMap<>();
    private Map<String, Long> counters = new HashMap<>();

    @Override public <T> T tx(Function<Tx, T> work) {
        lock.lock();
        Map<String, Long> b = new HashMap<>(balances); Set<String> o = new HashSet<>(ops);
        List<LedgerEntry> l = new ArrayList<>(ledger); Map<String, PurchaseRecord> p = new LinkedHashMap<>(purchases);
        Map<String, Long> c = new HashMap<>(counters);
        try {
            return work.apply(new MemTx());
        } catch (RuntimeException | Error e) {
            balances = b; ops = o; ledger = l; purchases = p; counters = c;
            throw e;
        } finally { lock.unlock(); }
    }
    @Override public void close() {}

    private final class MemTx implements Tx {
        private String k(String a, String c) { return a + "\u0000" + c; }
        public long balance(String a, String c) { return balances.getOrDefault(k(a, c), 0L); }
        public void setBalance(String a, String c, long v) { balances.put(k(a, c), v); }
        public long totalBalance(String c) {
            long s = 0; for (var e : balances.entrySet()) if (e.getKey().endsWith("\u0000" + c)) s = Math.addExact(s, e.getValue()); return s;
        }
        public boolean hasOp(String id) { return ops.contains(id); }
        public void claimOp(String id, long now) { if (!ops.add(id)) throw new DuplicateOperationException(id); }
        public void addLedger(LedgerEntry e) { ledger.add(e); }
        public List<LedgerEntry> ledger(String account, int limit) {
            List<LedgerEntry> out = new ArrayList<>();
            for (int i = ledger.size() - 1; i >= 0 && out.size() < limit; i--) if (ledger.get(i).account().equals(account)) out.add(ledger.get(i));
            return out;
        }
        public Optional<PurchaseRecord> purchase(String id) { return Optional.ofNullable(purchases.get(id)); }
        public void putPurchase(PurchaseRecord r) { purchases.put(r.id(), r); }
        public List<PurchaseRecord> purchasesIn(Set<PurchaseState> st, long before, int limit) {
            List<PurchaseRecord> out = new ArrayList<>();
            for (PurchaseRecord r : purchases.values()) { if (out.size() >= limit) break; if (st.contains(r.state()) && r.updatedAt() < before) out.add(r); }
            return out;
        }
        public List<PurchaseRecord> purchasesOf(String buyer, int limit) {
            List<PurchaseRecord> all = new ArrayList<>(purchases.values()), out = new ArrayList<>();
            for (int i = all.size() - 1; i >= 0 && out.size() < limit; i--) if (all.get(i).buyer().equals(buyer)) out.add(all.get(i));
            return out;
        }
        public OptionalLong counter(String key) { Long v = counters.get(key); return v == null ? OptionalLong.empty() : OptionalLong.of(v); }
        public void setCounter(String key, long v) { counters.put(key, v); }
    }
}
