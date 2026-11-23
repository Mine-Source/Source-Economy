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

/** Generic paginated list with search, extra buttons, back and close. */
public class PagedMenu<T> extends Menu {
    private List<T> all = new ArrayList<>();
    private int page;
    private String search = "";
    private final Supplier<CompletableFuture<List<T>>> loader;
    private final Function<T, ItemStack> icon;
    private final BiConsumer<T, ClickInfo> click;
    private Function<T, String> searchText;
    private Supplier<List<Btn>> extras;
    private String subtitle = "";
    private int perPage = 45;

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

    /** Items per page (multiple of 9, max 45). Slots from perPage to 44 stay free for extra buttons. */
    public PagedMenu<T> pageSize(int n) { this.perPage = Math.max(9, Math.min(45, n)); return this; }

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
        int pages = Math.max(1, (list.size() + perPage - 1) / perPage);
        if (page >= pages) page = pages - 1;
        if (page < 0) page = 0;
        int start = page * perPage;
        for (int i = 0; i < perPage && start + i < list.size(); i++) {
            T t = list.get(start + i);
            set(i, icon.apply(t), ci -> click.accept(t, ci));
        }
        ItemStack f = Items.filler();
        for (int i = 45; i < 54; i++) inv.setItem(i, f);
        set(45, Items.of(Material.ARROW, "&ePágina anterior"), c -> { if (page > 0) { page--; refresh(); } });
        set(53, Items.of(Material.ARROW, "&ePágina siguiente"), c -> { if (page < pages - 1) { page++; refresh(); } });
        set(49, Items.of(Material.PAPER, "&fPágina " + (page + 1) + "/" + pages,
                "&7Resultados: &f" + list.size() + (all.size() != list.size() ? "&7 de " + all.size() : ""), subtitle.isEmpty() ? "" : subtitle));
        if (searchText != null) {
            set(48, Items.of(Material.COMPASS, "&bBuscar", "&7Filtro: &f" + (search.isEmpty() ? "ninguno" : search), "&eClic para escribir"), c ->
                    Prompt.ask(viewer, "Escribe el texto a buscar", s -> { search = s; page = 0; open(); }, this::open));
            set(50, Items.of(Material.HOPPER, "&bLimpiar filtro"), c -> { search = ""; page = 0; refresh(); });
        }
        backButton(46);
        closeButton(52);
        if (extras != null) for (Btn b : extras.get()) set(b.slot(), b.item(), b.action());
    }

    public void reset() { page = 0; search = ""; }

    public List<T> items() { return Collections.unmodifiableList(all); }
}
