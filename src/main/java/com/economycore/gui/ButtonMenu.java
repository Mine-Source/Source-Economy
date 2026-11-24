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

    /** Hypixel-style navigation: back at the bottom row left of centre, close in the centre. */
    public ButtonMenu centerNav() { this.center = true; return this; }

    @Override public ButtonMenu back(Runnable parent) { this.parent = parent; return this; }

    @Override
    protected void render() {
        if (fill) fillEmpty();
        for (Btn b : buttons.get()) set(b.slot(), b.item(), b.action());
        if (center) { backButton(inv.getSize() - 6); closeButton(inv.getSize() - 5); }
        else { backButton(inv.getSize() - 9); closeButton(inv.getSize() - 1); }
    }
}
