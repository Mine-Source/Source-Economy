package com.economycore.core;

import java.sql.SQLException;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Persistent alerts shown to admins in game. */
public final class Alerts {
    private Alerts() {}

    public static void raise(Tx tx, String level, String kind, String message) throws SQLException {
        Sql.exec(tx, "INSERT INTO alerts(ts,level,kind,message) VALUES(?,?,?,?)", System.currentTimeMillis(), level, kind, message);
        tx.afterCommit(() -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("economycore.alerts")) p.sendMessage(Msg.get("alert", level, message));
            }
        });
    }
}
