package com.economycore.api;

/** Estado persistido de una compra. total/tax en unidades mínimas de currency. */
public record PurchaseRecord(String id, String marketId, String productId, String buyer, int quantity,
                             String currency, long total, long tax, String payee, PurchaseState state,
                             int attempts, String note, long createdAt, long updatedAt) {
    public static PurchaseRecord created(PurchaseRequest r, long now) {
        return new PurchaseRecord(r.purchaseId(), r.marketId(), r.productId(), r.buyer(), r.quantity(),
                "", 0, 0, "", PurchaseState.CREATED, 0, "", now, now);
    }
    public PurchaseRecord to(PurchaseState s, String note, long now) {
        return new PurchaseRecord(id, marketId, productId, buyer, quantity, currency, total, tax, payee, s, attempts,
                note == null ? this.note : note, createdAt, now);
    }
    public PurchaseRecord reserved(String currency, long total, long tax, String payee, long now) {
        return new PurchaseRecord(id, marketId, productId, buyer, quantity, currency, total, tax, payee,
                PurchaseState.RESERVED, attempts, note, createdAt, now);
    }
    public PurchaseRecord withAttempts(int a, long now) {
        return new PurchaseRecord(id, marketId, productId, buyer, quantity, currency, total, tax, payee, state, a, note, createdAt, now);
    }
    public boolean sameRequest(PurchaseRequest r) {
        return marketId.equals(r.marketId()) && productId.equals(r.productId()) && buyer.equals(r.buyer()) && quantity == r.quantity();
    }
}
