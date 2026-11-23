package com.economycore.market;

import com.economycore.core.EconomyCore;
import com.economycore.core.Tasks;

/** Shared service/menus for the Shop and Markets modules (the shop is a flagged market). */
public final class MarketHub {
    private static MarketHub instance;
    public final MarketService service;
    public final MarketMenus menus;

    private MarketHub(EconomyCore pl) {
        service = new MarketService(pl);
        menus = new MarketMenus(pl, service);
    }

    public static synchronized MarketHub of(EconomyCore pl) {
        if (instance == null) {
            instance = new MarketHub(pl);
            try { instance.service.seedShop(); } catch (Exception e) { pl.getLogger().warning("Shop seed failed: " + e); }
            pl.track(Tasks.timer(1200L, 1200L, () -> instance.service.rotate()));
        }
        return instance;
    }
}
