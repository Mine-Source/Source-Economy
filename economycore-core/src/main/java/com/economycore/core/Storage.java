package com.economycore.core;

import com.economycore.api.LedgerEntry;
import com.economycore.api.PurchaseRecord;
import com.economycore.api.PurchaseState;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;

/** Almacenamiento transaccional. tx() es atómico: si la función lanza, no se persiste nada. */
public interface Storage extends AutoCloseable {
    <T> T tx(Function<Tx, T> work);
    @Override void close();

    interface Tx {
        long balance(String account, String currency);
        void setBalance(String account, String currency, long amount);
        long totalBalance(String currency);
        boolean hasOp(String opId);
        /** Registra la operación como aplicada. Lanza DuplicateOperationException si ya existía. */
        void claimOp(String opId, long now);
        void addLedger(LedgerEntry e);
        List<LedgerEntry> ledger(String account, int limit);
        Optional<PurchaseRecord> purchase(String id);
        void putPurchase(PurchaseRecord r);
        List<PurchaseRecord> purchasesIn(Set<PurchaseState> states, long updatedBefore, int limit);
        List<PurchaseRecord> purchasesOf(String buyer, int limit);
        OptionalLong counter(String key);
        void setCounter(String key, long value);
    }

    class StorageException extends RuntimeException {
        public StorageException(String m, Throwable c) { super(m, c); }
    }
    class DuplicateOperationException extends RuntimeException {
        public DuplicateOperationException(String op) { super("operación duplicada: " + op); }
    }
}
