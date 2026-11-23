package com.economycore.core;

import org.bukkit.entity.Player;

/** A functional unit of the plugin (shop, auction house, bank, ...). Enabled/disabled from config.yml. */
public abstract class Module {
    protected EconomyCore pl;

    public final void bind(EconomyCore pl) { this.pl = pl; }

    public abstract String id();
    public abstract String title();
    public abstract String icon();
    public abstract String description();
    public abstract void enable();
    public void disable() {}

    /** Permission needed to open this module from the hub. */
    public String permission() { return "economycore.use"; }

    /** Opens the module's main menu for the player. */
    public abstract void open(Player p, Runnable back);
}
