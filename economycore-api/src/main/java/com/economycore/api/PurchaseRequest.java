package com.economycore.api;

/** purchaseId es la clave de idempotencia: generada por quien llama y reutilizada en reintentos. El precio NUNCA lo fija el llamante. */
public record PurchaseRequest(String purchaseId, String marketId, String productId, String buyer, int quantity) {
    public PurchaseRequest {
        if (purchaseId == null || purchaseId.isBlank() || purchaseId.length() > 100) throw new IllegalArgumentException("purchaseId inválido");
        if (marketId == null || productId == null || buyer == null) throw new IllegalArgumentException("null");
        if (quantity < 1) throw new IllegalArgumentException("quantity < 1");
    }
}
