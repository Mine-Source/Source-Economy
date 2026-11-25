package com.economycore.gui;

import com.economycore.core.EconomyCore;
import java.util.List;
import java.util.function.Supplier;
import org.bukkit.entity.Player;

/** Menu defined entirely by a list of buttons (rebuilt on every render). */
public final class ButtonMenu extends Menu {
    private final Supplier<List<Btn>> buttons;
    private final boolean fill;
    private boolean center;

    public ButtonMenu(EconomyCore pl, Player p, String title, int rows, boolean fill, Supplier<List<Btn>> buttons) {
        super(pl, p, title, rows);
        this.buttons = buttons;
        this.fill = fill;
    }

    /** Kept for source compatibility: navigation is always a single button at the bottom centre. */
    public ButtonMenu centerNav() { return this; }

    @Override public ButtonMenu back(Runnable parent) { this.parent = parent; return this; }

    @Override
    protected void render() {
        if (fill) fillEmpty();
        java.util.Set<Integer> used = new java.util.HashSet<>();
        for (Btn b : buttons.get()) { set(b.slot(), b.item(), b.action()); used.add(b.slot()); }
        int ns = navSlot();
        if (used.contains(ns)) {
            int rowStart = inv.getSize() - 9;
            for (int d = 1; d < 5 && used.contains(ns); d++) {
                if (ns - d >= rowStart && !used.contains(ns - d)) { ns = ns - d; break; }
                if (ns + d < inv.getSize() && !used.contains(ns + d)) { ns = ns + d; break; }
            }
        }
        if (!used.contains(ns)) nav(ns);
    }
}
