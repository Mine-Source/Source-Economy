package com.economycore.sinks;

import com.economycore.core.EconomyCore;
import com.economycore.core.Sql;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Sink settings stored in the database (editable in-game); config.yml provides the defaults. */
public final class SinkSettings {
    public record Def(String key, String name, String icon, String def, String help) {}

    public static final Map<String, Def> DEFS = new LinkedHashMap<>();
    static {
        add("anvil.per-level", "Tarifa de yunque", "ANVIL", "5", "Dinero por nivel de coste en el yunque (0 = desactivado)");
        add("shop.per-day", "Mantenimiento de tiendas", "BARREL", "25", "Dinero por día por cada tienda de jugador abierta (0 = desactivado)");
        add("company.per-day", "Mantenimiento de empresas", "BRICKS", "100", "Dinero por día por empresa de jugador (0 = desactivado)");
        add("inactivity.days", "Días de inactividad", "CLOCK", "30", "Días sin conectarse antes de aplicar el decaimiento");
        add("inactivity.percent-per-day", "Decaimiento por inactividad", "COBWEB", "1", "% diario del saldo inactivo que se pierde (0 = desactivado)");
        add("inactivity.exempt-below", "Saldo exento", "EMERALD", "1000", "El decaimiento solo afecta lo que supere este saldo");
        add("destination", "Destino del dinero", "HOPPER", "BURN", "BURN destruye el dinero, TREASURY lo manda a la tesorería");
    }

    private static void add(String k, String n, String i, String d, String h) { DEFS.put(k, new Def(k, n, i, d, h)); }

    private final EconomyCore pl;
    private final Map<String, String> values = new ConcurrentHashMap<>();

    public SinkSettings(EconomyCore pl) {
        this.pl = pl;
        pl.db().tx(tx -> {
            for (String[] r : Sql.list(tx, "SELECT key,value FROM meta WHERE key LIKE 'sinks.%'", rs -> new String[]{rs.getString(1), rs.getString(2)}))
                values.put(r[0].substring(6), r[1]);
            return null;
        });
    }

    public String get(String key) {
        String v = values.get(key);
        if (v != null) return v;
        return pl.getConfig().getString("sinks." + key, DEFS.get(key).def());
    }

    public BigDecimal dec(String key) {
        try { return new BigDecimal(get(key).trim()); } catch (Exception e) { return BigDecimal.ZERO; }
    }

    public boolean burn() { return !get("destination").equalsIgnoreCase("TREASURY"); }

    public java.util.concurrent.CompletableFuture<Void> set(String key, String value) {
        values.put(key, value);
        return pl.db().tx(tx -> {
            Sql.exec(tx, "INSERT INTO meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", "sinks." + key, value);
            return null;
        });
    }
}
