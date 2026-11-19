package com.economycore.api;

/** Cantidad inmutable no negativa en unidades mínimas. Toda aritmética es exacta (lanza ArithmeticException en overflow). */
public record Money(String currency, long minor) {
    public Money {
        if (currency == null) throw new IllegalArgumentException("currency null");
        if (minor < 0) throw new IllegalArgumentException("cantidad negativa");
    }
    public static Money of(String currency, long minor) { return new Money(currency, minor); }
    public Money plus(Money o) { same(o); return new Money(currency, Math.addExact(minor, o.minor)); }
    public Money minus(Money o) { same(o); return new Money(currency, Math.subtractExact(minor, o.minor)); }
    public Money times(long n) { return new Money(currency, Math.multiplyExact(minor, n)); }
    public boolean isPositive() { return minor > 0; }
    private void same(Money o) { if (!currency.equals(o.currency)) throw new IllegalArgumentException("monedas distintas"); }
}
