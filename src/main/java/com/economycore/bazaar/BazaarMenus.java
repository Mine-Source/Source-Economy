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

    // ------------------------------------------------------------------ categories

    /** Same five sections as Hypixel's Bazaar, in a left column (slots 0, 9, 18, 27, 36). */
    private static final String[] CAT_PANE = {"YELLOW", "LIGHT_BLUE", "RED", "BROWN", "MAGENTA"};
    private static final String[] CATS = {"farming", "mining", "combat", "woods", "misc"};
    private static final String[] CAT_NAME = {"Cultivos", "Minería", "Combate", "Madera y pesca", "Curiosidades"};
    private static final String[] CAT_ICON = {"WHEAT", "DIAMOND_PICKAXE", "IRON_SWORD", "OAK_LOG", "ENDER_CHEST"};
    private static final int[] GRID = {11, 12, 13, 14, 15, 16, 20, 21, 22, 23, 24, 25, 29, 30, 31, 32, 33, 34, 38, 39, 40, 41, 42, 43};

    private static String catOf(Info i) {
        String c = i.product().category();
        if (c != null && !c.equals("misc")) return c;
        String n = i.product().material() == null ? i.product().id() : i.product().material();
        if (n.endsWith("_LOG") || n.endsWith("_PLANKS") || n.equals("STICK") || n.endsWith("_WOOD")) return "woods";
        if (n.endsWith("_INGOT") || n.endsWith("_ORE") || n.equals("COBBLESTONE") || n.equals("COAL") || n.equals("DIAMOND") || n.equals("EMERALD")
                || n.equals("REDSTONE") || n.equals("LAPIS_LAZULI") || n.equals("QUARTZ") || n.equals("OBSIDIAN") || n.equals("NETHERITE_SCRAP") || n.equals("AMETHYST_SHARD")) return "mining";
        if (n.equals("WHEAT") || n.equals("CARROT") || n.equals("POTATO") || n.equals("SUGAR_CANE") || n.equals("BEETROOT") || n.equals("MELON_SLICE") || n.equals("PUMPKIN")
                || n.endsWith("_SEEDS") || n.equals("COCOA_BEANS") || n.equals("SWEET_BERRIES") || n.equals("BAMBOO") || n.equals("CACTUS") || n.equals("KELP")) return "farming";
        if (n.equals("BONE") || n.equals("STRING") || n.equals("GUNPOWDER") || n.equals("ENDER_PEARL") || n.equals("BLAZE_ROD") || n.equals("SPIDER_EYE")
                || n.equals("ROTTEN_FLESH") || n.equals("SLIME_BALL") || n.equals("GHAST_TEAR") || n.equals("PHANTOM_MEMBRANE") || n.equals("BLAZE_POWDER")) return "combat";
        return "misc";
    }

    private ItemStack productIcon(Info i) {
        String cur = i.product().currency();
        int ci = Math.max(0, java.util.Arrays.asList(CATS).indexOf(catOf(i)));
        return Items.of(mat(i.product().id()), "&e" + i.product().name(), "&8" + CAT_NAME[ci], "",
                "&7Comprar al instante: " + (i.bestAsk() == null ? "&8sin ofertas" : Money.fmt(cur, i.bestAsk())),
                "&7Vender al instante: " + (i.bestBid() == null ? "&8sin ofertas" : Money.fmt(cur, i.bestBid())),
                "&7Último precio: " + (i.last() == null ? "&8-" : Money.fmt(cur, i.last())), "", "&e▸ Clic para ver opciones");
    }

    public void main(Player p, Runnable back) { new Browser(p, back, null).load(); }

    private final class Browser extends com.economycore.gui.Menu {
        private final Runnable back;
        private List<Info> all = new ArrayList<>();
        private String cat;
        private int page;
        private String search = "";

        Browser(Player p, Runnable back, String cat) {
            super(pl, p, "&6Bazaar", 6);
            this.back = back;
            this.cat = cat;
            this.parent = back;
        }

        void load() {
            svc.infos().whenComplete((l, t) -> Tasks.sync(() -> {
                if (t != null) { Msg.error(viewer, t); return; }
                all = l == null ? new ArrayList<>() : l;
                if (open) refresh(); else open();
            }));
        }

        private List<Info> filtered() {
            List<Info> out = new ArrayList<>();
            String q = search.toLowerCase();
            for (Info i : all) {
                if (cat != null && !cat.equals(catOf(i))) continue;
                if (!q.isEmpty() && !(i.product().name() + " " + i.product().id()).toLowerCase().contains(q)) continue;
                out.add(i);
            }
            return out;
        }

        @Override
        protected void render() {
            boolean admin = viewer.hasPermission("economycore.bazaar.admin");
            List<Info> list = filtered();
            int pages = Math.max(1, (list.size() + GRID.length - 1) / GRID.length);
            if (page >= pages) page = pages - 1;
            if (page < 0) page = 0;
            for (int i = 0; i < GRID.length && page * GRID.length + i < list.size(); i++) {
                Info inf = list.get(page * GRID.length + i);
                set(GRID[i], productIcon(inf), ci -> product(viewer, inf.product().id(), () -> Browser.this.load()));
            }
            if (list.isEmpty()) set(22, Items.of(Material.BARRIER, "&cSin productos", "&7No hay productos en esta categoría", "&7o con ese filtro."));
            int sel = java.util.Arrays.asList(CATS).indexOf(cat);
            for (int i = 0; i < CATS.length; i++) {
                final String c = CATS[i];
                boolean on = i == sel;
                ItemStack it = Items.of(CAT_ICON[i], (on ? "&a▶ " : "&6") + CAT_NAME[i], "&8Categoría de productos", "", on ? "&a✔ Seleccionada &8(clic para quitar el filtro)" : "&e▸ Clic para ver productos");
                if (on) Items.glow(it);
                set(9 * i, it, ci -> { cat = on ? null : c; page = 0; refresh(); });
            }
            final int last = pages - 1;
            if (page > 0) set(46, Items.of(Material.ARROW, "&aPágina anterior", "&8(" + page + "/" + pages + ")", "", "&bClic derecho: saltar 5 páginas", "&e▸ Clic para retroceder"),
                    c -> { page = Math.max(0, page - (c.right() ? 5 : 1)); refresh(); });
            if (page < last) set(53, Items.of(Material.ARROW, "&aPágina siguiente", "&8(" + (page + 2) + "/" + pages + ")", "", "&bClic derecho: saltar 5 páginas", "&e▸ Clic para avanzar"),
                    c -> { page = Math.min(last, page + (c.right() ? 5 : 1)); refresh(); });
            set(47, Items.of(Material.ENDER_CHEST, "&dReclamar compras", "&7Los objetos que compres llegan", "&7a tu buzón.", "", "&e▸ Clic para reclamar"),
                    c -> ((EconomyModule) pl.module("economy")).menus().mailbox(viewer, () -> Browser.this.load()));
            set(48, Items.of(Material.OAK_SIGN, "&bBuscar", "&7Filtro: &f" + (search.isEmpty() ? "ninguno" : search), "&7Productos: &f" + list.size(), "", "&e▸ Clic para escribir", "&e▸ Clic derecho para limpiar"), c -> {
                if (c.right()) { search = ""; page = 0; refresh(); return; }
                Prompt.ask(viewer, "Escribe el producto a buscar", s -> { search = s; page = 0; open(); }, this::open);
            });
            nav(navSlot());
            set(50, Items.of(Material.WRITABLE_BOOK, "&6Gestionar órdenes", "&7Revisa y cancela tus órdenes", "&7abiertas.", "", "&e▸ Clic para ver"), c -> myOrders(viewer, () -> Browser.this.load()));
            if (admin) set(51, Items.of(Material.EMERALD, "&aAñadir producto", "&7Hace comerciable un material.", "", "&e▸ Clic para añadir"), c ->
                    Prompt.ask(viewer, "Material (ej: DIAMOND) o escribe 'mano' para usar el objeto en tu mano", s -> {
                        Material m = s.equalsIgnoreCase("mano") && Inv.hand(viewer) != null ? Inv.hand(viewer).getType() : Material.matchMaterial(s.trim());
                        if (m == null || !m.isItem()) { Msg.send(viewer, "invalid-material"); open(); return; }
                        svc.addProduct(viewer.getName(), m, pl.currencies().defId()).whenComplete((r, t) -> Tasks.sync(() -> { if (t != null) Msg.error(viewer, t); load(); }));
                    }, this::open));
            fillEmpty(Items.pane(sel >= 0 ? CAT_PANE[sel] : "GRAY"));
        }
    }

    public void product(Player p, String id, Runnable back) {
        String cur = svc.currencyOf(id);
        svc.bookOf(id).thenAccept((Book b) -> Tasks.sync(() -> {
            Runnable self = () -> product(p, id, back);
            boolean admin = p.hasPermission("economycore.bazaar.admin");
            ItemStack tmpl = new ItemStack(mat(id));
            int have = Inv.count(p, tmpl);
            BigDecimal ask = b.asks().isEmpty() ? null : b.asks().get(0).price();
            BigDecimal bid = b.bids().isEmpty() ? null : b.bids().get(0).price();
            new ButtonMenu(pl, p, "&6Bazaar &8➜ &6" + Inv.pretty(mat(id)), 5, true, () -> {
                List<Btn> l = new ArrayList<>();
                l.add(Btn.of(4, Items.of(mat(id), "&e" + Inv.pretty(mat(id)), "&7Último precio: " + (b.last() == null ? "&8-" : Money.fmt(cur, b.last())), "&7Tienes: &f" + have), () -> {}));
                l.add(Btn.of(10, Items.of(Material.GOLD_INGOT, "&6Comprar al instante", "&8Compra a las mejores ofertas de venta", "",
                        "&7Precio por unidad: " + (ask == null ? "&8sin ofertas" : Money.fmt(cur, ask)),
                        "&7Stock a la venta: &f" + b.asks().stream().mapToInt(Level::qty).sum(), "", ask == null ? "&cNo hay ofertas de venta" : "&e▸ Clic para comprar"),
                        () -> { if (ask != null) buyAmount(p, id, cur, b, self); }));
                l.add(Btn.of(12, Items.of(Material.HOPPER, "&6Vender al instante", "&8Vende a las mejores órdenes de compra", "",
                        "&7Precio por unidad: " + (bid == null ? "&8sin ofertas" : Money.fmt(cur, bid)),
                        "&7Tienes: &f" + have, "", bid == null ? "&cNo hay órdenes de compra" : have == 0 ? "&cNo tienes este objeto" : "&e▸ Clic para vender"),
                        () -> { if (bid != null && have > 0) sellAmount(p, id, cur, b, have, self); }));
                l.add(Btn.of(14, Items.of(Material.WRITABLE_BOOK, "&aCrear orden de compra", "&8Fija tu precio máximo por unidad.", "&8El dinero queda reservado y la orden", "&8se completa sola cuando haya vendedores.", "",
                        "&e▸ Clic para crear"), () -> orderAmount(p, id, cur, b, "BUY", have, self)));
                l.add(Btn.of(16, Items.of(Material.MAP, "&6Crear oferta de venta", "&8Fija tu precio mínimo por unidad.", "&8Los objetos quedan reservados y se", "&8venden solos cuando haya compradores.", "",
                        "&7Tienes: &f" + have, "", "&e▸ Clic para crear"), () -> orderAmount(p, id, cur, b, "SELL", have, self)));
                List<String> al = new ArrayList<>(List.of("&8Los vendedores más baratos", ""));
                al.addAll(levelsLore(b.asks(), cur, "&c"));
                l.add(Btn.of(29, Items.of(Material.RED_STAINED_GLASS_PANE, "&cOfertas de venta", al), () -> {}));
                List<String> bl = new ArrayList<>(List.of("&8Los compradores que más pagan", ""));
                bl.addAll(levelsLore(b.bids(), cur, "&a"));
                l.add(Btn.of(33, Items.of(Material.LIME_STAINED_GLASS_PANE, "&aÓrdenes de compra", bl), () -> {}));
                if (ask != null && bid != null) {
                    BigDecimal spread = ask.subtract(bid);
                    l.add(Btn.of(31, Items.of(Material.COMPARATOR, "&fMercado", "&7Diferencia (spread): " + Money.fmt(cur, spread), "&7Último precio: " + (b.last() == null ? "&8-" : Money.fmt(cur, b.last()))), () -> {}));
                }
                l.add(Btn.of(42, Items.of(Material.BOOK, "&bMis órdenes", "&7Revisa y cancela tus órdenes.", "", "&e▸ Clic para ver"), () -> myOrders(p, self)));
                if (admin) l.add(Btn.of(44, Items.of(Material.TNT, "&cQuitar producto", "&7Lo elimina del bazaar.", "", "&c▸ Clic para quitar"), () ->
                        ConfirmMenu.ask(pl, p, "&cQuitar del bazaar", List.of("&7" + Inv.pretty(mat(id))), () ->
                                svc.removeProduct(p.getName(), id).whenComplete((r, t) -> Tasks.sync(() -> { if (t != null) Msg.error(p, t); back.run(); })), self)));
                return l;
            }).centerNav().back(back).open();
        }));
    }

    private int freeSpace(Player p, Material m) {
        int free = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) if (it == null || it.getType().isAir()) free++;
        return free * m.getMaxStackSize();
    }

    private void buyAmount(Player p, String id, String cur, Book b, Runnable back) {
        Material m = mat(id);
        int fill = Math.max(1, freeSpace(p, m));
        int stack = m.getMaxStackSize();
        int[][] opts = {{10, 1}, {12, stack}, {14, fill}};
        String[] names = {"&aComprar uno", "&aComprar un stack", "&aLlenar el inventario"};
        Material[] icons = {Material.GOLD_NUGGET, Material.GOLD_INGOT, Material.GOLD_BLOCK};
        new ButtonMenu(pl, p, "&6Comprar &8➜ &6" + Inv.pretty(m), 4, true, () -> {
            List<Btn> l = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                int qty = opts[i][1];
                Est e = estimate(b.asks(), qty);
                l.add(Btn.of(opts[i][0], Items.of(icons[i], names[i] + " &8(x" + qty + ")", "&7Cantidad: &f" + qty,
                        "&7Coste estimado: " + (e.filled() == 0 ? "&8-" : Money.fmt(cur, e.total())),
                        e.filled() < qty ? "&eSolo hay " + e.filled() + " visibles a la venta" : null, "", "&e▸ Clic para comprar"), () ->
                        exec(p, svc.place(p, id, "BUY", null, qty), "BUY", back)));
            }
            l.add(Btn.of(16, Items.of(Material.OAK_SIGN, "&bCantidad personalizada", "&7Escribe cuántas unidades.", "", "&e▸ Clic para escribir"), () ->
                    Prompt.ask(p, "Cantidad a comprar al instante", s -> {
                        Integer n = parseInt(s);
                        if (n == null) { Msg.send(p, "invalid-number"); back.run(); return; }
                        exec(p, svc.place(p, id, "BUY", null, n), "BUY", back);
                    }, back)));
            return l;
        }).centerNav().back(back).open();
    }

    private void sellAmount(Player p, String id, String cur, Book b, int have, Runnable back) {
        Material m = mat(id);
        int stack = m.getMaxStackSize();
        int[][] opts = {{10, 1}, {12, Math.min(stack, have)}, {14, have}};
        String[] names = {"&6Vender uno", "&6Vender un stack", "&6Vender todo"};
        Material[] icons = {Material.GOLD_NUGGET, Material.GOLD_INGOT, Material.GOLD_BLOCK};
        new ButtonMenu(pl, p, "&6Vender &8➜ &6" + Inv.pretty(m), 4, true, () -> {
            List<Btn> l = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                int qty = opts[i][1];
                Est e = estimate(b.bids(), qty);
                l.add(Btn.of(opts[i][0], Items.of(icons[i], names[i] + " &8(x" + qty + ")", "&7Cantidad: &f" + qty,
                        "&7Ingreso estimado: " + (e.filled() == 0 ? "&8-" : Money.fmt(cur, e.total())),
                        e.filled() < qty ? "&eSolo hay " + e.filled() + " visibles en compra" : null, "", "&e▸ Clic para vender"), () ->
                        exec(p, svc.place(p, id, "SELL", null, qty), "SELL", back)));
            }
            l.add(Btn.of(16, Items.of(Material.OAK_SIGN, "&bCantidad personalizada", "&7Tienes: &f" + have, "", "&e▸ Clic para escribir"), () ->
                    Prompt.ask(p, "Cantidad a vender al instante (tienes " + have + ")", s -> {
                        Integer n = parseInt(s);
                        if (n == null) { Msg.send(p, "invalid-number"); back.run(); return; }
                        exec(p, svc.place(p, id, "SELL", null, n), "SELL", back);
                    }, back)));
            return l;
        }).centerNav().back(back).open();
    }

    /** Step 1 of creating an order: how many units. */
    private void orderAmount(Player p, String id, String cur, Book b, String side, int have, Runnable back) {
        boolean buy = side.equals("BUY");
        Material m = mat(id);
        int stack = m.getMaxStackSize();
        int[] qs = {1, stack, buy ? stack * 9 : Math.max(1, have)};
        String[] names = buy ? new String[]{"Comprar uno", "Comprar un stack", "Comprar 9 stacks"} : new String[]{"Vender uno", "Vender un stack", "Vender todo lo que tienes"};
        int[] slots = {10, 12, 14};
        new ButtonMenu(pl, p, (buy ? "&aOrden de compra" : "&6Oferta de venta") + " &8➜ &6" + Inv.pretty(m), 4, true, () -> {
            List<Btn> l = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                int qty = qs[i];
                l.add(Btn.of(slots[i], Items.of(buy ? Material.LIME_DYE : Material.ORANGE_DYE, (buy ? "&a" : "&6") + names[i] + " &8(x" + qty + ")", "&7Cantidad: &f" + qty, "", "&e▸ Clic para elegir"), () -> orderPrice(p, id, cur, b, side, qty, back)));
            }
            l.add(Btn.of(16, Items.of(Material.OAK_SIGN, "&bCantidad personalizada", "&7Escribe cuántas unidades.", "", "&e▸ Clic para escribir"), () ->
                    Prompt.ask(p, "Cantidad" + (buy ? "" : " (tienes " + have + ")"), s -> {
                        Integer n = parseInt(s);
                        if (n == null) { Msg.send(p, "invalid-number"); back.run(); return; }
                        orderPrice(p, id, cur, b, side, n, back);
                    }, back)));
            return l;
        }).centerNav().back(back).open();
    }

    /** Step 2: price per unit. */
    private void orderPrice(Player p, String id, String cur, Book b, String side, int qty, Runnable back) {
        boolean buy = side.equals("BUY");
        int dec = pl.currencies().getOrDefault(cur).decimals();
        BigDecimal unit = BigDecimal.ONE.movePointLeft(dec);
        BigDecimal ref = buy ? (b.bids().isEmpty() ? null : b.bids().get(0).price()) : (b.asks().isEmpty() ? null : b.asks().get(0).price());
        if (ref == null) ref = b.last();
        final BigDecimal base = ref;
        new ButtonMenu(pl, p, "&6Precio por unidad &8➜ &6" + Inv.pretty(mat(id)), 4, true, () -> {
            List<Btn> l = new ArrayList<>();
            if (base != null) {
                BigDecimal better = buy ? base.add(base.multiply(new BigDecimal("0.01")).max(unit)) : base.subtract(base.multiply(new BigDecimal("0.01")).max(unit)).max(unit);
                better = Money.scale(cur, better);
                final BigDecimal bp = better;
                l.add(Btn.of(11, Items.of(Material.GOLD_INGOT, "&6Mejor precio " + (buy ? "+1%" : "-1%"), "&7Precio: " + Money.fmt(cur, bp), "&7Total: " + Money.fmt(cur, bp.multiply(BigDecimal.valueOf(qty))), "&8Te pone el primero de la cola.", "", "&e▸ Clic para elegir"),
                        () -> confirmOrder(p, id, cur, side, bp, qty, back)));
                l.add(Btn.of(13, Items.of(Material.GOLD_NUGGET, "&eIgualar el mejor precio", "&7Precio: " + Money.fmt(cur, base), "&7Total: " + Money.fmt(cur, base.multiply(BigDecimal.valueOf(qty))), "", "&e▸ Clic para elegir"),
                        () -> confirmOrder(p, id, cur, side, Money.scale(cur, base), qty, back)));
            }
            l.add(Btn.of(15, Items.of(Material.OAK_SIGN, "&bPrecio personalizado", "&7Escribe el precio por unidad.", "", "&e▸ Clic para escribir"), () ->
                    Prompt.ask(p, "Precio por unidad", s -> {
                        BigDecimal pr = Money.parse(s);
                        if (pr == null || pr.signum() <= 0) { Msg.send(p, "invalid-amount"); back.run(); return; }
                        confirmOrder(p, id, cur, side, Money.scale(cur, pr), qty, back);
                    }, back)));
            return l;
        }).centerNav().back(back).open();
    }

    private void confirmOrder(Player p, String id, String cur, String side, BigDecimal price, int qty, Runnable back) {
        boolean buy = side.equals("BUY");
        ConfirmMenu.ask(pl, p, buy ? "&aConfirmar orden de compra" : "&6Confirmar oferta de venta",
                List.of("&7Producto: &f" + Inv.pretty(mat(id)), "&7Cantidad: &f" + qty, "&7Precio por unidad: " + Money.fmt(cur, price),
                        "&7Total: " + Money.fmt(cur, price.multiply(BigDecimal.valueOf(qty))), buy ? "&8El dinero queda reservado." : "&8Los objetos quedan reservados."),
                () -> exec(p, svc.place(p, id, side, price, qty), side, back), back);
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
        new PagedMenu<Order>(pl, p, "&6Bazaar &8➜ &6Mis órdenes", () -> svc.myOrders(p.getUniqueId()), o -> {
            String cur = svc.currencyOf(o.product());
            return Items.of(mat(o.product()), (o.side().equals("BUY") ? "&aCompra " : "&6Venta ") + Inv.pretty(mat(o.product())),
                    "&7Precio por unidad: " + Money.fmt(cur, o.price()), "&7Ejecutadas: &f" + o.filled() + "&8/&7" + o.amount(),
                    "&7Pendientes: &f" + (o.amount() - o.filled()), "&8Creada: " + Fmt.date(o.created()), "", "&c▸ Clic para cancelar");
        }, (o, ci) -> ConfirmMenu.ask(pl, p, "&cCancelar orden", List.of("&7Se devuelve lo reservado"), () ->
                svc.cancel(p, o.id(), false).whenComplete((r, t) -> Tasks.sync(() -> { if (t != null) Msg.error(p, t); else Msg.send(p, "order-cancelled"); myOrders(p, back); })), () -> myOrders(p, back)))
                .back(back).load();
    }
}
