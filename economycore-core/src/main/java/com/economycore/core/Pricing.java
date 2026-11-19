package com.economycore.core;

import com.economycore.api.PriceSpec;
import com.economycore.api.Product;
import com.economycore.api.Rotation;

import java.math.BigInteger;
import java.util.*;

/** Cálculo de precios y rotación. Todo con enteros exactos; sin estado. */
public final class Pricing {
    private Pricing() {}

    /** remaining = existencias actuales (-1 ilimitado), demand = unidades compradas en la ventana. */
    public static long unitPrice(String marketId, Product p, long remaining, long demand, long window, long now) {
        PriceSpec s = p.price();
        long unit;
        switch (s.type()) {
            case FIXED -> unit = s.base();
            case RANDOM -> {
                long span = s.max() - s.min() + 1;
                unit = s.min() + Math.floorMod(seed(marketId, p.id(), window), span);
            }
            case STOCK -> {
                if (p.stock() > 0 && remaining >= 0) {
                    BigInteger used = BigInteger.valueOf(Math.max(0, p.stock() - remaining));
                    BigInteger add = BigInteger.valueOf(s.base()).multiply(BigInteger.valueOf(s.factorBps())).multiply(used)
                        .divide(BigInteger.valueOf(p.stock()).multiply(BigInteger.valueOf(10000)));
                    unit = BigInteger.valueOf(s.base()).add(add).min(BigInteger.valueOf(Long.MAX_VALUE / 1024)).longValueExact();
                } else unit = s.base();
            }
            case DEMAND -> {
                BigInteger add = BigInteger.valueOf(s.base()).multiply(BigInteger.valueOf(s.factorBps())).multiply(BigInteger.valueOf(Math.max(0, demand)))
                    .divide(BigInteger.valueOf(10000));
                BigInteger v = BigInteger.valueOf(s.base()).add(add);
                if (s.max() > 0) v = v.min(BigInteger.valueOf(s.max()));
                unit = v.min(BigInteger.valueOf(Long.MAX_VALUE / 1024)).longValueExact();
            }
            default -> throw new IllegalStateException();
        }
        if (p.discountBps() > 0 && now < p.discountUntil()) {
            unit = unit - BigInteger.valueOf(unit).multiply(BigInteger.valueOf(p.discountBps())).divide(BigInteger.valueOf(10000)).longValueExact();
        }
        return Math.max(1, unit);
    }

    /** Impuesto redondeado hacia arriba (half-up) en unidades mínimas. */
    public static long tax(long total, int bps) {
        if (bps == 0) return 0;
        return BigInteger.valueOf(total).multiply(BigInteger.valueOf(bps)).add(BigInteger.valueOf(5000)).divide(BigInteger.valueOf(10000)).longValueExact();
    }

    public static long window(String unusedMarket, Rotation r, long now) { return r != null ? r.windowIndex(now) : now / 86_400_000L; }

    /** Selección determinista de la rotación: los alwaysVisible + 'slots' productos elegidos por semilla (mercado, ventana). */
    public static List<Product> rotate(String marketId, Rotation r, Collection<Product> pool, long now) {
        List<Product> sorted = new ArrayList<>(pool);
        sorted.removeIf(p -> !p.enabled());
        sorted.sort(Comparator.comparing(Product::id));
        if (r == null) return sorted;
        List<Product> fixed = new ArrayList<>(), rest = new ArrayList<>();
        for (Product p : sorted) (p.alwaysVisible() ? fixed : rest).add(p);
        Collections.shuffle(rest, new Random(seed(marketId, "rot", r.windowIndex(now))));
        List<Product> out = new ArrayList<>(fixed);
        out.addAll(rest.subList(0, Math.min(r.slots(), rest.size())));
        out.sort(Comparator.comparing(Product::id));
        return out;
    }

    private static long seed(String a, String b, long window) {
        long h = 1125899906842597L;
        for (char ch : (a + "|" + b + "|" + window).toCharArray()) h = 31 * h + ch;
        return h;
    }
}
