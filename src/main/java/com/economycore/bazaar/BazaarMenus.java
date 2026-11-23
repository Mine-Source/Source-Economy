package com.economycore.bazaar;

import com.economycore.bazaar.BazaarService.Book;
import com.economycore.bazaar.BazaarService.Info;
import com.economycore.core.EconomyCore;
import com.economycore.core.Fmt;
import com.economycore.core.Inv;
import com.economycore.core.Money;
import com.economycore.core.Msg;
import com.economycore.core.OrderBook.Level;
import com.economycore.core.OrderBook.Order;
import com.economycore.core.Tasks;
import com.economycore.eco.EconomyModule;
import com.economycore.gui.Btn;
import com.economycore.gui.ButtonMenu;
import com.economycore.gui.ConfirmMenu;
import com.economycore.gui.Form;
import com.economycore.gui.Items;
import com.economycore.gui.PagedMenu;
import com.economycore.gui.Prompt;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class BazaarMenus {
    private final EconomyCore pl;
    private final BazaarService svc;

    public BazaarMenus(EconomyCore pl, BazaarService svc) {
        this.pl = pl;
        this.svc = svc;
    }

    private static Material mat(String id) {
        Material m = Material.matchMaterial(id);
        return m == null ? Material.BARRIER : m;
    }

    private static Integer parseInt(String s) {
        try { int n = Integer.parseInt(s.trim()); return n > 0 ? n : null; } catch (NumberFormatException e) { return null; }
    }

    public void main(Player p, Runnable back) {
        boolean admin = p.hasPermission("economycore.bazaar.admin");
        Runnable self = () -> main(p, back);
        new PagedMenu<Info>(pl, p, "&6Bazaar", svc::infos, i -> {
            String cur = i.product().currency();
            return Items.of(mat(i.product().id()), "&e" + i.product().name(),
                    "&7Mejor oferta de venta: " + (i.bestAsk() == null ? "&8-" : Money.fmt(cur, i.bestAsk())),
                    "&7Mejor oferta de compra: " + (i.bestBid() == null ? "&8-" : Money.fmt(cur, i.bestBid())),
                    "&7Último precio: " + (i.last() == null ? "&8-" : Money.fmt(cur, i.last())), "&eClic para operar");
        }, (i, ci) -> product(p, i.product().id(), self))
                .searchable(i -> i.product().name() + " " + i.product().id())
                .extras(() -> {
                    List<Btn> l = new ArrayList<>();
                    l.add(Btn.of(47, Items.of(Material.WRITABLE_BOOK, "&bMis órdenes", "&7Órdenes abiertas y cancelación"), () -> myOrders(p, self)));
                    l.add(Btn.of(51, Items.of(Material.ENDER_CHEST, "&6Reclamar compras", "&7Los objetos llegan a tu buzón"), () -> ((EconomyModule) pl.module("economy")).menus().mailbox(p, self)));
                    if (admin) l.add(Btn.of(50, Items.of(Material.EMERALD, "&aAñadir producto", "&7Material comerciable en el bazaar"), () ->
                            Prompt.ask(p, "Material (ej: DIAMOND) o escribe 'mano' para usar el objeto en tu mano", s -> {
                                Material m = s.equalsIgnoreCase("mano") && Inv.hand(p) != null ? Inv.hand(p).getType() : Material.matchMaterial(s.trim());
                                if (m == null || !m.isItem()) { Msg.send(p, "invalid-material"); self.run(); return; }
                                svc.addProduct(p.getName(), m, pl.currencies().defId()).whenComplete((r, t) -> Tasks.sync(() -> { if (t != null) Msg.error(p, t); self.run(); }));
                            }, self)));
                    return l;
                }).back(back).load();
    }

    private ItemStack levelIcon(Level l, String cur, boolean ask) {
        return Items.of(ask ? Material.RED_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE, (ask ? "&cVenden: " : "&aCompran: ") + Money.fmt(cur, l.price()), "&7Cantidad: &f" + l.qty());
    }

    public void product(Player p, String id, Runnable back) {
        String cur = svc.currencyOf(id);
        svc.bookOf(id).thenAccept((Book b) -> Tasks.sync(() -> {
            Runnable self = () -> product(p, id, back);
            boolean admin = p.hasPermission("economycore.bazaar.admin");
            ItemStack tmpl = new ItemStack(mat(id));
            new ButtonMenu(pl, p, "&6Bazaar: " + Inv.pretty(mat(id)), 5, true, () -> {
                List<Btn> l = new ArrayList<>();
                l.add(Btn.of(4, Items.of(mat(id), "&e" + Inv.pretty(mat(id)), "&7Último precio: " + (b.last() == null ? "&8-" : Money.fmt(cur, b.last())), "&7Tienes: &f" + Inv.count(p, tmpl)), () -> {}));
                l.add(Btn.of(11, Items.of(Material.LIME_CONCRETE, "&aCompra instantánea", "&7Compra a las mejores ofertas de venta", "&7Mejor precio: " + (b.asks().isEmpty() ? "&8-" : Money.fmt(cur, b.asks().get(0).price()))), () ->
                        Prompt.ask(p, "Cantidad a comprar al instante", s -> {
                            Integer n = parseInt(s);
                            if (n == null) { Msg.send(p, "invalid-number"); self.run(); return; }
                            exec(p, svc.place(p, id, "BUY", null, n), "BUY", self);
                        }, self)));
                l.add(Btn.of(15, Items.of(Material.ORANGE_CONCRETE, "&6Venta instantánea", "&7Vende a las mejores ofertas de compra", "&7Mejor precio: " + (b.bids().isEmpty() ? "&8-" : Money.fmt(cur, b.bids().get(0).price()))), () ->
                        Prompt.ask(p, "Cantidad a vender al instante (tienes " + Inv.count(p, tmpl) + ")", s -> {
                            Integer n = parseInt(s);
                            if (n == null) { Msg.send(p, "invalid-number"); self.run(); return; }
                            exec(p, svc.place(p, id, "SELL", null, n), "SELL", self);
                        }, self)));
                l.add(Btn.of(20, Items.of(Material.LIME_DYE, "&aOrden de compra", "&7Fija tu precio; el dinero se reserva", "&7y se ejecuta automáticamente"), () ->
                        Form.of(p).ask("price", "Precio por unidad").ask("amt", "Cantidad").onCancel(self).then(a -> {
                            BigDecimal pr = Money.parse(a.get("price"));
                            Integer n = parseInt(a.get("amt"));
                            if (pr == null || n == null) { Msg.send(p, "invalid-number"); self.run(); return; }
                            exec(p, svc.place(p, id, "BUY", pr, n), "BUY", self);
                        })));
                l.add(Btn.of(24, Items.of(Material.ORANGE_DYE, "&6Orden de venta", "&7Fija tu precio; los objetos se reservan", "&7y se venden automáticamente"), () ->
                        Form.of(p).ask("price", "Precio por unidad").ask("amt", "Cantidad (tienes " + Inv.count(p, tmpl) + ")").onCancel(self).then(a -> {
                            BigDecimal pr = Money.parse(a.get("price"));
                            Integer n = parseInt(a.get("amt"));
                            if (pr == null || n == null) { Msg.send(p, "invalid-number"); self.run(); return; }
                            exec(p, svc.place(p, id, "SELL", pr, n), "SELL", self);
                        })));
                for (int i = 0; i < 4; i++) {
                    if (i < b.asks().size()) l.add(Btn.of(30 - i, levelIcon(b.asks().get(i), cur, true), () -> {}));
                    if (i < b.bids().size()) l.add(Btn.of(32 + i, levelIcon(b.bids().get(i), cur, false), () -> {}));
                }
                l.add(Btn.of(31, Items.of(Material.PAPER, "&fLibro de órdenes", "&cIzquierda: ventas", "&aDerecha: compras"), () -> {}));
                l.add(Btn.of(38, Items.of(Material.WRITABLE_BOOK, "&bMis órdenes"), () -> myOrders(p, self)));
                if (admin) l.add(Btn.of(42, Items.of(Material.TNT, "&cQuitar producto"), () ->
                        ConfirmMenu.ask(pl, p, "&cQuitar del bazaar", List.of("&7" + Inv.pretty(mat(id))), () ->
                                svc.removeProduct(p.getName(), id).whenComplete((r, t) -> Tasks.sync(() -> { if (t != null) Msg.error(p, t); back.run(); })), self)));
                return l;
            }).back(back).open();
        }));
    }

    private void exec(Player p, java.util.concurrent.CompletableFuture<com.economycore.core.OrderBook.PlaceResult> f, String side, Runnable again) {
        f.whenComplete((r, t) -> Tasks.sync(() -> {
            if (t != null) Msg.error(p, t);
            else {
                String cur = pl.currencies().defId();
                Msg.send(p, "bazaar-placed", side.equals("BUY") ? "compra" : "venta", r.filled(), r.remaining(), Money.fmt(cur, r.money()));
            }
            again.run();
        }));
    }

    public void myOrders(Player p, Runnable back) {
        new PagedMenu<Order>(pl, p, "&6Mis órdenes", () -> svc.myOrders(p.getUniqueId()), o -> {
            String cur = svc.currencyOf(o.product());
            return Items.of(mat(o.product()), (o.side().equals("BUY") ? "&aCompra " : "&6Venta ") + Inv.pretty(mat(o.product())),
                    "&7Precio: " + Money.fmt(cur, o.price()), "&7Cantidad: &f" + o.filled() + "/" + o.amount() + " &7ejecutadas", "&7Creada: &f" + Fmt.date(o.created()), "&cClic para cancelar");
        }, (o, ci) -> ConfirmMenu.ask(pl, p, "&cCancelar orden", List.of("&7Se devuelve lo reservado"), () ->
                svc.cancel(p, o.id(), false).whenComplete((r, t) -> Tasks.sync(() -> { if (t != null) Msg.error(p, t); else Msg.send(p, "order-cancelled"); myOrders(p, back); })), () -> myOrders(p, back)))
                .back(back).load();
    }
}
