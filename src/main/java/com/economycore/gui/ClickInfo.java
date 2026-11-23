package com.economycore.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

public record ClickInfo(Player player, ClickType click, int slot, ItemStack current) {
    public boolean left() { return click == ClickType.LEFT || click == ClickType.SHIFT_LEFT; }
    public boolean right() { return click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT; }
    public boolean shift() { return click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT; }
    public boolean middle() { return click == ClickType.MIDDLE; }
}
