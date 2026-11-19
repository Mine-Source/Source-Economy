package com.economycore.core;

import com.economycore.api.*;
import com.economycore.api.PurchaseState;
import com.economycore.api.Result.Code;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Motor multimercado + máquina de estados de compras.
 * CREATED -> VALIDATING -> RESERVED (stock/límites) -> PAID (fondos a escrow) -> DELIVERING -> COMPLETED (escrow a pagador/tesorería).
 * Fallo de entrega: PENDING_RECOVERY (reintentable sin volver a cobrar) o REFUNDED (escrow devuelto, stock repuesto). Fallos previos al pago: FAILED.
 * Cada transición es un CAS dentro de una transacción de Storage; las recompensas solo se ejecutan tras PAID.
 */
public final class MarketEngine implements MarketService {
    private static final Logger LOG = Logger.getLogger("EconomyCore");
    public static final String TREASURY = "treasury";

    private final Storage storage; private final CurrencyRegistry currencies; private final MarketRegistry registry; private final Clock clock;
    private final Map<String, RewardHandler> handlers = new ConcurrentHashMap<>();
    private final Map<String, ConditionProvider> conditions = new ConcurrentHashMap<>();
    private final Map<String, ItemProvider> providers = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> providerMarkets = new ConcurrentHashMap<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final List<MarketListener> listeners = new CopyOnWriteArrayList<>();
    private final int maxAttempts;

    public MarketEngine(Storage storage, CurrencyRegistry currencies, MarketRegistry registry, Clock clock, int maxDeliveryAttempts) {
        this.storage = storage; this.currencies = currencies; this.registry = registry; this.clock = clock; this.maxAttempts = maxDeliveryAttempts;
    }
    public MarketRegistry registry() { return registry; }

    // ---------- Registro de mercados / productos ----------
    public void registerMarket(Market m, List<Product> products) { registry.register(m, products); }
    public boolean unregisterMarket(String id) { return registry.unregister(id); }
    public boolean setMarketEnabled(String id, boolean on) { return registry.setEnabled(id, on); }
    public List<String> marketIds() { return registry.ids(); }
    public Optional<Market> market(String id) { return registry.market(id); }
    public void upsertProducts(String id, List<Product> ps) { registry.upsert(id, ps); }
    public void removeProduct(String id, String productId) { registry.remove(id, p -> p.id().equals(productId)); }
    public List<Product> visibleProducts(String id) { List<Product> l = new ArrayList<>(registry.visible(id, clock.millis())); l.removeIf(Product::secret); return l; }
    public List<Product> visibleProducts(String id, String buyer) {
        List<Product> l = new ArrayList<>(registry.visible(id, clock.millis()));
        l.removeIf(p -> p.secret() && !conditionsOk(buyer, p.requirements()));
        return l;
    }
    public List<Product> search(String id, String q, int page, int size) { return registry.search(id, q, clock.millis(), page, size); }
    public void registerRewardHandler(RewardHandler h) { handlers.put(h.type(), h); }
    public void registerConditionProvider(ConditionProvider p) { conditions.put(p.type(), p); }
    public void addListener(MarketListener l) { listeners.add(l); }

    public void attachProvider(String marketId, ItemProvider provider) {
        providers.put(provider.id(), provider);
        providerMarkets.computeIfAbsent(provider.id(), k -> ConcurrentHashMap.newKeySet()).add(marketId);
        refreshProvider(provider.id());
    }
    public void detachProvider(String marketId, String providerId) {
        Set<String> ms = providerMarkets.get(providerId);
        if (ms != null) ms.remove(marketId);
        registry.remove(marketId, p -> providerId.equals(p.providerId()));
    }
    /** Aislado: si el proveedor falla se conservan sus productos anteriores y no se afecta a nadie más. */
    public void refreshProvider(String providerId) {
        ItemProvider ip = providers.get(providerId);
        if (ip == null) return;
        List<Product> fresh;
        try { fresh = ip.products(); } catch (Throwable t) { LOG.log(Level.WARNING, "Proveedor " + providerId + " falló al listar productos", t); return; }
        for (String m : new ArrayList<>(providerMarkets.getOrDefault(providerId, Set.of()))) {
            List<Product> tagged = new ArrayList<>();
            for (Product p : fresh) tagged.add(new Product(p.id(), p.category(), p.name(), p.description(), providerId, p.currency(), p.price(), p.reward(),
                p.stock(), p.perPlayerLimit(), p.maxQuantity(), p.requirements(), p.secret(), p.enabled(), p.alwaysVisible(), p.payee(), p.discountBps(), p.discountUntil()));
            Set<String> keep = new HashSet<>(); for (Product p : tagged) keep.add(p.id());
            registry.remove(m, p -> providerId.equals(p.providerId()) && !keep.contains(p.id()));
            registry.upsert(m, tagged);
        }
    }

    public void restock(String marketId, String productId, long remaining) {
        if (remaining < 0) throw new IllegalArgumentException("remaining < 0");
        storage.tx(tx -> { tx.setCounter(stockKey(marketId, productId), remaining); return null; });
    }

    // ---------- Consultas ----------
    public Optional<PurchaseRecord> purchase(String id) { return storage.tx(tx -> tx.purchase(id)); }
    public List<PurchaseRecord> purchasesOf(String buyer, int limit) { return storage.tx(tx -> tx.purchasesOf(buyer, limit)); }

    // ---------- Compra ----------
    public PurchaseRecord purchase(PurchaseRequest req) {
        long now = clock.millis();
        PurchaseRecord rec = storage.tx(tx -> {
            Optional<PurchaseRecord> ex = tx.purchase(req.purchaseId());
            if (ex.isPresent()) return ex.get();
            PurchaseRecord r = PurchaseRecord.created(req, now); tx.putPurchase(r); return r;
        });
        if (!rec.sameRequest(req)) throw new IllegalArgumentException("purchaseId reutilizado con parámetros distintos");
        return advance(rec);
    }

    public int recover(long staleMillis) {
        long before = clock.millis() - staleMillis; int n = 0;
        List<PurchaseRecord> stuck = storage.tx(tx -> tx.purchasesIn(EnumSet.of(PurchaseState.CREATED, PurchaseState.VALIDATING, PurchaseState.RESERVED,
            PurchaseState.PAID, PurchaseState.DELIVERING, PurchaseState.PENDING_RECOVERY), before, 500));
        for (PurchaseRecord r : stuck) {
            try {
                switch (r.state()) {
                    case CREATED, VALIDATING -> step(r, tx -> cas(tx, r.id(), Set.of(r.state()), PurchaseState.FAILED, "RECOVERED_ABORT", false));
                    case RESERVED -> step(r, tx -> cas(tx, r.id(), Set.of(PurchaseState.RESERVED), PurchaseState.FAILED, "RECOVERED_ABORT", true));
                    case PENDING_RECOVERY -> { if (r.attempts() < maxAttempts) advance(r); else continue; }
                    default -> advance(r);
                }
                n++;
            } catch (RuntimeException e) { LOG.log(Level.WARNING, "recover " + r.id(), e); }
        }
        return n;
    }

    public PurchaseRecord retryDelivery(String id) {
        PurchaseRecord r = storage.tx(tx -> {
            PurchaseRecord cur = tx.purchase(id).orElseThrow(() -> new IllegalArgumentException("compra desconocida"));
            if (cur.state() != PurchaseState.PENDING_RECOVERY) return cur;
            PurchaseRecord n = cur.withAttempts(0, clock.millis()); tx.putPurchase(n); return n;
        });
        return advance(r);
    }

    public PurchaseRecord refund(String id) {
        PurchaseRecord r = purchase(id).orElseThrow(() -> new IllegalArgumentException("compra desconocida"));
        if (!inFlight.add(id)) return r;
        try { return doRefund(r, "ADMIN_REFUND"); } finally { inFlight.remove(id); }
    }

    // ---------- Máquina de estados ----------
    private PurchaseRecord advance(PurchaseRecord start) {
        final String id = start.id();
        PurchaseRecord r = start;
        if (!inFlight.add(id)) return storage.tx(tx -> tx.purchase(id)).orElse(start);
        try {
            for (int guard = 0; guard < 8; guard++) {
                switch (r.state()) {
                    case CREATED -> r = step(r, tx -> cas(tx, id, Set.of(PurchaseState.CREATED), PurchaseState.VALIDATING, null, false));
                    case VALIDATING -> r = validateAndReserve(r);
                    case RESERVED -> r = pay(r);
                    case PAID, DELIVERING, PENDING_RECOVERY -> { return deliver(r); }
                    default -> { return r; }
                }
            }
            return r;
        } finally { inFlight.remove(id); }
    }

    private PurchaseRecord validateAndReserve(PurchaseRecord r) {
        long now = clock.millis();
        String why = validate(r, now);
        if (why != null) return step(r, tx -> cas(tx, r.id(), Set.of(PurchaseState.VALIDATING), PurchaseState.FAILED, why, false));
        MarketRegistry.State st = registry.state(r.marketId());
        Market m = st.market; Product p = st.products.get(r.productId());
        return step(r, tx -> {
            PurchaseRecord cur = tx.purchase(r.id()).orElseThrow();
            if (cur.state() != PurchaseState.VALIDATING) return cur;
            long remaining = -1;
            if (p.stock() >= 0) {
                remaining = tx.counter(stockKey(m.id(), p.id())).orElse(p.stock());
                if (remaining < r.quantity()) return fail(tx, cur, "OUT_OF_STOCK");
            }
            long bought = 0;
            if (p.perPlayerLimit() > 0) {
                bought = tx.counter(limitKey(m.id(), p.id(), r.buyer())).orElse(0);
                if (bought + r.quantity() > p.perPlayerLimit()) return fail(tx, cur, "LIMIT_REACHED");
            }
            long window = Pricing.window(m.id(), m.rotation(), now);
            long demand = tx.counter(demandKey(m.id(), p.id(), window)).orElse(0);
            long unit, total, tax;
            try {
                unit = Pricing.unitPrice(m.id(), p, remaining, demand, window, now);
                total = Math.multiplyExact(unit, (long) r.quantity());
                tax = Pricing.tax(total, m.taxBps());
                Math.addExact(total, tax);
            } catch (ArithmeticException e) { return fail(tx, cur, "PRICE_OVERFLOW"); }
            if (p.stock() >= 0) tx.setCounter(stockKey(m.id(), p.id()), remaining - r.quantity());
            if (p.perPlayerLimit() > 0) tx.setCounter(limitKey(m.id(), p.id(), r.buyer()), bought + r.quantity());
            tx.setCounter(demandKey(m.id(), p.id(), window), demand + r.quantity());
            PurchaseRecord n = cur.reserved(p.currency(), total, tax, p.payee() == null ? "sink:" + m.id() : p.payee(), now);
            tx.putPurchase(n); return n;
        });
    }

    /** Validaciones baratas fuera de transacción. Devuelve motivo de rechazo o null. */
    private String validate(PurchaseRecord r, long now) {
        MarketRegistry.State st = registry.state(r.marketId());
        if (st == null) return "MARKET_NOT_FOUND";
        if (!st.enabled) return "MARKET_DISABLED";
        Market m = st.market;
        switch (m.mode()) { case BUY_SHOP, BUY_SELL, OFFERS, BLACK_MARKET, CUSTOM -> {} default -> { return "MODE_UNSUPPORTED"; } }
        if ((m.opensAt() > 0 && now < m.opensAt()) || (m.closesAt() > 0 && now >= m.closesAt())) return "MARKET_CLOSED";
        Product p = st.products.get(r.productId());
        if (p == null) return "PRODUCT_NOT_FOUND";
        if (!p.enabled()) return "PRODUCT_DISABLED";
        if (!st.inRotation(p, now)) return "NOT_AVAILABLE";
        if (r.quantity() > p.maxQuantity()) return "QUANTITY_TOO_HIGH";
        if (!currencies.has(p.currency())) return "UNKNOWN_CURRENCY";
        if (!m.currencies().isEmpty() && !m.currencies().contains(p.currency())) return "CURRENCY_NOT_ACCEPTED";
        if (!conditionsOk(r.buyer(), m.requirements()) || !conditionsOk(r.buyer(), p.requirements())) return "ACCESS_DENIED";
        if (!handlers.containsKey(p.reward().type())) return "NO_REWARD_HANDLER";
        if (p.providerId() != null) {
            ItemProvider ip = providers.get(p.providerId());
            if (ip == null) return "PROVIDER_UNAVAILABLE";
            try { Optional<String> no = ip.validate(r.buyer(), p, r.quantity()); if (no.isPresent()) return "PROVIDER_REJECTED:" + no.get(); }
            catch (Throwable t) { LOG.log(Level.WARNING, "Proveedor " + p.providerId() + " falló al validar", t); return "PROVIDER_ERROR"; }
        }
        return null;
    }

    private boolean conditionsOk(String buyer, List<String> reqs) {
        for (String c : reqs) {
            int i = c.indexOf(':');
            String type = i < 0 ? c : c.substring(0, i), arg = i < 0 ? "" : c.substring(i + 1);
            ConditionProvider cp = conditions.get(type);
            if (cp == null) return false; // falla cerrado
            try { if (!cp.test(buyer, arg)) return false; } catch (Throwable t) { return false; }
        }
        return true;
    }

    private PurchaseRecord pay(PurchaseRecord r) {
        return step(r, tx -> {
            PurchaseRecord cur = tx.purchase(r.id()).orElseThrow();
            if (cur.state() != PurchaseState.RESERVED) return cur;
            long now = clock.millis(), charge;
            try { charge = Math.addExact(cur.total(), cur.tax()); } catch (ArithmeticException e) { return fail(tx, cur, "PRICE_OVERFLOW"); }
            Code c = Accounts.move(tx, cur.id() + ":hold", cur.buyer(), escrow(cur.id()), cur.currency(), charge, "purchase " + cur.id(), now);
            if (c == Code.INSUFFICIENT_FUNDS) return fail(tx, cur, "INSUFFICIENT_FUNDS");
            if (c != Code.OK && c != Code.DUPLICATE) return fail(tx, cur, "PAYMENT_" + c);
            PurchaseRecord n = cur.to(PurchaseState.PAID, null, now); tx.putPurchase(n); return n;
        });
    }

    private PurchaseRecord deliver(PurchaseRecord r) {
        long now = clock.millis();
        PurchaseRecord d = step(r, tx -> {
            PurchaseRecord cur = tx.purchase(r.id()).orElseThrow();
            if (cur.state() != PurchaseState.PAID && cur.state() != PurchaseState.DELIVERING && cur.state() != PurchaseState.PENDING_RECOVERY) return cur;
            PurchaseRecord n = cur.to(PurchaseState.DELIVERING, null, now).withAttempts(cur.attempts() + 1, now);
            tx.putPurchase(n); return n;
        });
        if (d.state() != PurchaseState.DELIVERING) return d;

        MarketRegistry.State st = registry.state(d.marketId());
        Product p = st == null ? null : st.products.get(d.productId());
        if (p == null) return pending(d, "PRODUCT_MISSING");
        RewardHandler h = handlers.get(p.reward().type());
        if (h == null) return pending(d, "NO_REWARD_HANDLER");
        DeliveryResult res;
        try {
            res = h.deliver(new DeliveryContext("ec:" + d.id(), d.id(), d.buyer(), d.marketId(), p, d.quantity(), p.reward(), d.currency(), d.total() + d.tax()));
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "Entrega fallida " + d.id(), t);
            return pending(d, "EXCEPTION:" + t.getClass().getSimpleName());
        }
        if (res == null) return pending(d, "NULL_RESULT");
        switch (res.status()) {
            case DELIVERED: return complete(d);
            case FAILED: return doRefund(d, "DELIVERY_REJECTED:" + res.message());
            default: return pending(d, "RETRY:" + res.message());
        }
    }

    private PurchaseRecord pending(PurchaseRecord d, String note) {
        return step(d, tx -> cas(tx, d.id(), Set.of(PurchaseState.DELIVERING), PurchaseState.PENDING_RECOVERY, note, false));
    }

    private PurchaseRecord complete(PurchaseRecord d) {
        return step(d, tx -> {
            PurchaseRecord cur = tx.purchase(d.id()).orElseThrow();
            if (cur.state() != PurchaseState.DELIVERING) return cur;
            long now = clock.millis();
            Code c1 = Accounts.move(tx, cur.id() + ":pay", escrow(cur.id()), cur.payee(), cur.currency(), cur.total(), "sale " + cur.id(), now);
            if (c1 != Code.OK && c1 != Code.DUPLICATE) throw new IllegalStateException("escrow inconsistente " + cur.id() + " " + c1);
            if (cur.tax() > 0) {
                Code c2 = Accounts.move(tx, cur.id() + ":tax", escrow(cur.id()), TREASURY, cur.currency(), cur.tax(), "tax " + cur.id(), now);
                if (c2 != Code.OK && c2 != Code.DUPLICATE) throw new IllegalStateException("escrow inconsistente " + cur.id() + " " + c2);
            }
            PurchaseRecord n = cur.to(PurchaseState.COMPLETED, "OK", now); tx.putPurchase(n); return n;
        });
    }

    private PurchaseRecord doRefund(PurchaseRecord r, String why) {
        return step(r, tx -> {
            PurchaseRecord cur = tx.purchase(r.id()).orElseThrow();
            switch (cur.state()) { case PAID, DELIVERING, PENDING_RECOVERY -> {} default -> { return cur; } }
            long now = clock.millis();
            Code c = Accounts.move(tx, cur.id() + ":refund", escrow(cur.id()), cur.buyer(), cur.currency(), cur.total() + cur.tax(), "refund " + cur.id(), now);
            if (c != Code.OK && c != Code.DUPLICATE) throw new IllegalStateException("reembolso inconsistente " + cur.id() + " " + c);
            release(tx, cur, now);
            PurchaseRecord n = cur.to(PurchaseState.REFUNDED, why, now); tx.putPurchase(n); return n;
        });
    }

    // ---------- Utilidades ----------
    private PurchaseRecord fail(Storage.Tx tx, PurchaseRecord cur, String why) {
        PurchaseRecord n = cur.to(PurchaseState.FAILED, why, clock.millis());
        if (cur.state() == PurchaseState.RESERVED) release(tx, cur, clock.millis());
        tx.putPurchase(n); return n;
    }

    /** CAS: aplica la transición solo si el estado actual es uno de 'from'. releaseStock repone stock/límites reservados. */
    private PurchaseRecord cas(Storage.Tx tx, String id, Set<PurchaseState> from, PurchaseState to, String note, boolean releaseStock) {
        PurchaseRecord cur = tx.purchase(id).orElseThrow();
        if (!from.contains(cur.state())) return cur;
        long now = clock.millis();
        if (releaseStock) release(tx, cur, now);
        PurchaseRecord n = cur.to(to, note, now); tx.putPurchase(n); return n;
    }

    /** Repone stock, límite por jugador y demanda de una reserva. Solo toca contadores existentes. */
    private void release(Storage.Tx tx, PurchaseRecord r, long now) {
        MarketRegistry.State st = registry.state(r.marketId());
        long window = st == null ? now / 86_400_000L : Pricing.window(r.marketId(), st.market.rotation(), r.updatedAt());
        adjust(tx, stockKey(r.marketId(), r.productId()), r.quantity());
        adjust(tx, limitKey(r.marketId(), r.productId(), r.buyer()), -r.quantity());
        adjust(tx, demandKey(r.marketId(), r.productId(), window), -r.quantity());
    }
    private void adjust(Storage.Tx tx, String key, long delta) {
        OptionalLong v = tx.counter(key);
        if (v.isPresent()) tx.setCounter(key, Math.max(0, v.getAsLong() + delta));
    }

    private PurchaseRecord step(PurchaseRecord before, Function<Storage.Tx, PurchaseRecord> fn) {
        PurchaseRecord after;
        try { after = storage.tx(fn); }
        catch (Storage.DuplicateOperationException e) { after = storage.tx(tx -> tx.purchase(before.id())).orElse(before); }
        if (after.state() != before.state()) {
            for (MarketListener l : listeners) { try { l.onPurchaseState(after); } catch (Throwable t) { LOG.log(Level.WARNING, "listener", t); } }
        }
        return after;
    }

    static String escrow(String purchaseId) { return "escrow:" + purchaseId; }
    static String stockKey(String m, String p) { return "stock:" + m + ":" + p; }
    static String limitKey(String m, String p, String buyer) { return "plimit:" + m + ":" + p + ":" + buyer; }
    static String demandKey(String m, String p, long w) { return "demand:" + m + ":" + p + ":" + w; }
}
