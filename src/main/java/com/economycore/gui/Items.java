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
            for (String s : lore) if (s != null) l.add("§r" + Msg.color(s));
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
        for (String s : extra) if (s != null) l.add("§r" + Msg.color(s));
        meta.setLore(l);
        it.setItemMeta(meta);
        return it;
    }

    public static ItemStack withLore(ItemStack base, String... extra) { return withLore(base, Arrays.asList(extra)); }

    public static ItemStack pane(String color) {
        return of(Compat.mat(Material.GRAY_STAINED_GLASS_PANE, color + "_STAINED_GLASS_PANE"), " ");
    }

    public static ItemStack filler() { return pane("BLACK"); }

    /** Adds the enchantment glint without showing any enchantment text. */
    public static ItemStack glow(ItemStack it) {
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            try { meta.setEnchantmentGlintOverride(true); it.setItemMeta(meta); } catch (Throwable ignored) {}
        }
        return it;
    }

    /** Hypixel-style option list: the selected entry is highlighted with an arrow. */
    public static List<String> options(String[] labels, int selected) {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) l.add(i == selected ? "&b▶ &f" + labels[i] : "&8  " + labels[i]);
        return l;
    }

    public static ItemStack head(org.bukkit.OfflinePlayer owner, String name, String... lore) {
        ItemStack it = of(Material.PLAYER_HEAD, name, lore);
        if (it.getItemMeta() instanceof org.bukkit.inventory.meta.SkullMeta sm) {
            try { sm.setOwningPlayer(owner); it.setItemMeta(sm); } catch (Throwable ignored) {}
        }
        return it;
    }
}
