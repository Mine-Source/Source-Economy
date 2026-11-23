package com.economycore.core;

import java.util.UUID;

/** A money holder: player, bank account, company, treasury, escrow or the system sink/source. */
public record Acct(String type, String id) {
    public static final Acct SYSTEM = new Acct("SYSTEM", "server");
    public static final Acct TREASURY = new Acct("TREASURY", "main");

    public static Acct player(UUID u) { return new Acct("PLAYER", u.toString()); }
    public static Acct player(String uuid) { return new Acct("PLAYER", uuid); }
    public static Acct bank(String id) { return new Acct("BANK", id); }
    public static Acct company(String id) { return new Acct("COMPANY", id); }
    public static Acct escrow(String name) { return new Acct("ESCROW", name); }

    public boolean isSystem() { return type.equals("SYSTEM"); }
    public boolean isPlayer() { return type.equals("PLAYER"); }
    public String key(String cur) { return type + ":" + id + ":" + cur; }
}
