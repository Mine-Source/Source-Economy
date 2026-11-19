package com.economycore.api;

public enum PurchaseState {
    CREATED, VALIDATING, RESERVED, PAID, DELIVERING, COMPLETED, FAILED, REFUNDED, PENDING_RECOVERY;
    public boolean terminal() { return this == COMPLETED || this == FAILED || this == REFUNDED; }
}
