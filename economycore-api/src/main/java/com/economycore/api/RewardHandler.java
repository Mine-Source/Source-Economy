package com.economycore.api;

/** Contrato de entrega. Se invoca solo tras confirmar el pago y puede invocarse varias veces con la misma idempotencyKey (recuperación). Debe ser idempotente. */
public interface RewardHandler {
    String type();
    DeliveryResult deliver(DeliveryContext ctx) throws Exception;
}
