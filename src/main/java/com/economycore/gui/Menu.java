package com.economycore.gui;

import com.economycore.core.EconomyCore;
import com.economycore.core.Msg;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/** Base GUI. All clicks are cancelled; only registered buttons act. Identified by InventoryHolder (version independent). */
public abstract class Menu implements InventoryHolder {
    protected final EconomyCore plugin;
    protected final Player viewer;
    protected final Inventory inv;
    private final Map<Integer, Consumer<ClickInfo>> actions = new HashMap<>();
    private long lastClick;
    protected boolean open;
    protected Runnable parent;

    protected Menu(EconomyCore plugin, Player viewer, String title, int rows) {
        this.plugin = plugin;
        this.viewer = viewer;
        String t = com.economycore.gui.Theme.paintTitle(title);
        if (t.length() > 190) t = t.substring(0, 190);
        this.inv = Bukkit.createInventory(this, rows * 9, t);
    }

    protected abstract void render();

    public Menu back(Runnable parent) { this.parent = parent; return this; }

    public void open() {
        inv.clear();
        actions.clear();
        render();
        open = true;
        viewer.openInventory(inv);
    }

    /** Re-render in place (only if still open). */
    public void refresh() {
        if (!open) return;
        inv.clear();
        actions.clear();
        render();
    }

    protected void set(int slot, ItemStack it, Consumer<ClickInfo> action) {
        if (slot < 0 || slot >= inv.getSize()) return;
        inv.setItem(slot, it);
        if (action != null) actions.put(slot, action);
    }

    protected void set(int slot, ItemStack it) { set(slot, it, null); }

    protected void fillBorder() {
        int n = inv.getSize();
        ItemStack f = Items.filler();
        for (int i = 0; i < n; i++) {
            if (i < 9 || i >= n - 9 || i % 9 == 0 || i % 9 == 8) inv.setItem(i, f);
        }
    }

    protected void fillEmpty() {
        ItemStack f = Items.filler();
        for (int i = 0; i < inv.getSize(); i++) if (inv.getItem(i) == null) inv.setItem(i, f);
    }

    protected void fillEmpty(ItemStack f) {
        for (int i = 0; i < inv.getSize(); i++) if (inv.getItem(i) == null) inv.setItem(i, f);
    }

    protected void backButton(int slot) {
        if (parent != null) set(slot, Items.of(Material.ARROW, "&e◀ Volver", "&8Regresa al menú anterior"), c -> parent.run());
    }

    /** Hypixel rule: one button at the bottom centre. Sub-menus get "Volver", root menus get "Cerrar" - never both. */
    protected void nav(int slot) {
        if (parent != null) set(slot, Items.of(Material.ARROW, "&a◀ Volver", "&7Regresa al menú anterior"), c -> parent.run());
        else set(slot, Items.of(Material.BARRIER, "&c✖ Cerrar"), c -> viewer.closeInventory());
    }

    protected int navSlot() { return inv.getSize() - 5; }

    protected void closeButton(int slot) {
        set(slot, Items.of(Material.BARRIER, "&c✖ Cerrar", "&8Cierra este menú"), c -> viewer.closeInventory());
    }

    public void handleTop(ClickInfo ci) {
        long now = System.currentTimeMillis();
        if (now - lastClick < 120) return;
        lastClick = now;
        Consumer<ClickInfo> a = actions.get(ci.slot());
        if (a != null) {
            if (plugin.getConfig().getBoolean("gui.sounds", true)) {
                try { viewer.playSound(viewer.getLocation(), "ui.button.click", 0.4f, 1.4f); } catch (Throwable ignored) {}
            }
            a.accept(ci);
        }
    }

    /** Clicks in the player's own inventory while this menu is open. */
    public void handleBottom(ClickInfo ci) {}

    public void onClose() { open = false; }

    public boolean allowsDrag() { return false; }

    @Override
    public Inventory getInventory() { return inv; }
}
