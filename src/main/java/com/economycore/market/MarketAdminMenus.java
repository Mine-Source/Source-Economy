package com.economycore.market;

import com.economycore.core.EcoException;
import com.economycore.core.EconomyCore;
import com.economycore.core.Inv;
import com.economycore.core.Money;
import com.economycore.core.Msg;
import com.economycore.core.Tasks;
import com.economycore.gui.Btn;
import com.economycore.gui.ConfirmMenu;
import com.economycore.gui.EditMenu;
import com.economycore.gui.Form;
import com.economycore.gui.Items;
import com.economycore.gui.PagedMenu;
import com.economycore.gui.Setters;
import com.economycore.market.MarketModels.Category;
import com.economycore.market.MarketModels.Market;
import com.economycore.market.MarketModels.Product;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Administration GUIs for markets, categories and products. */
public final class MarketAdminMenus {
    private final EconomyCore pl;
    private final MarketService svc;
    private final MarketMenus menus;

    MarketAdminMenus(EconomyCore pl, MarketService svc, MarketMenus menus) {
        this.pl = pl;
        this.svc = svc;
        this.menus = menus;
    }

    private static String id(String s) { return s.toLowerCase().replaceAll("[^a-z0-9_]", ""); }

    public void createMarket(Player p, Runnable back) {
        Form.of(p).ask("id", "ID del mercado (sin espacios)").ask("name", "Nombre visible").ask("cur", "Moneda (id)").onCancel(back).then(a -> {
            String cid = pl.currencies().exists(a.get("cur")) ? pl.currencies().get(a.get("cur")).id() : null;
            String id = id(a.get("id"));
            if (cid == null) { Msg.send(p, "unknown-currency", a.get("cur")); back.run(); return; }
            if (id.isEmpty()) { Msg.send(p, "invalid-number"); back.run(); return; }
            svc.createMarket(p.getName(), id, a.get("name"), cid).whenComplete((r, t) -> Tasks.sync(() -> {
                if (t != null) { Msg.error(p, t); back.run(); } else { Msg.send(p, "saved"); editMarket(p, id, back); }
            }));
        });
    }

    public void editMarket(Player p, String id, Runnable back) {
        svc.market(id).thenAccept(m -> Tasks.sync(() -> {
            if (m == null) { back.run(); return; }
            Runnable reload = () -> editMarket(p, id, back);
            EditMenu e = new EditMenu(pl, p, "&cMercado: " + m.name(), reload);
            e.field(Material.NAME_TAG, "Nombre", m.name(), "Nuevo nombre", Setters.text("markets", "id", id, "name", false));
            e.field(Material.ITEM_FRAME, "Icono", m.icon(), "Nombre del material (ej: DIAMOND)", s -> {
                Material mt = Material.matchMaterial(s.trim());
                return mt == null ? CompletableFuture.failedFuture(new EcoException("invalid-material")) : EditMenu.update("UPDATE markets SET icon=? WHERE id=?", mt.name(), id);
            });
            e.field(Material.SUNFLOWER, "Moneda", m.currency(), "ID de la moneda", s -> {
                var c = pl.currencies().get(s.trim());
                return c == null ? CompletableFuture.failedFuture(new EcoException("unknown-currency", s)) : EditMenu.update("UPDATE markets SET currency=? WHERE id=?", c.id(), id);
            });
            e.field(Material.PAPER, "Impuesto de compra %", m.taxPct().toPlainString(), "Impuesto % añadido a las compras", Setters.pct("markets", "id", id, "tax_pct"));
            e.field(Material.GOLD_NUGGET, "Comisión de venta %", m.commissionPct().toPlainString(), "Comisión % descontada al vender", Setters.pct("markets", "id", id, "commission_pct"));
            e.field(Material.REDSTONE, "Descuento global %", m.discountPct().toPlainString(), "Descuento % sobre todos los productos", Setters.pct("markets", "id", id, "discount_pct"));
            e.field(Material.TRIPWIRE_HOOK, "Permiso", m.permission() == null ? "-" : m.permission(), "Permiso requerido (o - para ninguno)", Setters.text("markets", "id", id, "permission", true));
            e.field(Material.CLOCK, "Hora de apertura", MarketService.minToStr(m.openMin()), "Hora HH:MM (o - para sin horario)", s -> {
                try { return EditMenu.update("UPDATE markets SET open_min=? WHERE id=?", MarketService.strToMin(s), id); }
                catch (Exception ex) { return CompletableFuture.failedFuture(new EcoException("invalid-time")); }
            });
            e.field(Material.CLOCK, "Hora de cierre", MarketService.minToStr(m.closeMin()), "Hora HH:MM (o - para sin horario)", s -> {
                try { return EditMenu.update("UPDATE markets SET close_min=? WHERE id=?", MarketService.strToMin(s), id); }
                catch (Exception ex) { return CompletableFuture.failedFuture(new EcoException("invalid-time")); }
            });
            e.field(Material.CLOCK, "Días abiertos", m.days().isEmpty() ? "todos" : m.days(), "Días: MON,TUE,WED,THU,FRI,SAT,SUN (o - para todos)", s ->
                    EditMenu.update("UPDATE markets SET days=? WHERE id=?", s.trim().equals("-") ? "" : s.trim().toUpperCase(), id));
            e.field(Material.COMPARATOR, "Rotación: tamaño", String.valueOf(m.rotSize()), "Cantidad de productos visibles por rotación", Setters.integer("markets", "id", id, "rot_size", 1, 10000));
            e.field(Material.COMPARATOR, "Rotación: intervalo (min)", String.valueOf(m.rotIntervalMin()), "Minutos entre rotaciones", Setters.integer("markets", "id", id, "rot_interval_min", 1, 1000000));
            e.toggle(Material.LIME_DYE, "Mercado activado", m.enabled(), () -> Setters.flip("markets", "id", id, "enabled"));
            e.toggle(Material.LIME_DYE, "Rotación activada", m.rotEnabled(), () -> EditMenu.update("UPDATE markets SET rot_enabled=1-rot_enabled, last_rot=0 WHERE id=?", id));
            e.action(Material.CHEST, "&eCategorías", List.of("&7Crear y editar categorías"), c -> categories(p, id, reload));
            e.action(Material.BOOK, "&eProductos", List.of("&7Todos los productos del mercado"), c -> products(p, m, null, reload));
            e.action(Material.DIAMOND, "&aRotar ahora", List.of("&7Fuerza una nueva rotación"), c ->
                    EditMenu.update("UPDATE markets SET last_rot=0 WHERE id=?", id).whenComplete((r, t) -> { svc.rotate(); Tasks.later(10L, () -> Msg.send(p, "saved")); }));
            if (!m.isShop()) e.action(Material.TNT, "&cEliminar mercado", List.of("&7Borra productos y categorías"), c ->
                    ConfirmMenu.ask(pl, p, "&cEliminar " + m.name(), List.of("&7Se borrarán todos sus productos"), () ->
                            svc.deleteMarket(p.getName(), id).whenComplete((r, t) -> Tasks.sync(() -> { if (t != null) Msg.error(p, t); else Msg.send(p, "saved"); back.run(); })), reload));
            e.back(back).open();
        }));
    }

    // ---------- categories ----------
    public void categories(Player p, String market, Runnable back) {
        new PagedMenu<Category>(pl, p, "&cCategorías", () -> svc.categories(market),
                c -> Items.of(MarketMenus.icon(c.icon(), Material.CHEST), "&e" + c.name(), "&7ID: &f" + c.id(), "&aClic para editar"),
                (c, ci) -> editCategory(p, c, () -> categories(p, market, back)))
                .extras(() -> List.of(Btn.of(47, Items.of(Material.EMERALD, "&aNueva categoría"), () ->
                        Form.of(p).ask("id", "ID de la categoría").ask("name", "Nombre visible").ask("icon", "Material del icono (ej: DIAMOND)").onCancel(() -> categories(p, market, back)).then(a -> {
                            Material mt = Material.matchMaterial(a.get("icon").trim());
                            if (mt == null) { Msg.send(p, "invalid-material"); categories(p, market, back); return; }
                            svc.createCategory(market, id(a.get("id")), a.get("name"), mt.name()).whenComplete((r, t) -> Tasks.sync(() -> { if (t != null) Msg.error(p, t); categories(p, market, back); }));
                        }))))
                .back(back).load();
    }

    private void editCategory(Player p, Category c, Runnable back) {
        Runnable reload = () -> svc.categories(c.market()).thenAccept(l -> Tasks.sync(() -> {
            for (Category x : l) if (x.id().equals(c.id())) { editCategory(p, x, back); return; }
            back.run();
        }));
        EditMenu e = new EditMenu(pl, p, "&cCategoría: " + c.name(), reload);
        String where = "market='" + c.market().replace("'", "") + "' AND id";
        e.field(Material.NAME_TAG, "Nombre", c.name(), "Nuevo nombre", s -> EditMenu.update("UPDATE market_categories SET name=? WHERE market=? AND id=?", s.trim(), c.market(), c.id()));
        e.field(Material.ITEM_FRAME, "Icono", c.icon(), "Material del icono", s -> {
            Material mt = Material.matchMaterial(s.trim());
            return mt == null ? CompletableFuture.failedFuture(new EcoException("invalid-material")) : EditMenu.update("UPDATE market_categories SET icon=? WHERE market=? AND id=?", mt.name(), c.market(), c.id());
        });
        e.field(Material.COMPARATOR, "Orden", String.valueOf(c.sort()), "Posición (número)", s -> {
            try { return EditMenu.update("UPDATE market_categories SET sort=? WHERE market=? AND id=?", Integer.parseInt(s.trim()), c.market(), c.id()); }
            catch (NumberFormatException ex) { return CompletableFuture.failedFuture(new EcoException("invalid-number")); }
        });
        e.action(Material.BOOK, "&eProductos", List.of("&7Productos de esta categoría"), ci -> svc.market(c.market()).thenAccept(m -> Tasks.sync(() -> products(p, m, c, reload))));
        e.action(Material.TNT, "&cEliminar categoría", List.of("&7Los productos quedan sin categoría"), ci ->
                ConfirmMenu.ask(pl, p, "&cEliminar " + c.name(), List.of("&7Los productos no se borran"), () ->
                        svc.deleteCategory(c.market(), c.id()).whenComplete((r, t) -> Tasks.sync(back)), reload));
        e.back(back).open();
    }

    // ---------- products ----------
    public void products(Player p, Market m, Category cat, Runnable back) {
        Runnable self = () -> products(p, m, cat, back);
        new PagedMenu<Product>(pl, p, "&cProductos: " + m.name() + (cat != null ? " » " + cat.name() : ""), () -> svc.products(m.id(), cat == null ? null : cat.id(), true),
                pr -> {
                    ItemStack base = Inv.decodeOr(pr.itemData(), pr.material());
                    return Items.withLore(base, "&7Compra: &f" + (pr.buy() == null ? "-" : pr.buy().toPlainString()), "&7Venta: &f" + (pr.sell() == null ? "-" : pr.sell().toPlainString()),
                            "&7Stock: &f" + (pr.stock() < 0 ? "∞" : pr.stock()), "&7Categoría: &f" + pr.category(), pr.active() ? "&aActivo" : "&cInactivo", "&eClic para editar");
                },
                (pr, ci) -> editProduct(p, m, pr.id(), self))
                .searchable(pr -> pr.material() + " " + pr.category())
                .extras(() -> List.of(Btn.of(47, Items.of(Material.EMERALD, "&aAñadir objeto en mano", "&7Sostén el objeto a vender"), () -> addFromHand(p, m, cat, self))))
                .back(back).load();
    }

    private void addFromHand(Player p, Market m, Category cat, Runnable back) {
        ItemStack hand = Inv.hand(p);
        if (hand == null) { Msg.send(p, "hold-item"); return; }
        ItemStack item = hand.clone();
        Form f = Form.of(p).ask("buy", "Precio de compra por unidad (o - si no se vende a jugadores)").ask("sell", "Precio de venta por unidad (o - si no se compra a jugadores)");
        if (cat == null) f.ask("cat", "ID de la categoría (o - para ninguna)");
        f.onCancel(back).then(a -> {
            BigDecimal buy = a.get("buy").equals("-") ? null : Money.parse(a.get("buy"));
            BigDecimal sell = a.get("sell").equals("-") ? null : Money.parse(a.get("sell"));
            if ((buy == null && !a.get("buy").equals("-")) || (sell == null && !a.get("sell").equals("-"))) { Msg.send(p, "invalid-amount"); back.run(); return; }
            String c = cat != null ? cat.id() : (a.get("cat").equals("-") ? "" : id(a.get("cat")));
            svc.addProduct(p.getName(), m.id(), c, item, buy, sell).whenComplete((r, t) -> Tasks.sync(() -> {
                if (t != null) Msg.error(p, t); else Msg.send(p, "saved");
                back.run();
            }));
        });
    }

    public void editProduct(Player p, Market m, long id, Runnable back) {
        svc.product(id).thenAccept(pr -> Tasks.sync(() -> {
            if (pr == null) { back.run(); return; }
            Runnable reload = () -> editProduct(p, m, id, back);
            EditMenu e = new EditMenu(pl, p, "&cProducto: " + pr.material(), reload);
            String T = "market_products";
            e.field(Material.CHEST, "Categoría", pr.category().isEmpty() ? "-" : pr.category(), "ID de categoría (o -)", s -> EditMenu.update("UPDATE market_products SET category=? WHERE id=?", s.trim().equals("-") ? "" : id(s), id));
            e.field(Material.EMERALD, "Precio de compra", pr.buy() == null ? "-" : pr.buy().toPlainString(), "Precio (o - para no vender a jugadores)", Setters.money(T, "id", id, "buy_price", true));
            e.field(Material.GOLD_INGOT, "Precio de venta", pr.sell() == null ? "-" : pr.sell().toPlainString(), "Precio (o - para no comprar a jugadores)", Setters.money(T, "id", id, "sell_price", true));
            e.field(Material.BARREL, "Stock", pr.stock() < 0 ? "ilimitado" : String.valueOf(pr.stock()), "Stock actual (-1 = ilimitado)", Setters.integer(T, "id", id, "stock", -1, 100000000));
            e.field(Material.BARREL, "Stock máximo", String.valueOf(pr.maxStock()), "Máximo (-1 = sin tope). Limita lo que el mercado compra", Setters.integer(T, "id", id, "max_stock", -1, 100000000));
            e.field(Material.HOPPER, "Reposición por hora", String.valueOf(pr.restock()), "Unidades repuestas cada hora (0 = no)", Setters.integer(T, "id", id, "restock_per_hour", 0, 1000000));
            e.field(Material.REDSTONE, "Descuento %", pr.discount().toPlainString(), "Descuento % del producto", Setters.pct(T, "id", id, "discount_pct"));
            e.field(Material.CLOCK, "Límite por jugador", String.valueOf(pr.limit()), "Unidades por ventana (0 = sin límite)", Setters.integer(T, "id", id, "limit_per_player", 0, 1000000));
            e.field(Material.CLOCK, "Ventana del límite (h)", String.valueOf(pr.limitHours()), "Horas de la ventana del límite", Setters.integer(T, "id", id, "limit_hours", 1, 100000));
            e.field(Material.TRIPWIRE_HOOK, "Permiso", pr.permission() == null ? "-" : pr.permission(), "Permiso requerido (o -)", Setters.text(T, "id", id, "permission", true));
            e.toggle(Material.LIME_DYE, "Activo", pr.active(), () -> Setters.flip(T, "id", id, "active"));
            e.toggle(Material.LIME_DYE, "Visible en rotación", pr.inRotation(), () -> Setters.flip(T, "id", id, "in_rotation"));
            e.action(Material.TNT, "&cEliminar producto", List.of("&7Se borra del mercado"), c ->
                    ConfirmMenu.ask(pl, p, "&cEliminar producto", List.of("&7" + pr.material()), () ->
                            svc.deleteProduct(p.getName(), id).whenComplete((r, t) -> Tasks.sync(back)), reload));
            e.back(back).open();
        }));
    }
}
