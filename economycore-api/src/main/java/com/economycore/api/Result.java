package com.economycore.api;

/** Resultado de una operación económica. DUPLICATE significa "ya aplicada antes": se considera éxito idempotente. */
public record Result(Code code) {
    public enum Code { OK, DUPLICATE, INSUFFICIENT_FUNDS, INVALID_AMOUNT, UNKNOWN_CURRENCY, OVERFLOW, SAME_ACCOUNT }
    public boolean ok() { return code == Code.OK || code == Code.DUPLICATE; }
}
