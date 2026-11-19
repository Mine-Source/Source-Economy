package com.economycore.api;

import java.util.List;

/**
 * Producto de un mercado. stock: -1 = ilimitado, si no, existencias globales iniciales.
 * perPlayerLimit: 0 = sin límite. secret: solo se lista a quien cumple los requisitos.
 * requirements: condiciones "tipo:argumento" resueltas por ConditionProvider. payee: cuenta que cobra (null = sumidero del mercado).
 */
public record Product(String id, String category, String name, String description, String providerId,
                      String currency, PriceSpec price, RewardSpec reward, long stock, long perPlayerLimit,
                      int maxQuantity, List<String> requirements, boolean secret, boolean enabled,
                      boolean alwaysVisible, String payee, int discountBps, long discountUntil) {
    public Product {
        if (id == null || !id.matches("[A-Za-z0-9_.-]{1,64}")) throw new IllegalArgumentException("id de producto inválido: " + id);
        if (currency == null || price == null || reward == null) throw new IllegalArgumentException("null");
        if (stock < -1 || perPlayerLimit < 0 || maxQuantity < 1 || discountBps < 0 || discountBps > 10000) throw new IllegalArgumentException("Product inválido");
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
        category = category == null ? "" : category;
        name = name == null ? id : name;
        description = description == null ? "" : description;
    }
    public static Builder builder(String id, String currency, PriceSpec price, RewardSpec reward) { return new Builder(id, currency, price, reward); }

    public static final class Builder {
        private final String id, currency; private final PriceSpec price; private final RewardSpec reward;
        private String category = "", name, description = "", providerId, payee;
        private long stock = -1, perPlayerLimit = 0, discountUntil = 0;
        private int maxQuantity = 64, discountBps = 0;
        private List<String> requirements = List.of();
        private boolean secret = false, enabled = true, alwaysVisible = false;
        private Builder(String id, String currency, PriceSpec price, RewardSpec reward) { this.id = id; this.currency = currency; this.price = price; this.reward = reward; }
        public Builder category(String v) { category = v; return this; }
        public Builder name(String v) { name = v; return this; }
        public Builder description(String v) { description = v; return this; }
        public Builder providerId(String v) { providerId = v; return this; }
        public Builder payee(String v) { payee = v; return this; }
        public Builder stock(long v) { stock = v; return this; }
        public Builder perPlayerLimit(long v) { perPlayerLimit = v; return this; }
        public Builder maxQuantity(int v) { maxQuantity = v; return this; }
        public Builder requirements(List<String> v) { requirements = v; return this; }
        public Builder secret(boolean v) { secret = v; return this; }
        public Builder enabled(boolean v) { enabled = v; return this; }
        public Builder alwaysVisible(boolean v) { alwaysVisible = v; return this; }
        public Builder discount(int bps, long untilMillis) { discountBps = bps; discountUntil = untilMillis; return this; }
        public Product build() {
            return new Product(id, category, name, description, providerId, currency, price, reward, stock, perPlayerLimit,
                    maxQuantity, requirements, secret, enabled, alwaysVisible, payee, discountBps, discountUntil);
        }
    }
}
