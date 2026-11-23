package com.economycore.core;

import java.sql.SQLException;

public final class Audit {
    private Audit() {}

    public static void log(Tx tx, String actor, String action, String target, String detail) throws SQLException {
        Sql.exec(tx, "INSERT INTO audit(ts,actor,action,target,detail) VALUES(?,?,?,?,?)",
                System.currentTimeMillis(), actor, action, target, detail);
    }
}
