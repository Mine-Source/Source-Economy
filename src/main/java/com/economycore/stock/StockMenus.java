package com.economycore.stock;

import com.economycore.company.CompanyModels.Company;
import com.economycore.core.EconomyCore;
import com.economycore.core.Fmt;
import com.economycore.core.Money;
import com.economycore.core.Msg;
import com.economycore.core.OrderBook.Level;
import com.economycore.core.OrderBook.Order;
import com.economycore.core.OrderBook.PlaceResult;
import com.economycore.core.Tasks;
import com.economycore.gui.Btn;
import com.economycore.gui.ButtonMenu;
import com.economycore.gui.ConfirmMenu;
import com.economycore.gui.Form;
import com.economycore.gui.Items;
import com.economycore.gui.PagedMenu;
import com.economycore.gui.Prompt;
import com.economycore.stock.StockService.Detail;
import com.economycore.stock.StockService.Holding;
import com.economycore.stock.StockService.IndexInfo;
import com.economycore.stock.StockService.Row;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Material;
import org.bukkit.entity.Player;

public final class StockMenus {
    private final EconomyCore pl;
    private final StockService svc;

    public StockMenus(EconomyCore pl, StockService svc) {
        this.pl = pl;
        this.svc = svc;
    }

    private static Integer parseInt(String s) {
        try { int n = Integer.parseInt(s.trim()); return n > 0 ? n : null; } catch (NumberFormatException e) { return null; }
    }

    private static String chg(BigDecimal c) {
        return (c.signum() >= 0 ? "&a+" : "&c") + c.toPlainString() + "%";
    }

    private static Material iconOf(Company c) {
        Material m = c.icon() == null ? null : Material.matchMaterial(c.icon());
        return m == null ? Material.GOLD_INGOT : m;
    }

    private static double[] arr(List<BigDecimal> l) {
        double[] d = new double[l.size()];
        for (int i = 0; i < d.length; i++) d[i] = l.get(i).doubleValue();
        return d;
    }

    private void done(Player p, CompletableFuture<?> f, String okKey, Runnable again, Object... args) {
        f.whenComplete((r, t) -> Tasks.sync(() -> {
            if (t != null) Msg.error(p, t); else if (okKey != null) Msg.send(p, okKey, args);
            again.run();
        }));
    }

    public void main(Player p, Runnable back) {
        boolean admin = p.hasPermission("economycore.stock.admin");
        Runnable self = () -> main(p, back);
        svc.index().whenComplete((IndexInfo ix, Throwable t0) -> Tasks.sync(() -> {
            new PagedMenu<Row>(pl, p, "&6Bolsa de valores", svc::listed, r -> {
                Company c = r.c();
                String cur = c.currency();
                return Items.of(iconOf(c), "&e" + c.ticker() + " &7- &f" + c.name(),
                        "&7Precio: &f" + Money.fmt(cur, c.price()) + " " + chg(r.changePct()),
                        "&7Volumen 24h: &f" + r.volume24(),
                        "&7Compra: " + (r.bid() == null ? "&8-" : Money.fmt(cur, r.bid())) + " &7Venta: " + (r.ask() == null ? "&8-" : Money.fmt(cur, r.ask())),
                        "&7IPO disponible: &f" + c.ipoRemaining(), "&eClic para operar");
            }, (r, ci) -> detail(p, r.c().id(), self))
                    .searchable(r -> r.c().ticker() + " " + r.c().name() + " " + r.c().industry())
                    .extras(() -> {
                        List<Btn> l = new ArrayList<>();
                        if (ix != null) {
                            l.add(Btn.of(47, Items.of(Material.NETHER_STAR, "&bÍndice ECO: &f" + ix.value().toPlainString() + " " + chg(ix.changePct()),
                                    "&7Volumen 24h: &f" + ix.volume24(), "&7Evolución: " + Fmt.spark(arr(ix.history()))), () -> {}));
                        }
                        l.add(Btn.of(51, Items.of(Material.CHEST, "&6Mi portafolio", "&7Acciones, coste medio y ganancias"), () -> portfolio(p, self)));
                        l.add(Btn.of(50, Items.of(Material.WRITABLE_BOOK, "&bMis órdenes"), () -> myOrders(p, self)));
                        if (admin) l.add(Btn.of(44, Items.of(Material.COMMAND_BLOCK, "&cAdmin: listar/IPO empresa", "&7Cotizar una empresa por su id"), () -> adminList(p, self)));
                        return l;
                    }).back(back).load();
        }));
    }

    private void adminList(Player p, Runnable back) {
        Form.of(p).ask("id", "Id de la empresa").ask("shares", "Acciones a ofrecer en IPO").ask("price", "Precio de IPO").onCancel(back).then(a -> {
            Integer n = parseInt(a.get("shares"));
            BigDecimal pr = Money.parse(a.get("price"));
            if (n == null || pr == null) { Msg.send(p, "invalid-number"); back.run(); return; }
            done(p, svc.setIpo(p, a.get("id").toLowerCase(), n, pr, true), "ipo-set", back);
        });
    }

    public void detail(Player p, String id, Runnable back) {
        svc.detail(id).whenComplete((Detail d, Throwable t) -> Tasks.sync(() -> {
            if (t != null) { Msg.error(p, t); return; }
            Runnable self = () -> detail(p, id, back);
            Company c = d.c();
            String cur = c.currency();
            boolean admin = p.hasPermission("economycore.stock.admin");
            boolean ceo = c.ceo().equals(p.getUniqueId().toString());
            new ButtonMenu(pl, p, "&6" + c.ticker() + " - " + c.name(), 6, true, () -> {
                List<Btn> l = new ArrayList<>();
                l.add(Btn.of(4, Items.of(iconOf(c), "&e" + c.name() + " &7(" + c.ticker() + ")",
                        "&7Precio: &f" + Money.fmt(cur, c.price()) + " " + chg(d.changePct()),
                        "&7Capitalización: &f" + Money.fmt(cur, d.marketCap()),
                        "&7Acciones totales: &f" + c.totalShares(), "&7Volumen 24h: &f" + d.volume24(),
                        "&7Historial: " + Fmt.spark(arr(d.history())), "&7" + c.description()), () -> {}));
                l.add(Btn.of(10, Items.of(Material.LIME_CONCRETE, "&aCompra de mercado", "&7Compra al instante a las mejores ventas"), () ->
                        Prompt.ask(p, "Cantidad de acciones a comprar", s -> {
                            Integer n = parseInt(s);
                            if (n == null) { Msg.send(p, "invalid-number"); self.run(); return; }
                            order(p, svc.place(p, id, "BUY", null, n), cur, self);
                        }, self)));
                l.add(Btn.of(16, Items.of(Material.ORANGE_CONCRETE, "&6Venta de mercado", "&7Vende al instante a las mejores compras"), () ->
                        Prompt.ask(p, "Cantidad de acciones a vender", s -> {
                            Integer n = parseInt(s);
                            if (n == null) { Msg.send(p, "invalid-number"); self.run(); return; }
                            order(p, svc.place(p, id, "SELL", null, n), cur, self);
                        }, self)));
                l.add(Btn.of(19, Items.of(Material.LIME_DYE, "&aOrden límite de compra", "&7El dinero queda reservado"), () ->
                        Form.of(p).ask("price", "Precio por acción").ask("amt", "Cantidad").onCancel(self).then(a -> limit(p, id, "BUY", a.get("price"), a.get("amt"), cur, self))));
                l.add(Btn.of(25, Items.of(Material.ORANGE_DYE, "&6Orden límite de venta", "&7Las acciones quedan reservadas"), () ->
                        Form.of(p).ask("price", "Precio por acción").ask("amt", "Cantidad").onCancel(self).then(a -> limit(p, id, "SELL", a.get("price"), a.get("amt"), cur, self))));
                if (c.ipoRemaining() > 0) {
                    l.add(Btn.of(22, Items.of(Material.EMERALD, "&aComprar en IPO", "&7Precio IPO: &f" + Money.fmt(cur, c.ipoPrice()), "&7Disponibles: &f" + c.ipoRemaining()), () ->
                            Prompt.ask(p, "Acciones a comprar en IPO", s -> {
                                Integer n = parseInt(s);
                                if (n == null) { Msg.send(p, "invalid-number"); self.run(); return; }
                                ConfirmMenu.ask(pl, p, "&aComprar IPO", List.of("&7" + n + " x " + Money.fmt(cur, c.ipoPrice())), () ->
                                        svc.buyIpo(p, id, n).whenComplete((r, t2) -> Tasks.sync(() -> {
                                            if (t2 != null) Msg.error(p, t2); else Msg.send(p, "ipo-bought", n, c.ticker(), Money.fmt(cur, r));
                                            self.run();
                                        })), self);
                            }, self)));
                }
                for (int i = 0; i < 4; i++) {
                    if (i < d.asks().size()) l.add(Btn.of(31 - i, level(d.asks().get(i), cur, true), () -> {}));
                    if (i < d.bids().size()) l.add(Btn.of(33 + i, level(d.bids().get(i), cur, false), () -> {}));
                }
                l.add(Btn.of(31, Items.of(Material.PAPER, "&fLibro de órdenes", "&cIzquierda: ventas", "&aDerecha: compras"), () -> {}));
                l.add(Btn.of(38, Items.of(Material.WRITABLE_BOOK, "&bMis órdenes"), () -> myOrders(p, self)));
                if (ceo || admin) {
                    l.add(Btn.of(42, Items.of(Material.NETHER_STAR, "&bConfigurar IPO", "&7Ofrecer acciones nuevas al público"), () ->
                            Form.of(p).ask("shares", "Acciones a ofrecer").ask("price", "Precio de IPO").onCancel(self).then(a -> {
                                Integer n = parseInt(a.get("shares"));
                                BigDecimal pr = Money.parse(a.get("price"));
                                if (n == null || pr == null) { Msg.send(p, "invalid-number"); self.run(); return; }
                                done(p, svc.setIpo(p, id, n, pr, admin), "ipo-set", self);
                            })));
                    l.add(Btn.of(43, Items.of(c.listed() ? Material.RED_CONCRETE : Material.GREEN_CONCRETE, c.listed() ? "&cRetirar de bolsa" : "&aCotizar en bolsa"), () ->
                            done(p, svc.setListed(p, id, !c.listed(), admin), "saved", back)));
                }
                return l;
            }).back(back).open();
        }));
    }

    private org.bukkit.inventory.ItemStack level(Level lv, String cur, boolean ask) {
        return Items.of(ask ? Material.RED_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE, (ask ? "&cVenden: " : "&aCompran: ") + Money.fmt(cur, lv.price()), "&7Acciones: &f" + lv.qty());
    }

    private void limit(Player p, String id, String side, String price, String amt, String cur, Runnable again) {
        BigDecimal pr = Money.parse(price);
        Integer n = parseInt(amt);
        if (pr == null || n == null) { Msg.send(p, "invalid-number"); again.run(); return; }
        order(p, svc.place(p, id, side, pr, n), cur, again);
    }

    private void order(Player p, CompletableFuture<PlaceResult> f, String cur, Runnable again) {
        f.whenComplete((r, t) -> Tasks.sync(() -> {
            if (t != null) Msg.error(p, t);
            else Msg.send(p, "stock-placed", r.filled(), r.remaining(), Money.fmt(cur, r.money()));
            again.run();
        }));
    }

    public void portfolio(Player p, Runnable back) {
        new PagedMenu<Holding>(pl, p, "&6Mi portafolio", () -> svc.portfolio(p.getUniqueId()), h -> {
            BigDecimal value = h.price().multiply(BigDecimal.valueOf(h.shares()));
            BigDecimal cost = h.avgCost().multiply(BigDecimal.valueOf(h.shares()));
            BigDecimal pnl = value.subtract(cost);
            String pct = cost.signum() == 0 ? "-" : pnl.multiply(BigDecimal.valueOf(100)).divide(cost, 2, RoundingMode.HALF_UP).toPlainString() + "%";
            return Items.of(Material.PAPER, "&e" + h.ticker() + " &7- &f" + h.name(), "&7Acciones: &f" + h.shares(),
                    "&7Coste medio: &f" + Money.fmt(h.currency(), h.avgCost()), "&7Precio actual: &f" + Money.fmt(h.currency(), h.price()),
                    "&7Valor: &f" + Money.fmt(h.currency(), value), "&7Resultado: " + (pnl.signum() >= 0 ? "&a" : "&c") + Money.fmt(h.currency(), pnl) + " (" + pct + ")", "&eClic para operar");
        }, (h, ci) -> detail(p, h.company(), () -> portfolio(p, back)))
                .searchable(h -> h.ticker() + " " + h.name()).back(back).load();
    }

    public void myOrders(Player p, Runnable back) {
        new PagedMenu<Order>(pl, p, "&6Mis órdenes de bolsa", () -> svc.myOrders(p.getUniqueId()), o -> {
            String cur = svc.currencyFor(o.product());
            return Items.of(o.side().equals("BUY") ? Material.LIME_DYE : Material.ORANGE_DYE, (o.side().equals("BUY") ? "&aCompra " : "&6Venta ") + o.product().toUpperCase(),
                    "&7Precio: " + Money.fmt(cur, o.price()), "&7Ejecutadas: &f" + o.filled() + "/" + o.amount(), "&7Creada: &f" + Fmt.date(o.created()), "&cClic para cancelar");
        }, (o, ci) -> ConfirmMenu.ask(pl, p, "&cCancelar orden", List.of("&7Se libera lo reservado"), () ->
                svc.cancel(p, o.id(), false).whenComplete((r, t) -> Tasks.sync(() -> {
                    if (t != null) Msg.error(p, t); else Msg.send(p, "order-cancelled");
                    myOrders(p, back);
                })), () -> myOrders(p, back))).back(back).load();
    }
}
