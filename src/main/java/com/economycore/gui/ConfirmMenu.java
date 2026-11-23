package com.economycore.gui;

import com.economycore.core.EconomyCore;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;

public final class ConfirmMenu extends Menu {
    private final List<String> lore;
    private final Runnable yes;
    private final Runnable no;

    public ConfirmMenu(EconomyCore pl, Player p, String title, List<String> lore, Runnable yes, Runnable no) {
        super(pl, p, title, 3);
        this.lore = lore;
        this.yes = yes;
        this.no = no;
    }

    public static void ask(EconomyCore pl, Player p, String title, List<String> lore, Runnable yes, Runnable no) {
        new ConfirmMenu(pl, p, title, lore, yes, no).open();
    }

    @Override
    protected void render() {
        fillEmpty();
        List<String> l = new ArrayList<>(lore);
        set(13, Items.of(Material.PAPER, "&eConfirmación", l));
        set(11, Items.of(Material.LIME_CONCRETE, "&a&lCONFIRMAR", "&7Clic para aceptar"), c -> { viewer.closeInventory(); yes.run(); });
        set(15, Items.of(Material.RED_CONCRETE, "&c&lCANCELAR", "&7Clic para cancelar"), c -> {
            if (no != null) no.run(); else viewer.closeInventory();
        });
    }
}
