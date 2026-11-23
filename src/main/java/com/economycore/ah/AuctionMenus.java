package com.economycore.ah;

import com.economycore.ah.AuctionService.Auction;
import com.economycore.ah.AuctionService.Filter;
import com.economycore.core.EconomyCore;
import com.economycore.core.Fmt;
import com.economycore.core.Inv;
import com.economycore.core.Money;
import com.economycore.core.Msg;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class AuctionMenus {
    private final EconomyCore pl;
    private final AuctionService svc;

    public AuctionMenus(EconomyCore pl, AuctionService svc) {
        this.pl = pl;
        this.svc = svc;
    }

    public static Filter defaultFilter() { return new Filter(null, null, "ending", null, null); }

    private static String catName(String c) {
        if (c == null) return "Todas";
        return switch (c) {
            case "blocks" -> "Bloques";
            case "weapons" -> "Armas";
            case "armor" -> "Armadura";
            case "tools" -> "Herramientas";
            case "food" -> "Comida";
            default -> "Varios";
        };
    }

    private static String sortName(String s) {
        return switch (s) {
            case "newest" -> "Más nuevas";
            case "price_asc" -> "Precio ascendente";
            case "price_desc" -> "Precio descendente";
            default -> "Termina pronto";
        };
    }

    private static <T> T next(T[] arr, T cur) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == null ? cur == null : arr[i].equals(cur)) return arr[(i + 1) % arr.length];
        return arr[0];
    }

    private ItemStack icon(Auction a, Set<Long> favs) {
        ItemStack base = Inv.decodeOr(a.itemData(), a.material());
        base.setAmount(Math.max(1, Math.min(a.amount(), base.getMaxStackSize())));
        List<String> lore = new ArrayList<>();
        lore.add("&8&m                    ");
        lore.add("&7Vendedor: &f" + a.sellerName() + (favs.contains(a.id()) ? "  &e★" : ""));
        lore.add("&7Cantidad: &f" + a.amount());
        if (a.isBin()) lore.add("&7Precio: " + Money.fmt(a.currency(), a.startPrice()) + " &a(Compra directa)");
        else {
            lore.add("&7Puja actual: " + (a.currentBid() == null ? "&8sin pujas &7(mín. " + Money.fmt(a.currency(), a.startPrice()) + "&7)" : Money.fmt(a.currency(), a.currentBid())));
            lore.add("&7Pujas: &f" + a.bidCount() + (a.bidderName() != null ? " &7por &f" + a.bidderName() : ""));
            if (a.buyNow() != null) lore.add("&7Comprar ya: " + Money.fmt(a.currency(), a.buyNow()));
        }
        lore.add("&7Termina en: &f" + Fmt.duration(a.ends() - System.currentTimeMillis()));
        lore.add("&eClic para ver");
        return Items.withLore(base, lore);
    }

    public void browse(Player p, Filter f, Runnable back) {
        UUID id = p.getUniqueId();
        Set<Long> favs = ConcurrentHashMap.newKeySet();
        boolean mine = f.sellerOnly() != null;
        Runnable self = () -> browse(p, f, back);
        PagedMenu<Auction> m = new PagedMenu<>(pl, p, mine ? "&6Mis subastas" : "&6Casa de subastas", () -> svc.favorites(id).thenCompose(fs -> {
            favs.clear();
            favs.addAll(fs);
            return svc.list(f);
        }), a -> icon(a, favs), (a, ci) -> detail(p, a.id(), self));
        m.pageSize(36).searchable(a -> a.itemName() + " " + a.sellerName() + " " + a.material());
        m.extras(() -> {
            List<Btn> l = new ArrayList<>();
            String[] cats = new String[AuctionService.CATEGORIES.length + 1];
            cats[0] = null;
            System.arraycopy(AuctionService.CATEGORIES, 0, cats, 1, AuctionService.CATEGORIES.length);
            String[] types = {null, "BIN", "AUCTION"};
            l.add(Btn.of(36, Items.of(Material.COMPASS, "&bCategoría: &f" + catName(f.category()), "&7Clic para cambiar"), () -> browse(p, f.withCategory(next(cats, f.category())), back)));
            l.add(Btn.of(37, Items.of(Material.NAME_TAG, "&bTipo: &f" + (f.type() == null ? "Todos" : f.type().equals("BIN") ? "Compra directa" : "Subastas"), "&7Clic para cambiar"), () -> browse(p, f.withType(next(types, f.type())), back)));
            l.add(Btn.of(38, Items.of(Material.HOPPER, "&bOrden: &f" + sortName(f.sort()), "&7Clic para cambiar"), () -> browse(p, f.withSort(next(AuctionService.SORTS, f.sort())), back)));
            l.add(Btn.of(39, Items.of(f.favoritesOf() != null ? Material.NETHER_STAR : Material.GRAY_DYE, "&eSolo favoritos: &f" + (f.favoritesOf() != null ? "sí" : "no"), "&7Clic para alternar"),
                    () -> browse(p, f.withFavorites(f.favoritesOf() != null ? null : id), back)));
            if (!mine) l.add(Btn.of(41, Items.of(Material.CHEST, "&aMis subastas", "&7Tus listados activos"), () -> browse(p, new Filter(null, null, "ending", null, id), self)));
            l.add(Btn.of(42, Items.of(Material.EMERALD, "&a&lVender objeto", "&7Pon a la venta el objeto de tu mano"), () -> sellHand(p, self)));
            l.add(Btn.of(43, Items.of(Material.ENDER_CHEST, "&6Reclamaciones", "&7Objetos ganados, expirados o devueltos"), () -> ((EconomyModule) pl.module("economy")).menus().mailbox(p, self)));
            return l;
        });
        m.back(back).load();
    }

    public void detail(Player p, long id, Runnable back) {
        svc.get(id).thenAccept(a -> Tasks.sync(() -> {
            if (a == null || !a.state().equals("ACTIVE") || a.ends() <= System.currentTimeMillis()) { Msg.send(p, "ah-gone"); back.run(); return; }
            svc.favorites(p.getUniqueId()).thenAccept(favs -> Tasks.sync(() -> {
                boolean own = a.seller().equals(p.getUniqueId().toString());
                boolean admin = p.hasPermission("economycore.ah.admin");
                Runnable self = () -> detail(p, id, back);
                new ButtonMenu(pl, p, "&6Subasta #" + id, 4, true, () -> {
                    List<Btn> l = new ArrayList<>();
                    l.add(Btn.of(4, icon(a, favs), () -> {}));
                    if (!own) {
                        if (a.isBin()) {
                            l.add(Btn.of(13, Items.of(Material.LIME_CONCRETE, "&a&lCOMPRAR AHORA", "&7Precio: " + Money.fmt(a.currency(), a.startPrice())), () ->
                                    ConfirmMenu.ask(pl, p, "&aConfirmar compra", List.of("&7" + a.itemName() + " x" + a.amount(), "&7Precio: " + Money.fmt(a.currency(), a.startPrice())), () -> buyNow(p, id, self), self)));
                        } else {
                            BigDecimal min = svc.minBid(a);
                            l.add(Btn.of(11, Items.of(Material.LIME_STAINED_GLASS_PANE, "&aPujar mínimo", "&7Puja: " + Money.fmt(a.currency(), min)), () -> bid(p, id, min, self)));
                            BigDecimal plus = Money.scale(a.currency(), min.multiply(new BigDecimal("1.10")));
                            l.add(Btn.of(12, Items.of(Material.YELLOW_STAINED_GLASS_PANE, "&ePujar +10%", "&7Puja: " + Money.fmt(a.currency(), plus)), () -> bid(p, id, plus, self)));
                            l.add(Btn.of(13, Items.of(Material.WRITABLE_BOOK, "&bPuja personalizada", "&7Mínimo: " + Money.fmt(a.currency(), min)), () ->
                                    Prompt.ask(p, "Escribe tu puja", s -> {
                                        BigDecimal v = Money.parse(s);
                                        if (v == null) { Msg.send(p, "invalid-amount"); self.run(); } else bid(p, id, v, self);
                                    }, self)));
                            if (a.buyNow() != null) l.add(Btn.of(15, Items.of(Material.EMERALD_BLOCK, "&a&lCOMPRAR YA", "&7Precio: " + Money.fmt(a.currency(), a.buyNow())), () ->
                                    ConfirmMenu.ask(pl, p, "&aConfirmar compra", List.of("&7" + a.itemName(), "&7Precio: " + Money.fmt(a.currency(), a.buyNow())), () -> buyNow(p, id, self), self)));
                        }
                    }
                    l.add(Btn.of(22, Items.of(favs.contains(id) ? Material.NETHER_STAR : Material.GRAY_DYE, favs.contains(id) ? "&eQuitar de favoritos" : "&eAñadir a favoritos"), () ->
                            svc.toggleFavorite(p.getUniqueId(), id).whenComplete((r, t) -> Tasks.sync(self))));
                    if (own || admin) l.add(Btn.of(26, Items.of(Material.TNT, "&cCancelar subasta", own ? "&7Recuperas el objeto (si no hay pujas)" : "&7Cancelación administrativa"), () ->
                            ConfirmMenu.ask(pl, p, "&cCancelar subasta", List.of("&7" + a.itemName()), () ->
                                    svc.cancel(p, id, admin).whenComplete((r, t) -> Tasks.sync(() -> { if (t != null) Msg.error(p, t); else Msg.send(p, "ah-cancelled"); back.run(); })), self)));
                    return l;
                }).back(back).open();
            }));
        }));
    }

    private void bid(Player p, long id, BigDecimal amount, Runnable again) {
        svc.bid(p, id, amount).whenComplete((r, t) -> Tasks.sync(() -> {
            if (t != null) Msg.error(p, t);
            else if (r.bought()) Msg.send(p, "ah-bought");
            else Msg.send(p, "ah-bid-ok");
            again.run();
        }));
    }

    private void buyNow(Player p, long id, Runnable again) {
        svc.buyNow(p, id).whenComplete((r, t) -> Tasks.sync(() -> {
            if (t != null) Msg.error(p, t); else Msg.send(p, "ah-bought");
            again.run();
        }));
    }

    // ---------- selling ----------
    public void sellHand(Player p, Runnable back) {
        if (Inv.hand(p) == null) { Msg.send(p, "hold-item"); return; }
        int maxH = pl.getConfig().getInt("auction-house.max-duration-hours", 168);
        Form.of(p).ask("type", "Tipo: &ebin &7(compra directa) o &esubasta").ask("price", "Precio (precio inicial si es subasta)")
                .ask("hours", "Duración en horas (máx " + maxH + ")").onCancel(back).then(a -> {
                    boolean auction = a.get("type").toLowerCase().startsWith("s") || a.get("type").toLowerCase().startsWith("a");
                    BigDecimal price = Money.parse(a.get("price"));
                    int hours;
                    try { hours = Integer.parseInt(a.get("hours").trim()); } catch (NumberFormatException e) { Msg.send(p, "invalid-number"); return; }
                    if (price == null || hours < 1 || hours > maxH) { Msg.send(p, "invalid-number"); return; }
                    if (auction) Prompt.ask(p, "Precio de compra inmediata (o - para ninguno)", s -> {
                        BigDecimal bn = s.trim().equals("-") ? null : Money.parse(s);
                        if (bn == null && !s.trim().equals("-")) { Msg.send(p, "invalid-amount"); return; }
                        create(p, "AUCTION", price, bn, hours, back);
                    }, back);
                    else create(p, "BIN", price, null, hours, back);
                });
    }

    public void create(Player p, String type, BigDecimal price, BigDecimal buyNow, int hours, Runnable back) {
        ItemStack hand = Inv.hand(p);
        if (hand == null) { Msg.send(p, "hold-item"); return; }
        ItemStack item = hand.clone();
        int amount = item.getAmount();
        p.getInventory().setItemInMainHand(null);
        svc.create(p, item, amount, type, price, buyNow, hours, pl.currencies().defId()).whenComplete((r, t) -> Tasks.sync(() -> {
            if (t != null) Msg.error(p, t); else Msg.send(p, "ah-listed", Inv.name(item), Money.fmt(pl.currencies().defId(), price));
            if (back != null) back.run();
        }));
    }
}
