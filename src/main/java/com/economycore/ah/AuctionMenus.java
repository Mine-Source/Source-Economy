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

    private static final String[] CATS = {"weapons", "armor", "tools", "food", "blocks", "misc"};
    private static final String[] CAT_ICON = {"DIAMOND_SWORD", "DIAMOND_CHESTPLATE", "DIAMOND_PICKAXE", "APPLE", "GRASS_BLOCK", "STICK"};
    private static final String[] CAT_NAME = {"Armas", "Armadura", "Herramientas", "Comida", "Bloques", "Varios"};
    private static final String[] CAT_PANE = {"PURPLE", "CYAN", "RED", "ORANGE", "GREEN", "MAGENTA"};
    private static final String[][] CAT_EX = {
            {"Espadas", "Arcos y ballestas", "Tridentes", "Munición"}, {"Cascos y petos", "Pantalones", "Botas", "Escudos"},
            {"Picos y hachas", "Palas y azadas", "Cañas de pescar", "Herramientas"}, {"Carne y pescado", "Pan y verduras", "Pociones", "Comida cocinada"},
            {"Bloques", "Piedra y minerales", "Madera", "Decoración"}, {"Libros y pociones", "Cabezas", "Redstone", "Todo lo demás"}};
    /** 6 columns x 4 rows, exactly like Hypixel's Auctions Browser. */
    private static final int[] GRID = {11, 12, 13, 14, 15, 16, 20, 21, 22, 23, 24, 25, 29, 30, 31, 32, 33, 34, 38, 39, 40, 41, 42, 43};
    private static final String[] SORT_LABELS = {"Termina pronto", "Más nuevas", "Precio ascendente", "Precio descendente"};
    private static final String[] TYPE_LABELS = {"Todos", "Compra directa", "Subastas"};
    private static final String[] TYPES = {null, "BIN", "AUCTION"};

    /** Entry point: main Auction House menu (4 rows, like Hypixel). */
    public void hub(Player p, Runnable back) {
        new ButtonMenu(pl, p, "&6Casa de subastas", 4, true, () -> {
            List<Btn> l = new ArrayList<>();
            l.add(Btn.of(11, Items.of(Material.GOLD_BLOCK, "&6Explorar subastas", "&7Busca objetos que otros jugadores", "&7han puesto a la venta.", "",
                    "&7Categorías, filtros, orden y búsqueda.", "", "&e▸ Clic para explorar"), () -> browse(p, defaultFilter(), () -> hub(p, back))));
            l.add(Btn.of(13, Items.of(Material.GOLDEN_CARROT, "&6Ver pujas", "&7Subastas en las que has pujado.", "", "&e▸ Clic para ver"),
                    () -> browse(p, new Filter(null, null, "ending", null, null, p.getUniqueId()), () -> hub(p, back))));
            l.add(Btn.of(15, Items.of(Material.GOLDEN_HORSE_ARMOR, "&6Crear subasta", "&7Pon a la venta el objeto que", "&7llevas en la mano.", "",
                    "&7Comisión de venta: &a" + pl.getConfig().getString("auction-house.commission-percent", "5") + "%", "", "&e▸ Clic para vender"), () -> sellHand(p, () -> hub(p, back))));
            l.add(Btn.of(20, Items.of(Material.CHEST, "&6Mis subastas", "&7Gestiona tus publicaciones activas", "&7y cancela las que quieras.", "",
                    "&7Máx. publicaciones: &f" + pl.getConfig().getInt("auction-house.max-listings", 10), "", "&e▸ Clic para ver"),
                    () -> browse(p, new Filter(null, null, "ending", null, p.getUniqueId()), () -> hub(p, back))));
            l.add(Btn.of(22, Items.of(Material.NETHER_STAR, "&eFavoritos", "&7Subastas que marcaste con una estrella.", "", "&e▸ Clic para ver"),
                    () -> browse(p, new Filter(null, null, "ending", p.getUniqueId(), null), () -> hub(p, back))));
            l.add(Btn.of(24, Items.of(Material.ENDER_CHEST, "&dReclamaciones", "&7Objetos ganados, vencidos o devueltos", "&7y dinero pendiente de cobrar.", "", "&e▸ Clic para reclamar"),
                    () -> ((EconomyModule) pl.module("economy")).menus().mailbox(p, () -> hub(p, back))));
            l.add(Btn.of(32, Items.of(Material.BOOK, "&bInformación", "&7▪ &fBIN&7: compra directa al precio fijado.", "&7▪ &fSubasta&7: gana la puja más alta.",
                    "&7▪ &fAnti-sniping&7: una puja en los últimos &f" + pl.getConfig().getInt("auction-house.anti-snipe.window-seconds", 120) + "s", "&7  alarga la subasta.", "&7▪ Al vender se cobra una comisión."), () -> {}));
            return l;
        }).back(back).open();
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
            super(pl, p, f.sellerOnly() != null ? "&6Subastas &8➜ &6Mis subastas" : f.bidderOnly() != null ? "&6Subastas &8➜ &6Mis pujas" : "&6Explorador de subastas", 6);
            this.f = f;
            this.back = back;
            this.parent = back;
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
                set(GRID[i], icon(a, favs, mine), ci -> detail(viewer, a.id(), () -> Browser.this.load()));
            }
            if (list.isEmpty()) set(22, Items.of(Material.BARRIER, "&cNo hay subastas", "&7Nada coincide con los filtros", "&7actuales."));
            int sel = java.util.Arrays.asList(CATS).indexOf(f.category());
            // categories: left column, slots 0, 9, 18, 27, 36, 45
            for (int i = 0; i < CATS.length; i++) {
                final String cat = CATS[i];
                boolean on = i == sel;
                List<String> lore = new ArrayList<>();
                lore.add("&8Categoría");
                lore.add("");
                lore.add("&7Ejemplos:");
                for (String e : CAT_EX[i]) lore.add("&8▪ &7" + e);
                lore.add("");
                lore.add(on ? "&a✔ Seleccionada &8(clic para quitar el filtro)" : "&e▸ Clic para ver objetos");
                ItemStack it = Items.of(CAT_ICON[i], (on ? "&a▶ " : "&6") + CAT_NAME[i], lore);
                if (on) Items.glow(it);
                set(9 * i, it, ci -> { f = f.withCategory(on ? null : cat); page = 0; load(); });
            }
            final int last = pages - 1;
            if (page > 0) set(46, Items.of(Material.ARROW, "&aPágina anterior", "&8(" + page + "/" + pages + ")", "", "&bClic derecho: saltar 5 páginas", "&e▸ Clic para retroceder"),
                    c -> { page = Math.max(0, page - (c.right() ? 5 : 1)); refresh(); });
            if (page < last) set(53, Items.of(Material.ARROW, "&aPágina siguiente", "&8(" + (page + 2) + "/" + pages + ")", "", "&bClic derecho: saltar 5 páginas", "&e▸ Clic para avanzar"),
                    c -> { page = Math.min(last, page + (c.right() ? 5 : 1)); refresh(); });
            set(48, Items.of(Material.OAK_SIGN, "&bBuscar", "&7Filtro: &f" + (search.isEmpty() ? "ninguno" : search), "&7Resultados: &f" + list.size(), "",
                    "&e▸ Clic para escribir", "&e▸ Clic derecho para limpiar"), c -> {
                if (c.right()) { search = ""; page = 0; refresh(); return; }
                Prompt.ask(viewer, "Escribe el texto a buscar", s -> { search = s; page = 0; open(); }, this::open);
            });
            nav(navSlot());
            int si = Math.max(0, java.util.Arrays.asList(AuctionService.SORTS).indexOf(f.sort()));
            List<String> sl = new ArrayList<>(List.of("&8Cambia el orden de la lista", ""));
            sl.addAll(Items.options(SORT_LABELS, si));
            sl.add(""); sl.add("&e▸ Clic para cambiar");
            set(50, Items.of(Material.HOPPER, "&aOrdenar", sl), c -> { f = f.withSort(AuctionService.SORTS[(si + 1) % AuctionService.SORTS.length]); page = 0; load(); });
            int ti = 0;
            for (int i = 0; i < TYPES.length; i++) if (java.util.Objects.equals(TYPES[i], f.type())) ti = i;
            final int tIdx = ti;
            List<String> tl = new ArrayList<>(List.of("&8Compra directa o subastas", ""));
            tl.addAll(Items.options(TYPE_LABELS, ti));
            tl.add(""); tl.add("&e▸ Clic para cambiar");
            set(51, Items.of(Material.NAME_TAG, "&aTipo de venta", tl), c -> { f = f.withType(TYPES[(tIdx + 1) % TYPES.length]); page = 0; load(); });
            boolean fav = f.favoritesOf() != null;
            ItemStack favItem = Items.of(fav ? Material.NETHER_STAR : Material.GRAY_DYE, "&eSolo favoritos: " + (fav ? "&así" : "&cno"), "&8Muestra solo tus estrellas", "", "&e▸ Clic para alternar");
            set(52, fav ? Items.glow(favItem) : favItem, c -> { f = f.withFavorites(fav ? null : viewer.getUniqueId()); page = 0; load(); });
            fillEmpty(Items.pane(sel >= 0 ? CAT_PANE[sel] : "GRAY"));
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
    private static final class Draft {
        boolean auction;
        BigDecimal price;
        BigDecimal buyNow;
        int hours;
        ItemStack item;
    }

    private static final int[] HOUR_PRESETS = {1, 2, 6, 12, 24, 48, 72, 168, 336, 720};

    public void sellHand(Player p, Runnable back) {
        ItemStack hand = Inv.hand(p);
        if (hand == null) { Msg.send(p, "hold-item"); return; }
        Draft d = new Draft();
        d.item = hand.clone();
        d.auction = false;
        d.price = Money.dec(pl.getConfig().getString("auction-house.min-price", "1"), BigDecimal.ONE);
        d.hours = Math.min(24, pl.getConfig().getInt("auction-house.max-duration-hours", 168));
        sellMenu(p, d, back);
    }

    private void sellMenu(Player p, Draft d, Runnable back) {
        int maxH = pl.getConfig().getInt("auction-house.max-duration-hours", 168);
        String cur = pl.currencies().defId();
        BigDecimal pct = Money.dec(pl.getConfig().getString("auction-house.commission-percent", "5"), BigDecimal.ZERO);
        Runnable self = () -> sellMenu(p, d, back);
        new ButtonMenu(pl, p, "&6Crear subasta", 6, true, () -> {
            List<Btn> l = new ArrayList<>();
            ItemStack hand = Inv.hand(p);
            boolean valid = hand != null && hand.isSimilar(d.item) && !(d.auction && d.buyNow != null && d.buyNow.compareTo(d.price) <= 0);
            l.add(Btn.of(13, Items.withLore(d.item, "", "&8Objeto a la venta", "&7Cantidad: &f" + d.item.getAmount(), "", "&8Debe seguir en tu mano al publicar"), () -> {}));
            l.add(Btn.of(11, Items.of(d.auction ? Material.GOLDEN_CARROT : Material.GOLD_INGOT, "&aTipo de venta", "&8Cómo se vende el objeto", "",
                    Items.options(new String[]{"Compra directa (BIN)", "Subasta con pujas"}, d.auction ? 1 : 0).get(0),
                    Items.options(new String[]{"Compra directa (BIN)", "Subasta con pujas"}, d.auction ? 1 : 0).get(1), "", "&e▸ Clic para cambiar"), () -> { d.auction = !d.auction; if (!d.auction) d.buyNow = null; self.run(); }));
            if (d.auction) l.add(new Btn(15, Items.of(Material.EMERALD, "&aCompra inmediata" + (d.buyNow == null ? " &8(opcional)" : ": " + Money.fmt(cur, d.buyNow)),
                    "&7Precio al que cualquiera puede", "&7llevárselo sin pujar.", "", "&e▸ Clic para escribir", "&e▸ Clic der: quitar"), c -> {
                if (c.right()) { d.buyNow = null; self.run(); return; }
                Prompt.ask(p, "Precio de compra inmediata", s -> {
                    BigDecimal v = Money.parse(s);
                    if (v == null || v.signum() <= 0) Msg.send(p, "invalid-amount"); else d.buyNow = v;
                    self.run();
                }, self);
            }));
            l.add(Btn.of(29, Items.of(Material.GOLD_NUGGET, d.auction ? "&6Puja inicial: &e" + Money.plain(cur, d.price) : "&6Precio: &e" + Money.plain(cur, d.price),
                    d.auction ? "&7Con qué cantidad empieza la subasta." : "&7Lo que pagará quien lo compre.", "", "&e▸ Clic para escribir"), () ->
                    Prompt.ask(p, d.auction ? "Puja inicial" : "Precio", s -> {
                        BigDecimal v = Money.parse(s);
                        if (v == null || v.signum() <= 0) Msg.send(p, "invalid-amount"); else d.price = v;
                        self.run();
                    }, self)));
            int hi = 0;
            for (int i = 0; i < HOUR_PRESETS.length; i++) if (HOUR_PRESETS[i] <= d.hours) hi = i;
            final int fhi = hi;
            l.add(new Btn(33, Items.of(Material.CLOCK, "&6Duración: &e" + Fmt.duration(d.hours * 3_600_000L), "&7Cuánto tiempo estará publicada.",
                    d.auction ? "&8Las pujas alargan la subasta." : "&8Si nadie compra, vuelve a ti.", "", "&e▸ Clic izq: siguiente", "&e▸ Clic der: escribir horas"), c -> {
                if (c.right()) { Prompt.ask(p, "Duración en horas (máx " + maxH + ")", s -> {
                    try { int h = Integer.parseInt(s.trim()); if (h >= 1 && h <= maxH) d.hours = h; else Msg.send(p, "invalid-number"); } catch (NumberFormatException e) { Msg.send(p, "invalid-number"); }
                    self.run();
                }, self); return; }
                int n = HOUR_PRESETS[(fhi + 1) % HOUR_PRESETS.length];
                d.hours = n > maxH ? HOUR_PRESETS[0] : n;
                self.run();
            }));
            BigDecimal fee = Money.scale(cur, d.price.multiply(pct).divide(BigDecimal.valueOf(100), 12, java.math.RoundingMode.HALF_UP));
            List<String> cl = new ArrayList<>();
            cl.add("&7Precio: " + Money.fmt(cur, d.price));
            cl.add("&7Comisión al vender: &a" + pct.stripTrailingZeros().toPlainString() + "% &8(~" + Money.plain(cur, fee) + ")");
            cl.add("&7Recibirás: " + Money.fmt(cur, Money.scale(cur, d.price.subtract(fee))));
            cl.add("&7Duración: &f" + Fmt.duration(d.hours * 3_600_000L));
            cl.add("");
            cl.add(valid ? "&e▸ Clic para publicar" : "&c✖ Sostén el objeto en la mano y revisa los precios");
            l.add(Btn.of(31, Items.of(valid ? Material.LIME_TERRACOTTA : Material.RED_TERRACOTTA, valid ? "&a✔ Crear " + (d.auction ? "subasta" : "oferta") : "&cNo se puede crear aún", cl), () -> {
                ItemStack h = Inv.hand(p);
                if (h == null || !h.isSimilar(d.item)) { Msg.send(p, "hold-item"); return; }
                if (d.auction && d.buyNow != null && d.buyNow.compareTo(d.price) <= 0) { Msg.send(p, "invalid-amount"); return; }
                create(p, d.auction ? "AUCTION" : "BIN", d.price, d.buyNow, d.hours, back);
            }));
            return l;
        }).back(back).open();
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
