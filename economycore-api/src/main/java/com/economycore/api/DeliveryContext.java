package com.economycore.api;

/** idempotencyKey es estable para una compra: el handler DEBE usarla para no entregar dos veces si se le llama de nuevo. */
public record DeliveryContext(String idempotencyKey, String purchaseId, String buyer, String marketId, Product product,
                              int quantity, RewardSpec reward, String currency, long paid) {}
