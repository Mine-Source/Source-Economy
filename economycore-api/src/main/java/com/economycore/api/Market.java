package com.economycore.api;

import java.util.List;
import java.util.Set;

/** Definición de un mercado. opensAt/closesAt en epoch millis (0 = sin límite). currencies vacío = cualquiera. taxBps: impuesto sobre el total, va a "treasury". */
public record Market(String id, String name, String description, Mode mode, boolean enabled, List<String> requirements,
                     int taxBps, long opensAt, long closesAt, Set<String> currencies, Rotation rotation) {
    public enum Mode { BUY_SHOP, SELL_SHOP, BUY_SELL, OFFERS, BLACK_MARKET, PLAYER_MARKET, AUCTION, ORDERS, EXCHANGE, CUSTOM }
    public Market {
        if (id == null || !id.matches("[a-z0-9_-]{1,48}")) throw new IllegalArgumentException("id de mercado inválido: " + id);
        if (mode == null) throw new IllegalArgumentException("mode null");
        if (taxBps < 0 || taxBps > 10000) throw new IllegalArgumentException("taxBps");
        name = name == null ? id : name;
        description = description == null ? "" : description;
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
        currencies = currencies == null ? Set.of() : Set.copyOf(currencies);
    }
    public static Market simple(String id, String name, Mode mode) { return new Market(id, name, "", mode, true, List.of(), 0, 0, 0, Set.of(), null); }
    public Market withTax(int bps) { return new Market(id, name, description, mode, enabled, requirements, bps, opensAt, closesAt, currencies, rotation); }
    public Market withRequirements(List<String> r) { return new Market(id, name, description, mode, enabled, r, taxBps, opensAt, closesAt, currencies, rotation); }
    public Market withRotation(Rotation r) { return new Market(id, name, description, mode, enabled, requirements, taxBps, opensAt, closesAt, currencies, r); }
    public Market withWindow(long opens, long closes) { return new Market(id, name, description, mode, enabled, requirements, taxBps, opens, closes, currencies, rotation); }
    public Market withCurrencies(Set<String> c) { return new Market(id, name, description, mode, enabled, requirements, taxBps, opensAt, closesAt, c, rotation); }
}
