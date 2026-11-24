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
        for (int s : new int[]{0, 9, 18, 1, 19}) set(s, Items.pane("LIME"));
        for (int s : new int[]{8, 17, 26, 7, 25}) set(s, Items.pane("RED"));
        List<String> l = new ArrayList<>(lore);
        set(13, Items.of(Material.PAPER, "&6&l⚠ Confirmación", l));
        set(11, Items.of(Material.LIME_CONCRETE, "&a&l✔ CONFIRMAR", "&7Clic para aceptar"), c -> { viewer.closeInventory(); yes.run(); });
        set(15, Items.of(Material.RED_CONCRETE, "&c&l✖ CANCELAR", "&7Clic para cancelar"), c -> {
            if (no != null) no.run(); else viewer.closeInventory();
        });
    }
}
