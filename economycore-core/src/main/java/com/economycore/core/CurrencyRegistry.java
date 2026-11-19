package com.economycore.core;

import com.economycore.api.Currency;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class CurrencyRegistry {
    private final Map<String, Currency> map = new ConcurrentHashMap<>();
    public CurrencyRegistry register(Currency c) { map.put(c.id(), c); return this; }
    public Optional<Currency> get(String id) { return Optional.ofNullable(map.get(id)); }
    public boolean has(String id) { return map.containsKey(id); }
    public Collection<Currency> all() { return List.copyOf(map.values()); }
}
