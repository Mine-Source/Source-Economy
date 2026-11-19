package com.economycore.core;

import com.economycore.api.*;
import com.economycore.api.Result.Code;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Servicio económico central: saldos long exactos, idempotencia por opId, libro mayor y transacciones atómicas. */
public final class EconomyEngine implements EconomyService {
    private final Storage storage; private final CurrencyRegistry currencies; private final Clock clock;

    public EconomyEngine(Storage storage, CurrencyRegistry currencies, Clock clock) {
        this.storage = storage; this.currencies = currencies; this.clock = clock;
    }
    public Collection<Currency> currencies() { return currencies.all(); }
    public Optional<Currency> currency(String id) { return currencies.get(id); }
    public long balance(String account, String currency) { return storage.tx(tx -> tx.balance(account, currency)); }
    public List<LedgerEntry> history(String account, int limit) { return storage.tx(tx -> tx.ledger(account, limit)); }

    public Result deposit(String opId, String account, Money m, String reason) { return run(opId, null, account, m, reason); }
    public Result withdraw(String opId, String account, Money m, String reason) { return run(opId, account, null, m, reason); }
    public Result transfer(String opId, String from, String to, Money m, String reason) { return run(opId, from, to, m, reason); }

    private Result run(String opId, String from, String to, Money m, String reason) {
        if (opId == null || opId.isBlank() || opId.length() > 120) return Accounts.result(Code.INVALID_AMOUNT);
        if (!currencies.has(m.currency())) return Accounts.result(Code.UNKNOWN_CURRENCY);
        try {
            long now = clock.millis();
            return Accounts.result(storage.tx(tx -> Accounts.move(tx, opId, from, to, m.currency(), m.minor(), reason == null ? "" : reason, now)));
        } catch (Storage.DuplicateOperationException e) {
            return Accounts.result(Code.DUPLICATE);
        }
    }
    /** Suma total en circulación de una moneda (auditoría). */
    public long totalSupply(String currency) { return storage.tx(tx -> tx.totalBalance(currency)); }
}
