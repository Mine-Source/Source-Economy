package com.economycore.api;

/** DELIVERED: entregado (o ya estaba entregado). RETRY: fallo temporal, se reintentará. FAILED: rechazo definitivo, se reembolsa. */
public record DeliveryResult(Status status, String message) {
    public enum Status { DELIVERED, RETRY, FAILED }
    public static DeliveryResult delivered() { return new DeliveryResult(Status.DELIVERED, ""); }
    public static DeliveryResult retry(String m) { return new DeliveryResult(Status.RETRY, m); }
    public static DeliveryResult failed(String m) { return new DeliveryResult(Status.FAILED, m); }
}
