package com.economycore.gui;

import com.economycore.core.Compat;
import com.economycore.core.Msg;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class Items {
    private Items() {}

    public static ItemStack of(Material m, String name, String... lore) {
        return of(m, name, Arrays.asList(lore));
    }

    public static ItemStack of(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m == null ? Material.STONE : m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§r" + Msg.color(name));
            List<String> l = new ArrayList<>();
            for (String s : lore) l.add("§r" + Msg.color(s));
            meta.setLore(l);
            meta.addItemFlags(ItemFlag.values());
            it.setItemMeta(meta);
        }
        return it;
    }

    public static ItemStack of(String material, String name, String... lore) {
        return of(Compat.mat(material), name, lore);
    }

    /** Copy of a real item with extra lore lines appended (keeps its own name/lore). */
    public static ItemStack withLore(ItemStack base, List<String> extra) {
        ItemStack it = base.clone();
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return it;
        List<String> l = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        for (String s : extra) l.add("§r" + Msg.color(s));
        meta.setLore(l);
        it.setItemMeta(meta);
        return it;
    }

    public static ItemStack withLore(ItemStack base, String... extra) { return withLore(base, Arrays.asList(extra)); }

    public static ItemStack pane(String color) {
        return of(Compat.mat(Material.GRAY_STAINED_GLASS_PANE, color + "_STAINED_GLASS_PANE"), " ");
    }

    public static ItemStack filler() { return pane("BLACK"); }

    public static ItemStack head(org.bukkit.OfflinePlayer owner, String name, String... lore) {
        ItemStack it = of(Material.PLAYER_HEAD, name, lore);
        if (it.getItemMeta() instanceof org.bukkit.inventory.meta.SkullMeta sm) {
            try { sm.setOwningPlayer(owner); it.setItemMeta(sm); } catch (Throwable ignored) {}
        }
        return it;
    }
}
