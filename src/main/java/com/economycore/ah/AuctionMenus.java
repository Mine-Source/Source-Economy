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

    private ItemStack icon(Auction a, Set<Long> favs, boolean mine) {
        ItemStack base = Inv.decodeOr(a.itemData(), a.material());
        base.setAmount(Math.max(1, Math.min(a.amount(), base.getMaxStackSize())));
        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add("&7Vendedor: &f" + a.sellerName() + (favs.contains(a.id()) ? "  &e★" : ""));
        if (a.amount() > 1) lore.add("&7Cantidad: &f" + a.amount());
        if (a.isBin()) {
            lore.add("&7Precio: " + Money.fmt(a.currency(), a.startPrice()));
            lore.add("&8Compra directa");
        } else {
            lore.add("&7Puja actual: " + (a.currentBid() == null ? "&8sin pujas" : Money.fmt(a.currency(), a.currentBid())));
            if (a.currentBid() == null) lore.add("&7Puja mínima: " + Money.fmt(a.currency(), a.startPrice()));
            lore.add("&7Pujas: &f" + a.bidCount() + (a.bidderName() != null ? " &8· &7líder: &f" + a.bidderName() : ""));
            if (a.buyNow() != null) lore.add("&7Comprar ya: " + Money.fmt(a.currency(), a.buyNow()));
        }
        lore.add("&7Termina en: &f" + Fmt.duration(a.ends() - System.currentTimeMillis()));
        lore.add("");
        lore.add(mine ? "&e▸ Clic para gestionar" : "&e▸ Clic para inspeccionar");
        return Items.withLore(base, lore);
    }

    private static final String[] CATS = {null, "blocks", "weapons", "armor", "tools", "food", "misc"};
    private static final String[] CAT_ICON = {"NETHER_STAR", "GRASS_BLOCK", "DIAMOND_SWORD", "DIAMOND_CHESTPLATE", "DIAMOND_PICKAXE", "COOKED_BEEF", "CHEST"};
    private static final int[] GRID = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
    private static final String[] SORT_LABELS = {"Termina pronto", "Más nuevas", "Precio ascendente", "Precio descendente"};
    private static final String[] TYPE_LABELS = {"Todos", "Compra directa", "Subastas"};
    private static final String[] TYPES = {null, "BIN", "AUCTION"};

    /** Entry point: main Auction House menu. */
    public void hub(Player p, Runnable back) {
        new ButtonMenu(pl, p, "&6Casa de subastas", 5, true, () -> {
            List<Btn> l = new ArrayList<>();
            l.add(Btn.of(4, Items.of(Material.GOLD_BLOCK, "&6Casa de subastas", "&7Compra y vende objetos con otros", "&7jugadores.", "",
                    "&7Comisión de venta: &a" + pl.getConfig().getString("auction-house.commission-percent", "5") + "%",
                    "&7Máx. publicaciones: &f" + pl.getConfig().getInt("auction-house.max-listings", 10)), () -> {}));
            l.add(Btn.of(11, Items.of(Material.COMPASS, "&aExplorar subastas", "&7Busca objetos a la venta con", "&7categorías, filtros y orden.", "", "&e▸ Clic para explorar"), () -> browse(p, defaultFilter(), () -> hub(p, back))));
            l.add(Btn.of(13, Items.of(Material.EMERALD, "&aVender un objeto", "&7Pon a la venta el objeto que", "&7llevas en la mano.", "", "&e▸ Clic para vender"), () -> sellHand(p, () -> hub(p, back))));
            l.add(Btn.of(15, Items.of(Material.CHEST, "&6Mis subastas", "&7Gestiona tus publicaciones activas.", "", "&e▸ Clic para ver"), () -> browse(p, new Filter(null, null, "ending", null, p.getUniqueId()), () -> hub(p, back))));
            l.add(Btn.of(29, Items.of(Material.NETHER_STAR, "&eFavoritos", "&7Subastas que marcaste con una estrella.", "", "&e▸ Clic para ver"), () -> browse(p, new Filter(null, null, "ending", p.getUniqueId(), null), () -> hub(p, back))));
            l.add(Btn.of(31, Items.of(Material.ENDER_CHEST, "&dReclamaciones", "&7Objetos ganados, vencidos o devueltos", "&7y dinero pendiente de cobrar.", "", "&e▸ Clic para reclamar"), () -> ((EconomyModule) pl.module("economy")).menus().mailbox(p, () -> hub(p, back))));
            l.add(Btn.of(33, Items.of(Material.BOOK, "&bCómo funciona", "&7▪ &fBIN&7: compra directa al precio fijado.", "&7▪ &fSubasta&7: gana la puja más alta.",
                    "&7▪ &fAnti-sniping&7: una puja en los últimos &f" + pl.getConfig().getInt("auction-house.anti-snipe.window-seconds", 120) + "s",
                    "&7  alarga la subasta.", "&7▪ Al vender se cobra una comisión."), () -> {}));
            return l;
        }).centerNav().back(back).open();
    }

    public void browse(Player p, Filter f, Runnable back) {
        new Browser(p, f, back).load();
    }

    private final class Browser extends com.economycore.gui.Menu {
        private Filter f;
        private final Runnable back;
        private List<Auction> all = new ArrayList<>();
        private final Set<Long> favs = ConcurrentHashMap.newKeySet();
        private int page;
        private String search = "";

        Browser(Player p, Filter f, Runnable back) {
            super(pl, p, f.sellerOnly() != null ? "&6Subastas &8➜ &6Mis subastas" : "&6Subastas &8➜ &6Explorar", 6);
            this.f = f;
            this.back = back;
        }

        void load() {
            UUID id = viewer.getUniqueId();
            svc.favorites(id).thenCompose(fs -> {
                favs.clear();
                favs.addAll(fs);
                return svc.list(f);
            }).whenComplete((l, t) -> Tasks.sync(() -> {
                if (t != null) { Msg.error(viewer, t); return; }
                all = l == null ? new ArrayList<>() : l;
                if (open) refresh(); else open();
            }));
        }

        private List<Auction> filtered() {
            if (search.isEmpty()) return all;
            String q = search.toLowerCase();
            List<Auction> out = new ArrayList<>();
            for (Auction a : all) if ((a.itemName() + " " + a.sellerName() + " " + a.material()).toLowerCase().contains(q)) out.add(a);
            return out;
        }

        @Override
        protected void render() {
            boolean mine = f.sellerOnly() != null;
            List<Auction> list = filtered();
            int pages = Math.max(1, (list.size() + GRID.length - 1) / GRID.length);
            if (page >= pages) page = pages - 1;
            if (page < 0) page = 0;
            for (int i = 0; i < GRID.length && page * GRID.length + i < list.size(); i++) {
                Auction a = list.get(page * GRID.length + i);
                set(GRID[i], icon(a, favs, mine), ci -> detail(viewer, a.id(), () -> new Browser(viewer, f, back).load()));
            }
            if (list.isEmpty()) set(22, Items.of(Material.BARRIER, "&cNo hay subastas", "&7Nada coincide con los filtros", "&7actuales.", "", "&e▸ Prueba a cambiar la categoría"));
            // categories
            for (int i = 0; i < CATS.length; i++) {
                final String cat = CATS[i];
                boolean sel = java.util.Objects.equals(cat, f.category());
                ItemStack it = Items.of(CAT_ICON[i], (sel ? "&a▶ " : "&e") + catName(cat), "&8Filtra por categoría", "", sel ? "&a✔ Seleccionada" : "&e▸ Clic para seleccionar");
                if (sel) Items.glow(it);
                set(1 + i, it, ci -> { f = f.withCategory(cat); page = 0; load(); });
            }
            // bottom bar
            if (page > 0) set(45, Items.of(Material.ARROW, "&e◀ Anterior", "&7Página &f" + page), c -> { page--; refresh(); });
            else set(45, Items.of(Material.GRAY_DYE, "&8◀ Anterior"));
            if (page < pages - 1) set(53, Items.of(Material.ARROW, "&eSiguiente ▶", "&7Página &f" + (page + 2)), c -> { page++; refresh(); });
            else set(53, Items.of(Material.GRAY_DYE, "&8Siguiente ▶"));
            int si = Math.max(0, java.util.Arrays.asList(AuctionService.SORTS).indexOf(f.sort()));
            List<String> sl = new ArrayList<>(List.of("&8Cambia el orden de la lista", ""));
            sl.addAll(Items.options(SORT_LABELS, si));
            sl.add(""); sl.add("&e▸ Clic para cambiar");
            set(47, Items.of(Material.HOPPER, "&aOrdenar", sl), c -> { f = f.withSort(AuctionService.SORTS[(si + 1) % AuctionService.SORTS.length]); page = 0; load(); });
            set(48, Items.of(Material.OAK_SIGN, "&bBuscar", "&7Filtro: &f" + (search.isEmpty() ? "ninguno" : search), "", "&e▸ Clic para escribir", "&e▸ Clic derecho para limpiar"), c -> {
                if (c.right()) { search = ""; page = 0; refresh(); return; }
                Prompt.ask(viewer, "Escribe el texto a buscar", s -> { search = s; page = 0; open(); }, this::open);
            });
            backButton(46);
            closeButton(49);
            int ti = 0;
            for (int i = 0; i < TYPES.length; i++) if (java.util.Objects.equals(TYPES[i], f.type())) ti = i;
            final int tIdx = ti;
            List<String> tl = new ArrayList<>(List.of("&8Compra directa o subastas", ""));
            tl.addAll(Items.options(TYPE_LABELS, ti));
            tl.add(""); tl.add("&e▸ Clic para cambiar");
            set(50, Items.of(Material.NAME_TAG, "&aTipo de venta", tl), c -> { f = f.withType(TYPES[(tIdx + 1) % TYPES.length]); page = 0; load(); });
            boolean fav = f.favoritesOf() != null;
            ItemStack favItem = Items.of(fav ? Material.NETHER_STAR : Material.GRAY_DYE, "&eSolo favoritos: " + (fav ? "&así" : "&cno"), "&8Muestra solo tus estrellas", "", "&e▸ Clic para alternar");
            set(51, fav ? Items.glow(favItem) : favItem, c -> { f = f.withFavorites(fav ? null : viewer.getUniqueId()); page = 0; load(); });
            set(52, Items.of(Material.PAPER, "&fPágina " + (page + 1) + "&8/&7" + pages, com.economycore.gui.Theme.bar(page + 1, pages, 10), "&7Resultados: &f" + list.size()));
            fillEmpty();
        }
    }

    public void detail(Player p, long id, Runnable back) {
        svc.get(id).thenAccept(a -> Tasks.sync(() -> {
            if (a == null || !a.state().equals("ACTIVE") || a.ends() <= System.currentTimeMillis()) { Msg.send(p, "ah-gone"); back.run(); return; }
            svc.favorites(p.getUniqueId()).thenAccept(favs -> Tasks.sync(() -> {
                boolean own = a.seller().equals(p.getUniqueId().toString());
                boolean admin = p.hasPermission("economycore.ah.admin");
                Runnable self = () -> detail(p, id, back);
                BigDecimal purse = com.economycore.core.Ledger.cached(p.getUniqueId(), a.currency());
                new ButtonMenu(pl, p, "&6Subasta &8➜ &6" + (a.isBin() ? "Compra directa" : "Pujas"), 6, true, () -> {
                    List<Btn> l = new ArrayList<>();
                    l.add(Btn.of(13, icon(a, favs, own), () -> {}));
                    l.add(Btn.of(11, Items.head(org.bukkit.Bukkit.getOfflinePlayer(UUID.fromString(a.seller())), "&6Vendedor", "&f" + a.sellerName(), "", "&7Termina en: &f" + Fmt.duration(a.ends() - System.currentTimeMillis())), () -> {}));
                    if (!a.isBin()) {
                        l.add(Btn.of(15, Items.of(Material.WRITABLE_BOOK, "&6Pujas", "&7Puja actual: " + (a.currentBid() == null ? "&8ninguna" : Money.fmt(a.currency(), a.currentBid())),
                                "&7Número de pujas: &f" + a.bidCount(), a.bidderName() != null ? "&7Líder: &f" + a.bidderName() : null,
                                "", "&8Una puja en los últimos segundos", "&8alarga la subasta."), () -> {}));
                    }
                    if (!own) {
                        if (a.isBin()) {
                            BigDecimal price = a.startPrice();
                            if (purse.compareTo(price) < 0) {
                                l.add(Btn.of(31, Items.of(Material.RED_TERRACOTTA, "&cNo tienes suficiente dinero", "&7Precio: " + Money.fmt(a.currency(), price), "&7Tu saldo: " + Money.fmt(a.currency(), purse),
                                        "&7Te faltan: " + Money.fmt(a.currency(), price.subtract(purse))), () -> {}));
                            } else {
                                l.add(Btn.of(31, Items.of(Material.GOLD_BLOCK, "&6Comprar ahora", "&7Precio: " + Money.fmt(a.currency(), price), "&7Tu saldo: " + Money.fmt(a.currency(), purse), "", "&e▸ Clic para comprar"), () ->
                                        ConfirmMenu.ask(pl, p, "&aConfirmar compra", List.of("&7" + a.itemName() + " x" + a.amount(), "&7Precio: " + Money.fmt(a.currency(), price)), () -> buyNow(p, id, self), self)));
                            }
                        } else {
                            BigDecimal min = svc.minBid(a);
                            BigDecimal plus = Money.scale(a.currency(), min.multiply(new BigDecimal("1.10")));
                            l.add(Btn.of(29, Items.of(Material.GOLD_INGOT, "&aPujar mínimo", "&7Puja: " + Money.fmt(a.currency(), min), "&7Tu saldo: " + Money.fmt(a.currency(), purse), "", "&e▸ Clic para pujar"), () -> bid(p, id, min, self)));
                            l.add(Btn.of(30, Items.of(Material.GOLD_BLOCK, "&ePujar +10%", "&7Puja: " + Money.fmt(a.currency(), plus), "", "&e▸ Clic para pujar"), () -> bid(p, id, plus, self)));
                            l.add(Btn.of(31, Items.of(Material.WRITABLE_BOOK, "&bPuja personalizada", "&7Mínimo: " + Money.fmt(a.currency(), min), "", "&e▸ Clic para escribir"), () ->
                                    Prompt.ask(p, "Escribe tu puja", s -> {
                                        BigDecimal v = Money.parse(s);
                                        if (v == null) { Msg.send(p, "invalid-amount"); self.run(); } else bid(p, id, v, self);
                                    }, self)));
                            if (a.buyNow() != null) l.add(Btn.of(32, Items.of(Material.EMERALD_BLOCK, "&6Comprar ya", "&7Precio: " + Money.fmt(a.currency(), a.buyNow()), "", "&e▸ Clic para comprar"), () ->
                                    ConfirmMenu.ask(pl, p, "&aConfirmar compra", List.of("&7" + a.itemName(), "&7Precio: " + Money.fmt(a.currency(), a.buyNow())), () -> buyNow(p, id, self), self)));
                        }
                    }
                    boolean fav = favs.contains(id);
                    ItemStack fi = Items.of(fav ? Material.NETHER_STAR : Material.GRAY_DYE, fav ? "&eQuitar de favoritos" : "&eAñadir a favoritos", "&8Marca la subasta con una estrella", "", "&e▸ Clic para alternar");
                    l.add(Btn.of(22, fav ? Items.glow(fi) : fi, () -> svc.toggleFavorite(p.getUniqueId(), id).whenComplete((r, t) -> Tasks.sync(self))));
                    if (own || admin) l.add(Btn.of(53, Items.of(Material.TNT, "&cCancelar subasta", own ? "&7Recuperas el objeto (si no hay pujas)." : "&7Cancelación administrativa.", "", "&c▸ Clic para cancelar"), () ->
                            ConfirmMenu.ask(pl, p, "&cCancelar subasta", List.of("&7" + a.itemName()), () ->
                                    svc.cancel(p, id, admin).whenComplete((r, t) -> Tasks.sync(() -> { if (t != null) Msg.error(p, t); else Msg.send(p, "ah-cancelled"); back.run(); })), self)));
                    return l;
                }).centerNav().back(back).open();
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
