package com.economycore.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

/** Inventory helpers (main thread) and portable item serialisation. */
public final class Inv {
    private Inv() {}

    /** Serialises a template (amount normalised to 1). */
    public static String encode(ItemStack it) {
        try {
            ItemStack c = it.clone();
            c.setAmount(1);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (BukkitObjectOutputStream out = new BukkitObjectOutputStream(bos)) {
                out.writeObject(c);
            }
            return Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialise item", e);
        }
    }

    public static ItemStack decode(String data) {
        if (data == null || data.isEmpty()) return null;
        try (BukkitObjectInputStream in = new BukkitObjectInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(data)))) {
            return (ItemStack) in.readObject();
        } catch (Exception e) {
            return null;
        }
    }

    public static ItemStack decodeOr(String data, String material) {
        ItemStack it = decode(data);
        if (it != null) return it;
        Material m = material == null ? null : Material.matchMaterial(material);
        return new ItemStack(m == null ? Material.BARRIER : m);
    }

    public static String pretty(Material m) {
        String[] parts = m.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    public static String name(ItemStack it) {
        if (it == null) return "-";
        ItemMeta m = it.getItemMeta();
        if (m != null && m.hasDisplayName()) return Msg.strip(m.getDisplayName());
        return pretty(it.getType());
    }

    public static boolean isEmpty(ItemStack it) { return it == null || it.getType().isAir() || it.getAmount() <= 0; }

    public static int count(Player p, ItemStack tmpl) {
        int n = 0;
        for (ItemStack s : p.getInventory().getStorageContents()) if (!isEmpty(s) && s.isSimilar(tmpl)) n += s.getAmount();
        return n;
    }

    /** Removes exactly amount similar items; false (and nothing removed) when not enough. */
    public static boolean take(Player p, ItemStack tmpl, int amount) {
        if (amount <= 0) return true;
        if (count(p, tmpl) < amount) return false;
        ItemStack[] c = p.getInventory().getStorageContents();
        int left = amount;
        for (int i = 0; i < c.length && left > 0; i++) {
            ItemStack s = c[i];
            if (isEmpty(s) || !s.isSimilar(tmpl)) continue;
            int t = Math.min(left, s.getAmount());
            left -= t;
            if (t == s.getAmount()) c[i] = null; else s.setAmount(s.getAmount() - t);
        }
        p.getInventory().setStorageContents(c);
        return true;
    }

    /** How many more items of this kind fit in the player's storage. */
    public static int space(Player p, ItemStack tmpl) {
        int max = tmpl.getMaxStackSize();
        int n = 0;
        for (ItemStack s : p.getInventory().getStorageContents()) {
            if (isEmpty(s)) n += max;
            else if (s.isSimilar(tmpl)) n += Math.max(0, max - s.getAmount());
        }
        return n;
    }

    /** Adds items; whatever does not fit is returned (count). */
    public static int give(Player p, ItemStack tmpl, int amount) {
        int left = amount;
        int max = Math.max(1, tmpl.getMaxStackSize());
        while (left > 0) {
            ItemStack s = tmpl.clone();
            s.setAmount(Math.min(max, left));
            java.util.Map<Integer, ItemStack> rest = p.getInventory().addItem(s);
            if (!rest.isEmpty()) {
                int r = 0;
                for (ItemStack x : rest.values()) r += x.getAmount();
                return left - s.getAmount() + r;
            }
            left -= s.getAmount();
        }
        return 0;
    }

    public static ItemStack hand(Player p) {
        ItemStack it = p.getInventory().getItemInMainHand();
        return isEmpty(it) ? null : it;
    }
}
