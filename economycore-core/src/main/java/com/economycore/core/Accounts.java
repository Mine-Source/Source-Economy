package com.economycore.core;

import com.economycore.api.LedgerEntry;
import com.economycore.api.Result;
import com.economycore.api.Result.Code;

/** Movimientos de dinero dentro de una transacción de Storage. from/to null = emisión/destrucción (solo operaciones administrativas). */
final class Accounts {
    private Accounts() {}

    static Code move(Storage.Tx tx, String opId, String from, String to, String currency, long amount, String reason, long now) {
        if (amount <= 0) return Code.INVALID_AMOUNT;
        if (from != null && from.equals(to)) return Code.SAME_ACCOUNT;
        if (tx.hasOp(opId)) return Code.DUPLICATE;
        long fromBal = 0, toBal = 0;
        if (from != null) { fromBal = tx.balance(from, currency); if (fromBal < amount) return Code.INSUFFICIENT_FUNDS; }
        if (to != null) {
            toBal = tx.balance(to, currency);
            if (toBal > Long.MAX_VALUE - amount) return Code.OVERFLOW;
        }
        tx.claimOp(opId, now);
        if (from != null) {
            tx.setBalance(from, currency, fromBal - amount);
            tx.addLedger(new LedgerEntry(opId, from, currency, -amount, fromBal - amount, reason, now));
        }
        if (to != null) {
            tx.setBalance(to, currency, toBal + amount);
            tx.addLedger(new LedgerEntry(opId, to, currency, amount, toBal + amount, reason, now));
        }
        return Code.OK;
    }
    static Result result(Code c) { return new Result(c); }
}
