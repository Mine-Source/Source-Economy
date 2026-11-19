package com.economycore.core;

import com.economycore.api.*;
import com.economycore.api.Result.Code;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import com.economycore.api.Currency;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Runner de pruebas sin dependencias externas. Sale con código 1 si algo falla. */
public final class TestRunner {
    static int passed = 0; static final List<String> failures = new ArrayList<>();
    static void check(boolean ok, String msg) { if (!ok) throw new AssertionError(msg); }
    static void eq(Object a, Object b, String msg) { if (!Objects.equals(a, b)) throw new AssertionError(msg + " esperado=" + b + " actual=" + a); }

    static final class MutableClock extends Clock {
        final AtomicLong now = new AtomicLong(1_700_000_000_000L);
        public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId z) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        public long millis() { return now.get(); }
    }

    /** Handler idempotente por clave, con inyección de fallos. */
    static final class ItemHandler implements RewardHandler {
        final Set<String> delivered = ConcurrentHashMap.newKeySet();
        final AtomicInteger realDeliveries = new AtomicInteger(), calls = new AtomicInteger();
        volatile DeliveryResult.Status failWith = null; volatile boolean throwIt = false;
        public String type() { return "item"; }
        public DeliveryResult deliver(DeliveryContext c) {
            calls.incrementAndGet();
            if (throwIt) throw new RuntimeException("boom");
            if (failWith == DeliveryResult.Status.RETRY) return DeliveryResult.retry("later");
            if (failWith == DeliveryResult.Status.FAILED) return DeliveryResult.failed("nope");
            if (delivered.add(c.idempotencyKey())) realDeliveries.incrementAndGet();
            return DeliveryResult.delivered();
        }
    }

    static final class Env {
        final MutableClock clock = new MutableClock(); final InMemoryStorage storage = new InMemoryStorage();
        final CurrencyRegistry cur = new CurrencyRegistry().register(new Currency("coins", "Coins", "$", 2)).register(new Currency("gems", "Gems", "G", 0));
        final EconomyEngine eco = new EconomyEngine(storage, cur, clock);
        final MarketEngine mk = new MarketEngine(storage, cur, new MarketRegistry(), clock, 3);
        final ItemHandler handler = new ItemHandler();
        Env() { mk.registerRewardHandler(handler); }
        void fund(String acct, String cur, long n) { check(eco.deposit("fund:" + acct + cur + UUID.randomUUID(), acct, Money.of(cur, n), "t").ok(), "fund"); }
        Product prod(String id, String cur, long price, long stock) { return Product.builder(id, cur, PriceSpec.fixed(price), RewardSpec.of("item", Map.of("k", id))).stock(stock).category("c").name("Prod " + id).build(); }
    }
    static PurchaseRequest req(String id, String m, String p, String buyer, int q) { return new PurchaseRequest(id, m, p, buyer, q); }

    public static void main(String[] args) {
        run("money: aritmética exacta y parseo", () -> {
            Currency c = new Currency("coins", "C", "$", 2);
            eq(c.parse("12.50").minor(), 1250L, "parse");
            try { c.parse("1.234"); throw new AssertionError("debió fallar por decimales"); } catch (ArithmeticException ok) {}
            try { Money.of("coins", Long.MAX_VALUE).plus(Money.of("coins", 1)); throw new AssertionError("overflow"); } catch (ArithmeticException ok) {}
            try { Money.of("coins", -1); throw new AssertionError("negativo"); } catch (IllegalArgumentException ok) {}
        });
        run("economía: depósito/retiro/transferencia e idempotencia", () -> {
            Env e = new Env();
            check(e.eco.deposit("d1", "a", Money.of("coins", 1000), "x").code() == Code.OK, "dep");
            eq(e.eco.deposit("d1", "a", Money.of("coins", 1000), "x").code(), Code.DUPLICATE, "dep dup");
            eq(e.eco.balance("a", "coins"), 1000L, "no duplica");
            eq(e.eco.withdraw("w1", "a", Money.of("coins", 5000), "x").code(), Code.INSUFFICIENT_FUNDS, "fondos");
            eq(e.eco.balance("a", "coins"), 1000L, "sin cambios");
            eq(e.eco.transfer("t1", "a", "a", Money.of("coins", 1), "x").code(), Code.SAME_ACCOUNT, "misma cuenta");
            eq(e.eco.transfer("t2", "a", "b", Money.of("coins", 0), "x").code(), Code.INVALID_AMOUNT, "monto 0");
            eq(e.eco.deposit("u", "a", Money.of("nope", 1), "x").code(), Code.UNKNOWN_CURRENCY, "moneda");
            check(e.eco.transfer("t3", "a", "b", Money.of("coins", 400), "x").ok(), "transfer");
            check(e.eco.transfer("t3", "a", "b", Money.of("coins", 400), "x").ok(), "transfer repetida");
            eq(e.eco.balance("a", "coins"), 600L, "a"); eq(e.eco.balance("b", "coins"), 400L, "b");
            eq(e.eco.deposit("o", "a", Money.of("coins", Long.MAX_VALUE), "x").code(), Code.OVERFLOW, "overflow saldo");
            eq(e.eco.history("a", 10).size(), 2, "ledger de a");
        });
        run("economía: transferencias concurrentes conservan el dinero", () -> {
            Env e = new Env(); e.fund("a", "coins", 100_000); e.fund("b", "coins", 100_000);
            ExecutorService ex = Executors.newFixedThreadPool(16); List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < 500; i++) { int n = i; fs.add(ex.submit(() -> e.eco.transfer("tx" + n, n % 2 == 0 ? "a" : "b", n % 2 == 0 ? "b" : "a", Money.of("coins", 7), "x"))); }
            join(fs); ex.shutdown();
            eq(e.eco.totalSupply("coins"), 200_000L, "supply");
            eq(e.eco.balance("a", "coins") + e.eco.balance("b", "coins"), 200_000L, "suma");
        });
        run("mercados: crear, listar, activar/desactivar, impedir compras", () -> {
            Env e = new Env(); e.mk.registerMarket(Market.simple("main", "Main", Market.Mode.BUY_SHOP), List.of(e.prod("p1", "coins", 100, -1)));
            e.mk.registerMarket(Market.simple("pets", "Pets", Market.Mode.CUSTOM), List.of());
            eq(e.mk.marketIds(), List.of("main", "pets"), "ids"); e.fund("u", "coins", 1000);
            check(e.mk.setMarketEnabled("main", false), "disable");
            PurchaseRecord r = e.mk.purchase(req("a1", "main", "p1", "u", 1));
            eq(r.state(), PurchaseState.FAILED, "estado"); eq(r.note(), "MARKET_DISABLED", "nota");
            eq(e.eco.balance("u", "coins"), 1000L, "sin cobro");
            e.mk.setMarketEnabled("main", true);
            eq(e.mk.purchase(req("a2", "main", "p1", "u", 1)).state(), PurchaseState.COMPLETED, "compra ok");
            eq(e.mk.purchase(req("a3", "nada", "p1", "u", 1)).note(), "MARKET_NOT_FOUND", "no existe");
            eq(e.mk.purchase(req("a4", "main", "zzz", "u", 1)).note(), "PRODUCT_NOT_FOUND", "producto");
            Market sell = Market.simple("sell", "S", Market.Mode.AUCTION); e.mk.registerMarket(sell, List.of(e.prod("p1", "coins", 1, -1)));
            eq(e.mk.purchase(req("a5", "sell", "p1", "u", 1)).note(), "MODE_UNSUPPORTED", "modo");
        });
        run("compra: pago, impuesto, escrow y conservación", () -> {
            Env e = new Env(); e.mk.registerMarket(Market.simple("main", "Main", Market.Mode.BUY_SHOP).withTax(1000), List.of(e.prod("p1", "coins", 1000, -1)));
            e.fund("u", "coins", 5000);
            PurchaseRecord r = e.mk.purchase(req("c1", "main", "p1", "u", 2));
            eq(r.state(), PurchaseState.COMPLETED, "completada"); eq(r.total(), 2000L, "total"); eq(r.tax(), 200L, "tax");
            eq(e.eco.balance("u", "coins"), 2800L, "comprador"); eq(e.eco.balance("treasury", "coins"), 200L, "tesorería");
            eq(e.eco.balance("sink:main", "coins"), 2000L, "sumidero"); eq(e.eco.balance("escrow:c1", "coins"), 0L, "escrow vacío");
            eq(e.eco.totalSupply("coins"), 5000L, "conservación"); eq(e.handler.realDeliveries.get(), 1, "entregas");
        });
        run("compra: fondos insuficientes no entrega ni consume stock", () -> {
            Env e = new Env(); e.mk.registerMarket(Market.simple("main", "M", Market.Mode.BUY_SHOP), List.of(e.prod("p1", "coins", 1000, 3)));
            e.fund("u", "coins", 500);
            PurchaseRecord r = e.mk.purchase(req("f1", "main", "p1", "u", 2));
            eq(r.state(), PurchaseState.FAILED, "estado"); eq(r.note(), "INSUFFICIENT_FUNDS", "nota");
            eq(e.handler.calls.get(), 0, "sin entrega"); e.fund("v", "coins", 10_000);
            eq(e.mk.purchase(req("f2", "main", "p1", "v", 3)).state(), PurchaseState.COMPLETED, "stock intacto: 3 unidades disponibles");
            eq(e.mk.purchase(req("f3", "main", "p1", "v", 1)).note(), "OUT_OF_STOCK", "agotado");
        });
        run("idempotencia: mismo purchaseId nunca cobra ni entrega dos veces", () -> {
            Env e = new Env(); e.mk.registerMarket(Market.simple("main", "M", Market.Mode.BUY_SHOP), List.of(e.prod("p1", "coins", 100, -1)));
            e.fund("u", "coins", 1000);
            for (int i = 0; i < 5; i++) eq(e.mk.purchase(req("same", "main", "p1", "u", 1)).state(), PurchaseState.COMPLETED, "estado");
            eq(e.eco.balance("u", "coins"), 900L, "un solo cobro"); eq(e.handler.realDeliveries.get(), 1, "una entrega");
            try { e.mk.purchase(req("same", "main", "p1", "u", 2)); throw new AssertionError("debió rechazar parámetros distintos"); } catch (IllegalArgumentException ok) {}
            ExecutorService ex = Executors.newFixedThreadPool(16); List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < 64; i++) fs.add(ex.submit(() -> e.mk.purchase(req("conc", "main", "p1", "u", 1)))); join(fs); ex.shutdown();
            eq(e.eco.balance("u", "coins"), 800L, "concurrente: un solo cobro"); eq(e.handler.realDeliveries.get(), 2, "dos entregas en total");
        });
        run("stock simultáneo: el último artículo se vende una sola vez", () -> {
            Env e = new Env(); e.mk.registerMarket(Market.simple("bm", "BM", Market.Mode.BLACK_MARKET), List.of(e.prod("rare", "coins", 100, 5)));
            for (int i = 0; i < 50; i++) e.fund("u" + i, "coins", 100);
            ExecutorService ex = Executors.newFixedThreadPool(25); List<Future<PurchaseRecord>> fs = new ArrayList<>();
            for (int i = 0; i < 50; i++) { int n = i; fs.add(ex.submit(() -> e.mk.purchase(req("s" + n, "bm", "rare", "u" + n, 1)))); }
            int ok = 0; for (var f : fs) { try { if (f.get().state() == PurchaseState.COMPLETED) ok++; } catch (Exception x) { throw new RuntimeException(x); } } ex.shutdown();
            eq(ok, 5, "exactamente 5 ventas"); eq(e.handler.realDeliveries.get(), 5, "5 entregas");
            eq(e.eco.totalSupply("coins"), 5000L, "conservación"); eq(e.eco.balance("sink:bm", "coins"), 500L, "recaudado");
        });
        run("entrega fallida: PENDING_RECOVERY y recuperación sin cobrar de nuevo", () -> {
            Env e = new Env(); e.mk.registerMarket(Market.simple("main", "M", Market.Mode.BUY_SHOP), List.of(e.prod("p1", "coins", 100, -1)));
            e.fund("u", "coins", 1000); e.handler.throwIt = true;
            PurchaseRecord r = e.mk.purchase(req("r1", "main", "p1", "u", 1));
            eq(r.state(), PurchaseState.PENDING_RECOVERY, "pendiente");
            eq(e.eco.balance("u", "coins"), 900L, "cobrado una vez"); eq(e.eco.balance("escrow:r1", "coins"), 100L, "en escrow");
            e.handler.throwIt = false; e.clock.now.addAndGet(60_000);
            eq(e.mk.recover(1000), 1, "recuperada"); eq(e.mk.purchase("r1").get().state(), PurchaseState.COMPLETED, "completada");
            eq(e.eco.balance("u", "coins"), 900L, "sin segundo cobro"); eq(e.handler.realDeliveries.get(), 1, "una entrega");
            eq(e.eco.balance("sink:main", "coins"), 100L, "escrow liberado");
        });
        run("recuperación: reintentos máximos y reintento manual", () -> {
            Env e = new Env(); e.mk.registerMarket(Market.simple("main", "M", Market.Mode.BUY_SHOP), List.of(e.prod("p1", "coins", 100, -1)));
            e.fund("u", "coins", 1000); e.handler.failWith = DeliveryResult.Status.RETRY;
            e.mk.purchase(req("m1", "main", "p1", "u", 1));
            for (int i = 0; i < 6; i++) { e.clock.now.addAndGet(60_000); e.mk.recover(1000); }
            check(e.handler.calls.get() <= 3, "tope de reintentos respetado: " + e.handler.calls.get());
            eq(e.mk.purchase("m1").get().state(), PurchaseState.PENDING_RECOVERY, "sigue pendiente");
            e.handler.failWith = null; eq(e.mk.retryDelivery("m1").state(), PurchaseState.COMPLETED, "manual ok");
            eq(e.eco.balance("u", "coins"), 900L, "un cobro");
        });
        run("entrega rechazada definitivamente: reembolso y stock repuesto", () -> {
            Env e = new Env(); e.mk.registerMarket(Market.simple("main", "M", Market.Mode.BUY_SHOP).withTax(500), List.of(e.prod("p1", "coins", 1000, 1)));
            e.fund("u", "coins", 5000); e.handler.failWith = DeliveryResult.Status.FAILED;
            PurchaseRecord r = e.mk.purchase(req("x1", "main", "p1", "u", 1));
            eq(r.state(), PurchaseState.REFUNDED, "reembolsada"); eq(e.eco.balance("u", "coins"), 5000L, "dinero devuelto");
            eq(e.eco.balance("escrow:x1", "coins"), 0L, "escrow"); e.handler.failWith = null;
            eq(e.mk.purchase(req("x2", "main", "p1", "u", 1)).state(), PurchaseState.COMPLETED, "stock repuesto");
            eq(e.mk.refund("x2").state(), PurchaseState.COMPLETED, "no se reembolsa lo completado");
        });
        run("reembolso manual de una entrega atascada", () -> {
            Env e = new Env(); e.mk.registerMarket(Market.simple("main", "M", Market.Mode.BUY_SHOP), List.of(e.prod("p1", "coins", 100, 2)));
            e.fund("u", "coins", 1000); e.handler.throwIt = true; e.mk.purchase(req("g1", "main", "p1", "u", 2));
            eq(e.mk.refund("g1").state(), PurchaseState.REFUNDED, "refund"); eq(e.eco.balance("u", "coins"), 1000L, "saldo");
            eq(e.mk.refund("g1").state(), PurchaseState.REFUNDED, "idempotente"); eq(e.eco.balance("u", "coins"), 1000L, "sin doble reembolso");
            e.handler.throwIt = false;
            eq(e.mk.purchase(req("g2", "main", "p1", "u", 2)).state(), PurchaseState.COMPLETED, "stock restaurado");
        });
        run("recuperación de compras abandonadas (caída a mitad)", () -> {
            Env e = new Env(); e.mk.registerMarket(Market.simple("main", "M", Market.Mode.BUY_SHOP), List.of(e.prod("p1", "coins", 100, 1)));
            e.fund("u", "coins", 1000);
            // Simula caída tras RESERVED: stock reservado, sin pago.
            e.storage.tx(tx -> {
                PurchaseRecord c = PurchaseRecord.created(req("z1", "main", "p1", "u", 1), e.clock.millis());
                tx.putPurchase(c.reserved("coins", 100, 0, "sink:main", e.clock.millis())); tx.setCounter(MarketEngine.stockKey("main", "p1"), 0); return null; });
            eq(e.mk.purchase(req("z2", "main", "p1", "u", 1)).note(), "OUT_OF_STOCK", "reservado bloquea");
            e.clock.now.addAndGet(60_000); eq(e.mk.recover(1000), 1, "limpia");
            eq(e.mk.purchase("z1").get().state(), PurchaseState.FAILED, "abortada");
            eq(e.mk.purchase(req("z3", "main", "p1", "u", 1)).state(), PurchaseState.COMPLETED, "stock liberado");
        });
        run("proveedores externos: productos, validación, aislamiento de fallos", () -> {
            Env e = new Env(); e.mk.registerMarket(Market.simple("pets_market", "Pets", Market.Mode.CUSTOM), List.of());
            e.mk.registerMarket(Market.simple("main", "Main", Market.Mode.BUY_SHOP), List.of(e.prod("p1", "coins", 100, -1)));
            e.mk.registerRewardHandler(new RewardHandler() { public String type() { return "pet"; }
                public DeliveryResult deliver(DeliveryContext c) { return DeliveryResult.delivered(); } });
            final boolean[] broken = {false}; final boolean[] reject = {false};
            ItemProvider ip = new ItemProvider() {
                public String id() { return "pets"; }
                public List<Product> products() { if (broken[0]) throw new RuntimeException("plugin roto");
                    return List.of(Product.builder("legend", "gems", PriceSpec.fixed(500), RewardSpec.of("pet", Map.of("pet", "dragon"))).category("Mascotas").name("Mascota legendaria").build()); }
                public Optional<String> validate(String b, Product p, int q) { if (reject[0]) throw new IllegalStateException("x"); return Optional.empty(); } };
            e.mk.attachProvider("pets_market", ip);
            eq(e.mk.visibleProducts("pets_market").size(), 1, "producto externo publicado");
            eq(e.mk.search("pets_market", "legend", 0, 10).size(), 1, "búsqueda por prefijo");
            eq(e.mk.search("pets_market", "dragon", 0, 10).size(), 0, "búsqueda sin coincidencias");
            e.fund("u", "gems", 500);
            PurchaseRecord r = e.mk.purchase(req("pet1", "pets_market", "legend", "u", 1));
            eq(r.state(), PurchaseState.COMPLETED, "compra externa"); eq(e.eco.balance("u", "gems"), 0L, "gemas"); eq(e.eco.balance("sink:pets_market", "gems"), 500L, "cobro");
            broken[0] = true; e.mk.refreshProvider("pets");
            eq(e.mk.visibleProducts("pets_market").size(), 1, "conserva productos si el proveedor falla");
            reject[0] = true; e.fund("u", "gems", 500);
            eq(e.mk.purchase(req("pet2", "pets_market", "legend", "u", 1)).note(), "PROVIDER_ERROR", "excepción => rechazo");
            eq(e.eco.balance("u", "gems"), 500L, "sin cobro");
            e.fund("u", "coins", 100); eq(e.mk.purchase(req("ok1", "main", "p1", "u", 1)).state(), PurchaseState.COMPLETED, "otros mercados intactos");
            e.mk.detachProvider("pets_market", "pets"); eq(e.mk.visibleProducts("pets_market").size(), 0, "detach");
        });
        run("recarga individual de mercados y desactivar", () -> {
            Env e = new Env(); e.mk.registerMarket(Market.simple("a", "A", Market.Mode.BUY_SHOP), List.of(e.prod("p1", "coins", 100, -1)));
            e.mk.registerMarket(Market.simple("b", "B", Market.Mode.BUY_SHOP), List.of(e.prod("q1", "coins", 100, -1)));
            e.mk.registerMarket(Market.simple("a", "A2", Market.Mode.BUY_SHOP), List.of(e.prod("p2", "coins", 50, -1))); // reload de A
            eq(e.mk.market("a").get().name(), "A2", "a recargado"); eq(e.mk.visibleProducts("a").get(0).id(), "p2", "productos nuevos");
            eq(e.mk.visibleProducts("b").get(0).id(), "q1", "b intacto");
            e.mk.removeProduct("b", "q1"); eq(e.mk.visibleProducts("b").size(), 0, "remove");
            check(e.mk.unregisterMarket("a"), "unregister"); eq(e.mk.market("a").isPresent(), false, "borrado");
        });
        run("múltiples monedas y monedas admitidas", () -> {
            Env e = new Env(); e.mk.registerMarket(Market.simple("m", "M", Market.Mode.BUY_SHOP).withCurrencies(Set.of("gems")),
                List.of(e.prod("pg", "gems", 5, -1), e.prod("pc", "coins", 5, -1)));
            e.fund("u", "gems", 20); e.fund("u", "coins", 20);
            eq(e.mk.purchase(req("g", "m", "pg", "u", 2)).state(), PurchaseState.COMPLETED, "gems ok");
            eq(e.mk.purchase(req("c", "m", "pc", "u", 2)).note(), "CURRENCY_NOT_ACCEPTED", "coins no aceptada");
            eq(e.eco.balance("u", "gems"), 10L, "gems"); eq(e.eco.balance("u", "coins"), 20L, "coins intactas");
            eq(e.eco.totalSupply("gems"), 20L, "supply gems");
        });
        run("acceso: requisitos, falla cerrado y secretos", () -> {
            Env e = new Env(); Product open = e.prod("open", "coins", 10, -1);
            Product vip = Product.builder("vip", "coins", PriceSpec.fixed(10), RewardSpec.of("item", Map.of())).requirements(List.of("perm:vip")).secret(true).build();
            Product weird = Product.builder("weird", "coins", PriceSpec.fixed(10), RewardSpec.of("item", Map.of())).requirements(List.of("quest:x")).build();
            e.mk.registerMarket(Market.simple("bm", "BM", Market.Mode.BLACK_MARKET).withRequirements(List.of("perm:economy.market.blackmarket")), List.of(open, vip, weird));
            e.fund("u", "coins", 100);
            eq(e.mk.purchase(req("n1", "bm", "open", "u", 1)).note(), "ACCESS_DENIED", "sin condición registrada");
            Set<String> perms = ConcurrentHashMap.newKeySet();
            e.mk.registerConditionProvider(new ConditionProvider() { public String type() { return "perm"; } public boolean test(String b, String a) { return perms.contains(b + ":" + a); } });
            eq(e.mk.purchase(req("n2", "bm", "open", "u", 1)).note(), "ACCESS_DENIED", "sin permiso");
            perms.add("u:economy.market.blackmarket");
            eq(e.mk.purchase(req("n3", "bm", "open", "u", 1)).state(), PurchaseState.COMPLETED, "con permiso");
            eq(e.mk.purchase(req("n4", "bm", "vip", "u", 1)).note(), "ACCESS_DENIED", "vip");
            eq(e.mk.purchase(req("n5", "bm", "weird", "u", 1)).note(), "ACCESS_DENIED", "tipo desconocido deniega");
            eq(e.mk.visibleProducts("bm").size(), 2, "secreto oculto en catálogo público");
            perms.add("u:vip"); eq(e.mk.visibleProducts("bm", "u").size(), 3, "secreto visible al elegible");
            eq(e.mk.purchase(req("n6", "bm", "vip", "u", 1)).state(), PurchaseState.COMPLETED, "vip ok");
        });
        run("límites por jugador y ventana de apertura", () -> {
            Env e = new Env(); Product p = Product.builder("lim", "coins", PriceSpec.fixed(10), RewardSpec.of("item", Map.of())).perPlayerLimit(2).stock(100).build();
            long t = e.clock.millis();
            e.mk.registerMarket(Market.simple("m", "M", Market.Mode.OFFERS).withWindow(t + 1000, t + 5000), List.of(p));
            e.fund("u", "coins", 1000); e.fund("v", "coins", 1000);
            eq(e.mk.purchase(req("w0", "m", "lim", "u", 1)).note(), "MARKET_CLOSED", "aún cerrado");
            e.clock.now.addAndGet(2000);
            eq(e.mk.purchase(req("w1", "m", "lim", "u", 2)).state(), PurchaseState.COMPLETED, "2 permitidas");
            eq(e.mk.purchase(req("w2", "m", "lim", "u", 1)).note(), "LIMIT_REACHED", "límite");
            eq(e.mk.purchase(req("w3", "m", "lim", "v", 1)).state(), PurchaseState.COMPLETED, "otro jugador");
            e.clock.now.addAndGet(5000); eq(e.mk.purchase(req("w4", "m", "lim", "v", 1)).note(), "MARKET_CLOSED", "cerrado");
        });
        run("mercado negro: rotación determinista y cambio por ventana", () -> {
            Env e = new Env(); List<Product> pool = new ArrayList<>();
            for (int i = 0; i < 20; i++) pool.add(e.prod("it" + i, "coins", 10, -1));
            pool.add(Product.builder("fixed", "coins", PriceSpec.fixed(10), RewardSpec.of("item", Map.of())).alwaysVisible(true).build());
            e.mk.registerMarket(Market.simple("bm", "BM", Market.Mode.BLACK_MARKET).withRotation(new Rotation(3600, 4)), pool);
            List<String> w1 = ids(e.mk.visibleProducts("bm")); eq(w1.size(), 5, "4 + fijo"); check(w1.contains("fixed"), "fijo siempre");
            eq(ids(e.mk.visibleProducts("bm")), w1, "determinista"); e.fund("u", "coins", 1000);
            String hidden = null; for (int i = 0; i < 20; i++) if (!w1.contains("it" + i)) { hidden = "it" + i; break; }
            eq(e.mk.purchase(req("r0", "bm", hidden, "u", 1)).note(), "NOT_AVAILABLE", "fuera de rotación no se compra");
            boolean changed = false;
            for (int k = 0; k < 10 && !changed; k++) { e.clock.now.addAndGet(3600_000L); changed = !ids(e.mk.visibleProducts("bm")).equals(w1); }
            check(changed, "la rotación cambia entre ventanas");
        });
        run("precios: aleatorio acotado, stock, demanda y descuento", () -> {
            Env e = new Env();
            Product rnd = Product.builder("rnd", "coins", PriceSpec.random(100, 200), RewardSpec.of("item", Map.of())).build();
            for (long w = 0; w < 50; w++) { long u = Pricing.unitPrice("m", rnd, -1, 0, w, 0); check(u >= 100 && u <= 200, "rango " + u); }
            eq(Pricing.unitPrice("m", rnd, -1, 0, 7, 0), Pricing.unitPrice("m", rnd, -1, 0, 7, 0), "estable por ventana");
            Product st = Product.builder("st", "coins", PriceSpec.stock(1000, 5000), RewardSpec.of("item", Map.of())).stock(10).build();
            eq(Pricing.unitPrice("m", st, 10, 0, 0, 0), 1000L, "stock lleno"); eq(Pricing.unitPrice("m", st, 0, 0, 0, 0), 1500L, "stock vacío +50%");
            Product dm = Product.builder("dm", "coins", PriceSpec.demand(1000, 1000, 1300), RewardSpec.of("item", Map.of())).build();
            eq(Pricing.unitPrice("m", dm, -1, 2, 0, 0), 1200L, "demanda 2"); eq(Pricing.unitPrice("m", dm, -1, 50, 0, 0), 1300L, "tope");
            Product ds = Product.builder("ds", "coins", PriceSpec.fixed(1000), RewardSpec.of("item", Map.of())).discount(2500, 100).build();
            eq(Pricing.unitPrice("m", ds, -1, 0, 0, 50), 750L, "descuento vigente"); eq(Pricing.unitPrice("m", ds, -1, 0, 0, 150), 1000L, "descuento vencido");
            eq(Pricing.tax(1005, 1000), 101L, "impuesto half-up");
            e.mk.registerMarket(Market.simple("m", "M", Market.Mode.OFFERS), List.of(Product.builder("d", "coins", PriceSpec.demand(100, 5000, 0), RewardSpec.of("item", Map.of())).build()));
            e.fund("u", "coins", 10_000);
            eq(e.mk.purchase(req("d1", "m", "d", "u", 1)).total(), 100L, "1ª al precio base");
            eq(e.mk.purchase(req("d2", "m", "d", "u", 1)).total(), 150L, "2ª sube por demanda");
        });
        run("restock administrativo", () -> {
            Env e = new Env(); e.mk.registerMarket(Market.simple("m", "M", Market.Mode.BUY_SHOP), List.of(e.prod("p", "coins", 1, 1)));
            e.fund("u", "coins", 10); e.mk.purchase(req("a", "m", "p", "u", 1));
            eq(e.mk.purchase(req("b", "m", "p", "u", 1)).note(), "OUT_OF_STOCK", "agotado"); e.mk.restock("m", "p", 3);
            eq(e.mk.purchase(req("c", "m", "p", "u", 3)).state(), PurchaseState.COMPLETED, "repuesto");
        });
        run("listeners y estados", () -> {
            Env e = new Env(); List<PurchaseState> seen = new CopyOnWriteArrayList<>();
            e.mk.addListener(r -> seen.add(r.state())); e.mk.addListener(r -> { throw new RuntimeException("listener roto"); });
            e.mk.registerMarket(Market.simple("m", "M", Market.Mode.BUY_SHOP), List.of(e.prod("p", "coins", 1, -1))); e.fund("u", "coins", 10);
            e.mk.purchase(req("l", "m", "p", "u", 1));
            eq(seen, List.of(PurchaseState.VALIDATING, PurchaseState.RESERVED, PurchaseState.PAID, PurchaseState.DELIVERING, PurchaseState.COMPLETED), "secuencia");
            eq(e.mk.purchasesOf("u", 5).size(), 1, "historial");
        });
        run("paginación e índice con miles de productos", () -> {
            Env e = new Env(); List<Product> ps = new ArrayList<>();
            for (int i = 0; i < 5000; i++) ps.add(e.prod("item" + i, "coins", 1, -1));
            e.mk.registerMarket(Market.simple("big", "Big", Market.Mode.BUY_SHOP), ps);
            eq(e.mk.registry().page("big", "c", e.clock.millis(), 2, 45).size(), 45, "página");
            eq(e.mk.search("big", "prod item49", 0, 1000).size(), 111, "búsqueda AND por prefijo (item49, item490-499, item4900-4999)");
            eq(e.mk.search("big", "prod item49", 0, 100).size(), 100, "paginada");
            eq(e.mk.registry().page("big", "c", e.clock.millis(), 111, 45).size(), 5, "última página");
        });
        System.out.println("\nPasaron " + passed + ", fallaron " + failures.size());
        for (String f : failures) System.out.println("  FALLO " + f);
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    static List<String> ids(List<Product> l) { List<String> o = new ArrayList<>(); for (Product p : l) o.add(p.id()); return o; }
    static void join(List<? extends Future<?>> fs) { for (Future<?> f : fs) { try { f.get(); } catch (Exception x) { throw new RuntimeException(x); } } }
    static void run(String name, Runnable r) {
        try { r.run(); passed++; System.out.println("ok   " + name); }
        catch (Throwable t) { failures.add(name + ": " + t); System.out.println("FAIL " + name + ": " + t); t.printStackTrace(System.out); }
    }
}
