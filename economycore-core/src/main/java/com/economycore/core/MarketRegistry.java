package com.economycore.core;

import com.economycore.api.Market;
import com.economycore.api.Product;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Registro central de mercados. Cada mercado es un estado inmutable reemplazado atómicamente (carga/recarga individual, caché por mercado, índice de búsqueda). */
public final class MarketRegistry {
    static final class State {
        final Market market; final Map<String, Product> products; final TreeMap<String, Set<String>> index;
        volatile boolean enabled;
        private volatile long cacheWindow = Long.MIN_VALUE; private volatile List<Product> cache = List.of();
        State(Market m, Map<String, Product> products, boolean enabled) {
            this.market = m; this.products = Collections.unmodifiableMap(new LinkedHashMap<>(products)); this.enabled = enabled;
            this.index = buildIndex(this.products.values());
        }
        /** Productos de la rotación vigente (cacheado por ventana). */
        List<Product> rotated(long now) {
            long w = Pricing.window(market.id(), market.rotation(), now);
            if (w != cacheWindow) { cache = Pricing.rotate(market.id(), market.rotation(), products.values(), now); cacheWindow = w; }
            return cache;
        }
        boolean inRotation(Product p, long now) {
            if (market.rotation() == null) return p.enabled();
            for (Product q : rotated(now)) if (q.id().equals(p.id())) return true;
            return false;
        }
    }

    private final Map<String, State> markets = new ConcurrentHashMap<>();

    State state(String id) { return markets.get(id); }
    public List<String> ids() { List<String> l = new ArrayList<>(markets.keySet()); Collections.sort(l); return l; }
    public Optional<Market> market(String id) { State s = markets.get(id); return s == null ? Optional.empty() : Optional.of(s.market); }
    public boolean isEnabled(String id) { State s = markets.get(id); return s != null && s.enabled; }

    public void register(Market m, Collection<Product> products) {
        Map<String, Product> map = new LinkedHashMap<>();
        for (Product p : products) map.put(p.id(), p);
        markets.put(m.id(), new State(m, map, m.enabled()));
    }
    public boolean unregister(String id) { return markets.remove(id) != null; }
    public boolean setEnabled(String id, boolean on) { State s = markets.get(id); if (s == null) return false; s.enabled = on; return true; }

    public void upsert(String marketId, Collection<Product> products) {
        markets.computeIfPresent(marketId, (k, s) -> {
            Map<String, Product> map = new LinkedHashMap<>(s.products);
            for (Product p : products) map.put(p.id(), p);
            State n = new State(s.market, map, s.enabled); return n;
        });
    }
    public void remove(String marketId, java.util.function.Predicate<Product> which) {
        markets.computeIfPresent(marketId, (k, s) -> {
            Map<String, Product> map = new LinkedHashMap<>(s.products);
            map.values().removeIf(which);
            return new State(s.market, map, s.enabled);
        });
    }

    public List<Product> visible(String marketId, long now) { State s = markets.get(marketId); return s == null ? List.of() : s.rotated(now); }

    public List<Product> page(String marketId, String category, long now, int page, int size) {
        List<Product> out = new ArrayList<>();
        for (Product p : visible(marketId, now)) if (category == null || category.equals(p.category())) out.add(p);
        return slice(out, page, size);
    }

    public List<Product> search(String marketId, String query, long now, int page, int size) {
        State s = markets.get(marketId);
        if (s == null) return List.of();
        Set<String> ids = null;
        for (String tok : tokens(query)) {
            Set<String> hit = new HashSet<>();
            for (var e : s.index.subMap(tok, tok + Character.MAX_VALUE).entrySet()) hit.addAll(e.getValue());
            if (ids == null) ids = hit; else ids.retainAll(hit);
        }
        if (ids == null) return List.of();
        Set<String> visible = new HashSet<>();
        for (Product p : s.rotated(now)) visible.add(p.id());
        List<Product> out = new ArrayList<>();
        for (String id : new TreeSet<>(ids)) if (visible.contains(id)) out.add(s.products.get(id));
        return slice(out, page, size);
    }

    private static List<Product> slice(List<Product> l, int page, int size) {
        int from = Math.max(0, page) * Math.max(1, size);
        if (from >= l.size()) return List.of();
        return List.copyOf(l.subList(from, Math.min(l.size(), from + size)));
    }
    private static List<String> tokens(String s) {
        List<String> t = new ArrayList<>();
        for (String x : s.toLowerCase().split("[^a-z0-9]+")) if (!x.isEmpty()) t.add(x);
        return t;
    }
    private static TreeMap<String, Set<String>> buildIndex(Collection<Product> ps) {
        TreeMap<String, Set<String>> idx = new TreeMap<>();
        for (Product p : ps) for (String t : tokens(p.id() + " " + p.name() + " " + p.category())) idx.computeIfAbsent(t, k -> new HashSet<>()).add(p.id());
        return idx;
    }
}
