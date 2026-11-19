package com.economycore.api;

/**
 * Precio en unidades mínimas. FIXED: base. RANDOM: entre min y max, estable durante la ventana de rotación/día.
 * STOCK: sube hasta factorBps/10000 * base según el stock consumido. DEMAND: sube factorBps por unidad comprada en la ventana (tope max si >0).
 */
public record PriceSpec(Type type, long base, long min, long max, int factorBps) {
    public enum Type { FIXED, RANDOM, STOCK, DEMAND }
    public PriceSpec {
        if (base < 0 || min < 0 || max < min || factorBps < 0) throw new IllegalArgumentException("PriceSpec inválido");
        if (type == Type.RANDOM && max < 1) throw new IllegalArgumentException("RANDOM requiere max>=1");
    }
    public static PriceSpec fixed(long base) { return new PriceSpec(Type.FIXED, base, 0, 0, 0); }
    public static PriceSpec random(long min, long max) { return new PriceSpec(Type.RANDOM, 0, min, max, 0); }
    public static PriceSpec stock(long base, int factorBps) { return new PriceSpec(Type.STOCK, base, 0, 0, factorBps); }
    public static PriceSpec demand(long base, int factorBpsPerUnit, long cap) { return new PriceSpec(Type.DEMAND, base, 0, cap, factorBpsPerUnit); }
}
