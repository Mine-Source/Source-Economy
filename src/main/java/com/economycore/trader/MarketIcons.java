package com.economycore.trader;

import org.bukkit.Material;

final class MarketIcons {
    private MarketIcons() {}

    static Material icon(String name, Material def) {
        Material m = name == null ? null : Material.matchMaterial(name);
        return m == null ? def : m;
    }
}
