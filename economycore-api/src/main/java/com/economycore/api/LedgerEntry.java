package com.economycore.api;

/** Línea inmutable del libro mayor. delta es positivo (ingreso) o negativo (egreso). */
public record LedgerEntry(String opId, String account, String currency, long delta, long balanceAfter, String reason, long timestamp) {}
