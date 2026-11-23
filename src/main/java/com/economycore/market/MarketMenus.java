package com.economycore.market;

import com.economycore.core.EconomyCore;
import com.economycore.core.Fmt;
import com.economycore.core.Inv;
import com.economycore.core.Money;
import com.economycore.core.Msg;
import com.economycore.core.Tasks;
import com.economycore.gui.Btn;
import com.economycore.gui.ButtonMenu;
import com.economycore.gui.ConfirmMenu;
import com.economycore.gui.Items;
import com.economycore.gui.PagedMenu;
import com.economycore.gui.Prompt;
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

/** Player-facing market GUIs (the default shop is just the market flagged is_shop). */
public final class MarketMenus {
    private final EconomyCore pl;
    private final MarketService svc;
    private final MarketAdminMenus admin;

    public MarketMenus(EconomyCore pl, MarketService svc) {
        this.pl = pl;
        this.svc = svc;
        this.admin = new MarketAdminMenus(pl, svc, this);
    }

    public MarketAdminMenus admin() { return admin; }

    public boolean canAccess(Player p, Market m) {
        if (p.hasPermission("economycore.market.admin")) return true;
        if (!m.enabled()) return false;
        return m.permission() == null || m.permission().isEmpty() || p.hasPermission(m.permission()) || p.hasPermission("economycore.market.*");
    }

    public static Material icon(String name, Material def) {
        Material m = name == null ? null : Material.matchMaterial(name);
        return m == null ? def : m;
    }

    // ---------- list of markets ----------
    public void list(Player p, Runnable back) {
        boolean adm = p.hasPermission("economycore.market.admin");
        PagedMenu<Market> m = new PagedMenu<>(pl, p, "&6Mercados", svc::markets,
                mk -> {
                    List<String> lore = new ArrayList<>();
                    lore.add("&7Moneda: &f" + mk.currency());
                    lore.add(svc.isOpen(mk) && mk.enabled() ? "&aAbierto" : "&cCerrado");
                    if (mk.openMin() >= 0) lore.add("&7Horario: &f" + MarketService.minToStr(mk.openMin()) + " - " + MarketService.minToStr(mk.closeMin()));
                    if (mk.rotEnabled()) lore.add("&7Rotación cada &f" + mk.rotIntervalMin() + " min");
                    if (!canAccess(p, mk)) lore.add("&cSin permiso");
                    lore.add("&eClic para abrir");
                    return Items.of(icon(mk.icon(), Material.CHEST), "&e" + mk.name(), lore);
                },
                (mk, ci) -> {
                    if (!canAccess(p, mk)) { Msg.send(p, "no-permission"); return; }
                    open(p, mk.id(), () -> list(p, back));
                }).searchable(Market::name);
        if (adm) m.extras(() -> List.of(Btn.of(47, Items.of(Material.EMERALD, "&aCrear mercado", "&7Mercados ilimitados"), () -> admin.createMarket(p, () -> list(p, back)))));
        m.back(back).load();
    }

    // ---------- one market ----------
    public void open(Player p, String marketId, Runnable back) {
        svc.market(marketId).thenAccept(m -> Tasks.sync(() -> {
            if (m == null) { Msg.send(p, "market-not-found"); return; }
            if (!canAccess(p, m)) { Msg.send(p, "no-permission"); return; }
            categories(p, m, back);
        }));
    }

    public void categories(Player p, Market m, Runnable back) {
        boolean adm = p.hasPermission("economycore.market.admin");
        Runnable self = () -> categories(p, m, back);
        PagedMenu<Category> pm = new PagedMenu<>(pl, p, "&6" + m.name(), () -> svc.categories(m.id()),
                c -> Items.of(icon(c.icon(), Material.CHEST), "&e" + c.name(), "&7Clic para ver productos"),
                (c, ci) -> products(p, m, c, self));
        pm.extras(() -> {
            List<Btn> l = new ArrayList<>();
            l.add(Btn.of(47, Items.of(Material.BOOK, "&bTodos los productos", "&7" + (svc.isOpen(m) ? "Mercado abierto" : "Mercado cerrado")), () -> products(p, m, null, self)));
            if (m.isShop()) l.add(Btn.of(51, Items.of(Material.HOPPER, "&aVender todo", "&7Vende lo vendible de tu inventario"), () -> sellAll(p, self)));
            if (adm) l.add(Btn.of(50, Items.of(Material.COMMAND_BLOCK, "&cAdministrar", "&7Configurar este mercado"), () -> admin.editMarket(p, m.id(), self)));
            return l;
        });
        pm.back(back).load();
    }

    private ItemStack productIcon(Market m, Product pr, boolean showHidden) {
        ItemStack base = Inv.decodeOr(pr.itemData(), pr.material());
        List<String> lore = new ArrayList<>();
        BigDecimal eff = pr.effectiveBuy(m.discountPct());
        if (pr.buy() != null) {
            boolean disc = eff.compareTo(pr.buy()) < 0;
            lore.add("&7Comprar: " + (disc ? "&m" + Money.plain(m.currency(), pr.buy()) + "&r " : "") + Money.fmt(m.currency(), eff) + (disc ? " &c-" + pr.discount().add(m.discountPct()).stripTrailingZeros().toPlainString() + "%" : ""));
        } else lore.add("&7Comprar: &8no disponible");
        lore.add(pr.sell() != null ? "&7Vender: " + Money.fmt(m.currency(), pr.sell()) : "&7Vender: &8no disponible");
        lore.add("&7Stock: &f" + (pr.stock() < 0 ? "ilimitado" : pr.stock()));
        if (pr.limit() > 0) lore.add("&7Límite: &f" + pr.limit() + " cada " + pr.limitHours() + "h");
        if (showHidden && (!pr.active() || !pr.inRotation())) lore.add("&c" + (!pr.active() ? "Desactivado" : "Fuera de rotación"));
        lore.add("&eClic para comprar/vender");
        return Items.withLore(base, lore);
    }

    public void products(Player p, Market m, Category cat, Runnable back) {
        boolean bypass = p.hasPermission("economycore.market.admin");
        new PagedMenu<Product>(pl, p, "&6" + m.name() + (cat != null ? " &8» &e" + cat.name() : ""), () -> svc.products(m.id(), cat == null ? null : cat.id(), bypass),
                pr -> productIcon(m, pr, bypass),
                (pr, ci) -> {
                    if (pr.permission() != null && !pr.permission().isEmpty() && !p.hasPermission(pr.permission()) && !bypass) { Msg.send(p, "no-permission"); return; }
                    productMenu(p, m, pr.id(), () -> products(p, m, cat, back));
                })
                .searchable(pr -> pr.material() + " " + Inv.pretty(Material.matchMaterial(pr.material()) == null ? Material.STONE : Material.matchMaterial(pr.material())))
                .back(back).load();
    }

    // ---------- product detail ----------
    public void productMenu(Player p, Market m, long productId, Runnable back) {
        svc.product(productId).thenAccept(pr -> Tasks.sync(() -> {
            if (pr == null) { Msg.send(p, "product-gone"); back.run(); return; }
            boolean adm = p.hasPermission("economycore.market.admin");
            ItemStack tmpl = Inv.decodeOr(pr.itemData(), pr.material());
            Runnable self = () -> productMenu(p, m, productId, back);
            new ButtonMenu(pl, p, "&6" + Inv.name(tmpl), 4, true, () -> {
                List<Btn> l = new ArrayList<>();
                l.add(Btn.of(4, productIcon(m, pr, adm), () -> {}));
                int[] q = {1, 8, 16, 32, 64};
                for (int i = 0; i < q.length; i++) {
                    int n = q[i];
                    if (pr.buy() != null) {
                        BigDecimal t = pr.effectiveBuy(m.discountPct()).multiply(BigDecimal.valueOf(n));
                        l.add(Btn.of(10 + i, Items.of(Material.LIME_STAINED_GLASS_PANE, "&aComprar x" + n, "&7Total: " + Money.fmt(m.currency(), t)), () -> buy(p, m, pr, n, self)));
                    }
                    if (pr.sell() != null) {
                        BigDecimal t = pr.sell().multiply(BigDecimal.valueOf(n));
                        l.add(Btn.of(19 + i, Items.of(Material.ORANGE_STAINED_GLASS_PANE, "&6Vender x" + n, "&7Recibes: " + Money.fmt(m.currency(), t) + " &8(antes de comisión)"), () -> sell(p, m, pr, n, self)));
                    }
                }
                if (pr.buy() != null) l.add(Btn.of(15, Items.of(Material.LIME_CONCRETE, "&aComprar cantidad…", "&7Escribe la cantidad"), () ->
                        Prompt.ask(p, "Cantidad a comprar", s -> { Integer n = parse(s); if (n == null) { Msg.send(p, "invalid-number"); self.run(); } else buy(p, m, pr, n, self); }, self)));
                if (pr.sell() != null) {
                    l.add(Btn.of(24, Items.of(Material.ORANGE_CONCRETE, "&6Vender cantidad…", "&7Escribe la cantidad"), () ->
                            Prompt.ask(p, "Cantidad a vender", s -> { Integer n = parse(s); if (n == null) { Msg.send(p, "invalid-number"); self.run(); } else sell(p, m, pr, n, self); }, self)));
                    l.add(Btn.of(25, Items.of(Material.HOPPER, "&6Vender todo", "&7Tienes: &f" + Inv.count(p, tmpl)), () -> {
                        int c = Inv.count(p, tmpl);
                        if (c <= 0) { Msg.send(p, "not-enough-items"); return; }
                        sell(p, m, pr, c, self);
                    }));
                }
                if (adm) l.add(Btn.of(31, Items.of(Material.COMMAND_BLOCK, "&cEditar producto"), () -> admin.editProduct(p, m, pr.id(), self)));
                return l;
            }).back(back).open();
        }));
    }

    private static Integer parse(String s) {
        try { int n = Integer.parseInt(s.trim()); return n > 0 ? n : null; } catch (NumberFormatException e) { return null; }
    }

    private void buy(Player p, Market m, Product pr, int qty, Runnable again) {
        svc.buy(p, pr.id(), qty).whenComplete((r, t) -> Tasks.sync(() -> {
            if (t != null) Msg.error(p, t);
            else Msg.send(p, "market-bought", qty, Inv.name(Inv.decodeOr(pr.itemData(), pr.material())), Money.fmt(r.currency(), r.total().add(r.tax())));
            again.run();
        }));
    }

    private void sell(Player p, Market m, Product pr, int qty, Runnable again) {
        svc.sell(p, pr, qty).whenComplete((r, t) -> Tasks.sync(() -> {
            if (t != null) Msg.error(p, t);
            else Msg.send(p, "market-sold", qty, Inv.name(Inv.decodeOr(pr.itemData(), pr.material())), Money.fmt(r.currency(), r.net()));
            again.run();
        }));
    }

    // ---------- sell all ----------
    public void sellAll(Player p, Runnable back) {
        svc.shop().thenCompose(shop -> shop == null ? CompletableFuture.<List<Product>>completedFuture(new ArrayList<>()) : svc.products(shop.id(), null, false)).thenAccept(prods -> Tasks.sync(() -> {
            BigDecimal total = BigDecimal.ZERO;
            int items = 0;
            List<String> lore = new ArrayList<>();
            for (Product pr : prods) {
                if (pr.sell() == null || pr.itemData() != null) continue;
                Material mat = Material.matchMaterial(pr.material());
                if (mat == null) continue;
                int c = Inv.count(p, new ItemStack(mat));
                if (c <= 0) continue;
                total = total.add(pr.sell().multiply(BigDecimal.valueOf(c)));
                items += c;
                if (lore.size() < 12) lore.add("&7" + c + "x &f" + Inv.pretty(mat) + " &7= " + Money.fmt(pl.currencies().defId(), pr.sell().multiply(BigDecimal.valueOf(c))));
            }
            if (items == 0) { Msg.send(p, "nothing-to-sell"); return; }
            lore.add("&eTotal bruto: " + Money.fmt(pl.currencies().defId(), total));
            lore.add("&7(Se descuentan comisiones e impuestos)");
            ConfirmMenu.ask(pl, p, "&aVender todo (" + items + " objetos)", lore, () ->
                    svc.sellAll(p, prods).whenComplete((r, t) -> Tasks.sync(() -> {
                        if (t != null) Msg.error(p, t);
                        else Msg.send(p, "sellall-done", r.sold(), Money.fmt(r.currency(), r.net()), r.returned());
                    })), back);
        }));
    }
}
