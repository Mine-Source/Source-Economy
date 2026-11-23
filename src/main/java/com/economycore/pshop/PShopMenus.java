package com.economycore.pshop;

import com.economycore.core.EcoException;
import com.economycore.core.EconomyCore;
import com.economycore.core.Inv;
import com.economycore.core.Money;
import com.economycore.core.Msg;
import com.economycore.core.Tasks;
import com.economycore.gui.Btn;
import com.economycore.gui.ButtonMenu;
import com.economycore.gui.ConfirmMenu;
import com.economycore.gui.EditMenu;
import com.economycore.gui.Form;
import com.economycore.gui.Items;
import com.economycore.gui.PagedMenu;
import com.economycore.gui.Prompt;
import com.economycore.gui.Setters;
import com.economycore.pshop.PShopService.Item;
import com.economycore.pshop.PShopService.Shop;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class PShopMenus {
    private final EconomyCore pl;
    private final PShopService svc;

    public PShopMenus(EconomyCore pl, PShopService svc) {
        this.pl = pl;
        this.svc = svc;
    }

    private static Material mat(String n, Material def) {
        Material m = n == null ? null : Material.matchMaterial(n);
        return m == null ? def : m;
    }

    private static Integer pint(String s) {
        try { int n = Integer.parseInt(s.trim()); return n > 0 ? n : null; } catch (NumberFormatException e) { return null; }
    }

    private ItemStack shopIcon(Shop s, boolean mineView) {
        return Items.of(mat(s.icon(), Material.CHEST), "&e" + s.name(), "&7Dueño: &f" + s.ownerName(), s.description() == null || s.description().isEmpty() ? "&8Sin descripción" : "&7" + s.description(),
                "&7Productos: &f" + s.items(), s.open() ? "&aAbierta" : "&cCerrada", mineView ? "&eClic para administrar" : "&eClic para visitar");
    }

    public void list(Player p, Runnable back) {
        Runnable self = () -> list(p, back);
        new PagedMenu<Shop>(pl, p, "&6Tiendas de jugadores", svc::openShops, s -> shopIcon(s, false), (s, ci) -> view(p, s.id(), self))
                .searchable(s -> s.name() + " " + s.ownerName() + " " + s.description())
                .extras(() -> {
                    List<Btn> l = new ArrayList<>();
                    l.add(Btn.of(47, Items.of(Material.WRITABLE_BOOK, "&bMis tiendas", "&7Administra tus tiendas"), () -> mine(p, self)));
                    if (p.hasPermission("economycore.pshop.create")) l.add(Btn.of(51, Items.of(Material.EMERALD, "&aCrear tienda", "&7Costo: " + Money.fmt(pl.currencies().defId(), Money.dec(pl.getConfig().getString("playershops.creation-cost", "0"), BigDecimal.ZERO))), () -> createShop(p, self)));
                    return l;
                }).back(back).load();
    }

    private void createShop(Player p, Runnable back) {
        if (!p.hasPermission("economycore.pshop.create")) { Msg.send(p, "no-permission"); return; }
        Prompt.ask(p, "Nombre de tu tienda", n -> svc.create(p, n).whenComplete((r, t) -> Tasks.sync(() -> {
            if (t != null) { Msg.error(p, t); back.run(); } else manage(p, r, back);
        })), back);
    }

    public void mine(Player p, Runnable back) {
        Runnable self = () -> mine(p, back);
        new PagedMenu<Shop>(pl, p, "&6Mis tiendas", () -> svc.mine(p.getUniqueId()), s -> shopIcon(s, true), (s, ci) -> manage(p, s.id(), self))
                .extras(() -> List.of(Btn.of(47, Items.of(Material.EMERALD, "&aCrear tienda"), () -> createShop(p, self)))).back(back).load();
    }

    public void view(Player p, long shopId, Runnable back) {
        svc.get(shopId).thenAccept(s -> Tasks.sync(() -> {
            if (s == null) { Msg.send(p, "pshop-gone"); back.run(); return; }
            Runnable self = () -> view(p, shopId, back);
            new PagedMenu<Item>(pl, p, "&6" + s.name(), () -> svc.items(shopId, false), i -> {
                ItemStack base = Inv.decodeOr(i.itemData(), i.material());
                base.setAmount(Math.max(1, Math.min(i.stock(), base.getMaxStackSize())));
                return Items.withLore(base, "&7Precio: " + Money.fmt(s.currency(), i.price()) + " c/u", "&7Stock: &f" + i.stock(), "&eClic para comprar");
            }, (i, ci) -> buyMenu(p, s, i.id(), self)).searchable(i -> i.itemName() + " " + i.material()).back(back).load();
        }));
    }

    private void buyMenu(Player p, Shop s, long itemId, Runnable back) {
        svc.itemById(itemId).thenAccept(i -> Tasks.sync(() -> {
            if (i == null) { Msg.send(p, "pshop-gone"); back.run(); return; }
            Runnable self = () -> buyMenu(p, s, itemId, back);
            new ButtonMenu(pl, p, "&6Comprar: " + i.itemName(), 3, true, () -> {
                List<Btn> l = new ArrayList<>();
                ItemStack base = Inv.decodeOr(i.itemData(), i.material());
                l.add(Btn.of(4, Items.withLore(base, "&7Precio: " + Money.fmt(s.currency(), i.price()), "&7Stock: &f" + i.stock()), () -> {}));
                int[] q = {1, 8, 16, 32, 64};
                for (int k = 0; k < q.length; k++) {
                    int n = q[k];
                    l.add(Btn.of(10 + k, Items.of(Material.LIME_STAINED_GLASS_PANE, "&aComprar x" + n, "&7Total: " + Money.fmt(s.currency(), i.price().multiply(BigDecimal.valueOf(n)))), () -> buy(p, itemId, n, self)));
                }
                l.add(Btn.of(16, Items.of(Material.WRITABLE_BOOK, "&bOtra cantidad"), () -> Prompt.ask(p, "Cantidad", t -> {
                    Integer n = pint(t);
                    if (n == null) { Msg.send(p, "invalid-number"); self.run(); } else buy(p, itemId, n, self);
                }, self)));
                return l;
            }).back(back).open();
        }));
    }

    private void buy(Player p, long itemId, int qty, Runnable again) {
        svc.buy(p, itemId, qty).whenComplete((r, t) -> Tasks.sync(() -> {
            if (t != null) Msg.error(p, t); else Msg.send(p, "pshop-bought", qty, r.itemName(), Money.fmt(r.currency(), r.total()));
            again.run();
        }));
    }

    // ---------- management ----------
    public void manage(Player p, long shopId, Runnable back) {
        svc.get(shopId).thenAccept(s -> Tasks.sync(() -> {
            if (s == null) { back.run(); return; }
            boolean owner = s.owner().equals(p.getUniqueId().toString());
            Runnable reload = () -> manage(p, shopId, back);
            EditMenu e = new EditMenu(pl, p, "&6Tienda: " + s.name(), reload);
            if (owner) {
                e.field(Material.NAME_TAG, "Nombre", s.name(), "Nuevo nombre", Setters.text("pshops", "id", shopId, "name", false));
                e.field(Material.BOOK, "Descripción", s.description() == null ? "" : s.description(), "Nueva descripción", Setters.text("pshops", "id", shopId, "description", false));
                e.field(Material.ITEM_FRAME, "Icono", s.icon(), "Material (o 'mano')", t -> {
                    Material m = t.equalsIgnoreCase("mano") && Inv.hand(p) != null ? Inv.hand(p).getType() : Material.matchMaterial(t.trim());
                    return m == null ? CompletableFuture.failedFuture(new EcoException("invalid-material")) : EditMenu.update("UPDATE pshops SET icon=? WHERE id=?", m.name(), shopId);
                });
                e.toggle(Material.LIME_DYE, "Tienda abierta", s.open(), () -> Setters.flip("pshops", "id", shopId, "open"));
            }
            e.action(Material.CHEST, "&eProductos y stock", List.of("&7Precios, stock y retiros"), c -> items(p, s, reload));
            e.action(Material.EMERALD, "&aAñadir objeto de la mano", List.of("&7Pone a la venta lo que sostienes"), c -> addFromHand(p, shopId, reload));
            e.action(Material.COMPASS, "&bVisitar como cliente", List.of(), c -> view(p, shopId, reload));
            if (owner) {
                e.action(Material.PLAYER_HEAD, "&dGestores", List.of("&7Quién puede editar productos"), c -> managers(p, shopId, reload));
                e.action(Material.TNT, "&cEliminar tienda", List.of("&7El stock vuelve a tu buzón"), c -> ConfirmMenu.ask(pl, p, "&cEliminar " + s.name(), List.of("&7Se devuelve todo el stock"), () ->
                        svc.delete(p, shopId, false).whenComplete((r, t) -> Tasks.sync(() -> { if (t != null) Msg.error(p, t); back.run(); })), reload));
            }
            e.back(back).open();
        }));
    }

    private void addFromHand(Player p, long shopId, Runnable back) {
        ItemStack hand = Inv.hand(p);
        if (hand == null) { Msg.send(p, "hold-item"); return; }
        ItemStack tmpl = hand.clone();
        Form.of(p).ask("price", "Precio por unidad").ask("qty", "Cantidad a poner en stock (tienes " + Inv.count(p, tmpl) + ")").onCancel(back).then(a -> {
            BigDecimal price = Money.parse(a.get("price"));
            Integer qty = pint(a.get("qty"));
            if (price == null || price.signum() <= 0 || qty == null) { Msg.send(p, "invalid-number"); back.run(); return; }
            if (!Inv.take(p, tmpl, qty)) { Msg.send(p, "not-enough-items"); back.run(); return; }
            svc.addStock(p, shopId, tmpl, qty, price).whenComplete((r, t) -> Tasks.sync(() -> { if (t != null) Msg.error(p, t); else Msg.send(p, "saved"); back.run(); }));
        });
    }

    private void items(Player p, Shop s, Runnable back) {
        Runnable self = () -> items(p, s, back);
        new PagedMenu<Item>(pl, p, "&6Productos: " + s.name(), () -> svc.items(s.id(), true), i -> Items.withLore(Inv.decodeOr(i.itemData(), i.material()),
                "&7Precio: " + Money.fmt(s.currency(), i.price()), "&7Stock: &f" + i.stock(), "&7Vendidos: &f" + i.sold(), "&eClic para editar"), (i, ci) -> editItem(p, s, i.id(), self)).back(back).load();
    }

    private void editItem(Player p, Shop s, long itemId, Runnable back) {
        svc.itemById(itemId).thenAccept(i -> Tasks.sync(() -> {
            if (i == null) { back.run(); return; }
            Runnable reload = () -> editItem(p, s, itemId, back);
            EditMenu e = new EditMenu(pl, p, "&6Producto: " + i.itemName(), reload);
            e.field(Material.EMERALD, "Precio por unidad", i.price().toPlainString(), "Nuevo precio", t -> {
                BigDecimal v = Money.parse(t);
                return v == null ? CompletableFuture.failedFuture(new EcoException("invalid-amount")) : svc.setPrice(p, itemId, v);
            });
            e.action(Material.HOPPER, "&aAñadir stock (de tu inventario)", List.of("&7Stock actual: " + i.stock()), c ->
                    Prompt.ask(p, "Cantidad a añadir", t -> {
                        Integer q = pint(t);
                        ItemStack tmpl = Inv.decodeOr(i.itemData(), i.material());
                        if (q == null) { Msg.send(p, "invalid-number"); reload.run(); return; }
                        if (!Inv.take(p, tmpl, q)) { Msg.send(p, "not-enough-items"); reload.run(); return; }
                        svc.addStock(p, s.id(), tmpl, q, i.price()).whenComplete((r, x) -> Tasks.sync(() -> { if (x != null) Msg.error(p, x); reload.run(); }));
                    }, reload));
            e.action(Material.DROPPER, "&6Retirar stock", List.of("&7Va a tu buzón"), c -> Prompt.ask(p, "Cantidad a retirar", t -> {
                Integer q = pint(t);
                if (q == null) { Msg.send(p, "invalid-number"); reload.run(); return; }
                svc.withdrawStock(p, itemId, q, false).whenComplete((r, x) -> Tasks.sync(() -> { if (x != null) Msg.error(p, x); reload.run(); }));
            }, reload));
            e.action(Material.TNT, "&cQuitar producto", List.of("&7El stock vuelve a tu buzón"), c -> ConfirmMenu.ask(pl, p, "&cQuitar producto", List.of("&7" + i.itemName()), () ->
                    svc.withdrawStock(p, itemId, 0, true).whenComplete((r, x) -> Tasks.sync(back)), reload));
            e.back(back).open();
        }));
    }

    private void managers(Player p, long shopId, Runnable back) {
        Runnable self = () -> managers(p, shopId, back);
        new PagedMenu<String[]>(pl, p, "&dGestores", () -> svc.managers(shopId), m -> Items.of(Material.PLAYER_HEAD, "&e" + m[1], "&cClic para quitar"),
                (m, ci) -> EditMenu.update("DELETE FROM pshop_managers WHERE shop_id=? AND uuid=?", shopId, m[0]).whenComplete((r, t) -> Tasks.sync(self)))
                .extras(() -> List.of(Btn.of(47, Items.of(Material.EMERALD, "&aAñadir gestor"), () -> Prompt.ask(p, "Nombre del jugador", n ->
                        svc.addManager(p, shopId, n).whenComplete((r, t) -> Tasks.sync(() -> { if (t != null) Msg.error(p, t); self.run(); })), self))))
                .back(back).load();
    }
}
