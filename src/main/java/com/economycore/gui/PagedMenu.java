package com.economycore.gui;

import com.economycore.core.EconomyCore;
import com.economycore.core.Msg;
import com.economycore.core.Tasks;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Hypixel-style paginated list: 6 rows, a framed grid of 28 slots (10-16, 19-25, 28-34, 37-43),
 * page arrows with "(n/N)" at 46 and 53, search at 48 and the single Go Back / Close button at 49.
 * Extra buttons may use the free bottom-row slots (45, 47, 50, 51, 52).
 */
public class PagedMenu<T> extends Menu {
    public static final int[] GRID = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};

    private List<T> all = new ArrayList<>();
    private int page;
    private String search = "";
    private final Supplier<CompletableFuture<List<T>>> loader;
    private final Function<T, ItemStack> icon;
    private final BiConsumer<T, ClickInfo> click;
    private Function<T, String> searchText;
    private Supplier<List<Btn>> extras;
    private String subtitle = "";
    private String frame = "BLACK";

    public PagedMenu(EconomyCore pl, Player p, String title, Supplier<CompletableFuture<List<T>>> loader,
                     Function<T, ItemStack> icon, BiConsumer<T, ClickInfo> click) {
        super(pl, p, title, 6);
        this.loader = loader;
        this.icon = icon;
        this.click = click;
    }

    public PagedMenu<T> searchable(Function<T, String> text) { this.searchText = text; return this; }

    public PagedMenu<T> extras(Supplier<List<Btn>> e) { this.extras = e; return this; }

    public PagedMenu<T> subtitle(String s) { this.subtitle = s; return this; }

    /** Colour of the frame panes (a stained glass colour name, e.g. "PURPLE"). */
    public PagedMenu<T> frame(String color) { this.frame = color; return this; }

    /** Kept for source compatibility; the grid is fixed at 28 slots. */
    public PagedMenu<T> pageSize(int n) { return this; }

    @Override public PagedMenu<T> back(Runnable parent) { this.parent = parent; return this; }

    /** Loads data asynchronously, then opens (or refreshes) the menu. */
    public void load() {
        loader.get().whenComplete((l, t) -> Tasks.sync(() -> {
            if (t != null) { Msg.error(viewer, t); return; }
            all = l == null ? new ArrayList<>() : l;
            if (open) refresh(); else open();
        }));
    }

    private List<T> filtered() {
        if (search.isEmpty() || searchText == null) return all;
        String q = search.toLowerCase(Locale.ROOT);
        List<T> out = new ArrayList<>();
        for (T t : all) if (searchText.apply(t).toLowerCase(Locale.ROOT).contains(q)) out.add(t);
        return out;
    }

    @Override
    protected void render() {
        List<T> list = filtered();
        int per = GRID.length;
        int pages = Math.max(1, (list.size() + per - 1) / per);
        if (page >= pages) page = pages - 1;
        if (page < 0) page = 0;
        int start = page * per;
        for (int i = 0; i < per && start + i < list.size(); i++) {
            T t = list.get(start + i);
            set(GRID[i], icon.apply(t), ci -> click.accept(t, ci));
        }
        if (list.isEmpty()) set(22, Items.of(Material.BARRIER, "&cNada que mostrar", search.isEmpty() ? "&7Todavía no hay elementos." : "&7Nada coincide con &f" + search));
        final int last = pages - 1;
        if (page > 0) set(46, Items.of(Material.ARROW, "&aPágina anterior", "&8(" + page + "/" + pages + ")", "", "&bClic derecho: saltar 5 páginas", "&e▸ Clic para retroceder"),
                c -> { page = Math.max(0, page - (c.right() ? 5 : 1)); flip(); });
        if (page < last) set(53, Items.of(Material.ARROW, "&aPágina siguiente", "&8(" + (page + 2) + "/" + pages + ")", "", "&bClic derecho: saltar 5 páginas", "&e▸ Clic para avanzar"),
                c -> { page = Math.min(last, page + (c.right() ? 5 : 1)); flip(); });
        if (searchText != null) {
            set(48, Items.of(Material.OAK_SIGN, "&bBuscar", "&7Filtro: &f" + (search.isEmpty() ? "ninguno" : search), "&7Resultados: &f" + list.size() + (all.size() != list.size() ? "&7 de &f" + all.size() : ""),
                    subtitle.isEmpty() ? null : subtitle, "", "&e▸ Clic para escribir", "&e▸ Clic derecho para limpiar"), c -> {
                if (c.right()) { search = ""; page = 0; refresh(); return; }
                Prompt.ask(viewer, "Escribe el texto a buscar", s -> { search = s; page = 0; open(); }, this::open);
            });
        }
        nav(navSlot());
        if (extras != null) for (Btn b : extras.get()) set(b.slot(), b.item(), b.action());
        fillEmpty(Items.pane(frame));
    }

    private void flip() {
        if (plugin.getConfig().getBoolean("gui.sounds", true)) {
            try { viewer.playSound(viewer.getLocation(), "item.book.page_turn", 0.6f, 1.0f); } catch (Throwable ignored) {}
        }
        refresh();
    }

    public void reset() { page = 0; search = ""; }

    public List<T> items() { return Collections.unmodifiableList(all); }
}
