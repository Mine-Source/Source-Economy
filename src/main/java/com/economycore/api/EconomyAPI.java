package com.economycore.api;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Public API, registered in Bukkit's ServicesManager. Reads are cached and safe on the main thread; writes are asynchronous. */
public interface EconomyAPI {
    String defaultCurrency();

    Collection<String> currencies();

    String format(String currency, BigDecimal amount);

    /** Last known committed balance of an online player (0 when offline/unknown). */
    BigDecimal getCachedBalance(UUID player, String currency);

    CompletableFuture<BigDecimal> getBalance(UUID player, String currency);

    CompletableFuture<Boolean> has(UUID player, String currency, BigDecimal amount);

    /** Creates money (source: system). */
    CompletableFuture<Boolean> deposit(UUID player, String currency, BigDecimal amount, String reason);

    /** Destroys money (sink: system). Fails when funds are insufficient. */
    CompletableFuture<Boolean> withdraw(UUID player, String currency, BigDecimal amount, String reason);

    /** Atomic player to player transfer without fees. */
    CompletableFuture<Boolean> transfer(UUID from, UUID to, String currency, BigDecimal amount, String reason);
}
