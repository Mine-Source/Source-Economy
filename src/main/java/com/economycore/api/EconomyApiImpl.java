package com.economycore.api;

import com.economycore.core.Acct;
import com.economycore.core.EconomyCore;
import com.economycore.core.Ledger;
import com.economycore.core.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class EconomyApiImpl implements EconomyAPI {
    private final EconomyCore pl;

    public EconomyApiImpl(EconomyCore pl) { this.pl = pl; }

    private String cur(String c) { return pl.currencies().getOrDefault(c).id(); }

    @Override public String defaultCurrency() { return pl.currencies().defId(); }

    @Override public Collection<String> currencies() {
        Collection<String> l = new ArrayList<>();
        pl.currencies().all().forEach(c -> l.add(c.id()));
        return l;
    }

    @Override public String format(String currency, BigDecimal amount) { return Money.plain(cur(currency), amount); }

    @Override public BigDecimal getCachedBalance(UUID player, String currency) { return Ledger.cached(player, cur(currency)); }

    @Override public CompletableFuture<BigDecimal> getBalance(UUID player, String currency) {
        return pl.db().tx(tx -> Ledger.bal(tx, Acct.player(player), cur(currency)));
    }

    @Override public CompletableFuture<Boolean> has(UUID player, String currency, BigDecimal amount) {
        return getBalance(player, currency).thenApply(b -> b.compareTo(amount) >= 0);
    }

    @Override public CompletableFuture<Boolean> deposit(UUID player, String currency, BigDecimal amount, String reason) {
        return pl.db().tx(tx -> { Ledger.transfer(tx, Acct.SYSTEM, Acct.player(player), cur(currency), amount, "API_DEPOSIT", reason); return true; })
                .exceptionally(t -> false);
    }

    @Override public CompletableFuture<Boolean> withdraw(UUID player, String currency, BigDecimal amount, String reason) {
        return pl.db().tx(tx -> { Ledger.transfer(tx, Acct.player(player), Acct.SYSTEM, cur(currency), amount, "API_WITHDRAW", reason); return true; })
                .exceptionally(t -> false);
    }

    @Override public CompletableFuture<Boolean> transfer(UUID from, UUID to, String currency, BigDecimal amount, String reason) {
        return pl.db().tx(tx -> { Ledger.transfer(tx, Acct.player(from), Acct.player(to), cur(currency), amount, "API_TRANSFER", reason); return true; })
                .exceptionally(t -> false);
    }
}
