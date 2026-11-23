package com.economycore.core;

import java.sql.SQLException;
import java.util.UUID;
import org.bukkit.entity.Player;

/** Name/UUID directory so offline players can be addressed. */
public final class Players {
    private Players() {}

    public static void touch(Tx tx, Player p) throws SQLException {
        long now = System.currentTimeMillis();
        Sql.exec(tx, "INSERT INTO players(uuid,name,first_seen,last_seen) VALUES(?,?,?,?) ON CONFLICT(uuid) DO UPDATE SET name=excluded.name,last_seen=excluded.last_seen",
                p.getUniqueId(), p.getName(), now, now);
    }

    public record Ref(UUID uuid, String name) {}

    public static Ref find(Tx tx, String name) throws SQLException {
        return Sql.one(tx, "SELECT uuid,name FROM players WHERE name=? COLLATE NOCASE", rs -> new Ref(UUID.fromString(rs.getString(1)), rs.getString(2)), name);
    }

    public static String name(Tx tx, String uuid) throws SQLException {
        String n = Sql.one(tx, "SELECT name FROM players WHERE uuid=?", rs -> rs.getString(1), uuid);
        return n == null ? uuid.substring(0, Math.min(8, uuid.length())) : n;
    }

    public static Ref require(Tx tx, String name) throws SQLException {
        Ref r = find(tx, name);
        if (r == null) throw new EcoException("player-not-found", name);
        return r;
    }
}
