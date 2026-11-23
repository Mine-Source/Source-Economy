package com.economycore.gui;

import java.util.function.Consumer;
import org.bukkit.inventory.ItemStack;

public record Btn(int slot, ItemStack item, Consumer<ClickInfo> action) {
    public static Btn of(int slot, ItemStack item, Runnable r) { return new Btn(slot, item, c -> r.run()); }
}
