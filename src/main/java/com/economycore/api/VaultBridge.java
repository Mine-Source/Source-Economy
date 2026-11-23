package com.economycore.api;

import com.economycore.core.Acct;
import com.economycore.core.EconomyCore;
import com.economycore.core.Ledger;
import com.economycore.core.Money;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.ServicePriority;

/**
 * Optional Vault bridge. Vault is NOT a compile dependency: the Economy interface is implemented at runtime
 * with a dynamic proxy, so other plugins that use Vault see EconomyCore's default currency.
 * Reads use the committed-balance cache; writes are queued on the DB thread, with pending withdrawals
 * reserved so a plugin cannot overspend between the call and the commit.
 */
public final class VaultBridge implements InvocationHandler {
    private final EconomyCore pl;
    private final Map<UUID, BigDecimal> pending = new ConcurrentHashMap<>();
    private Class<?> respClass;
    private Class<?> typeClass;
    private Class<Object> svc;
    private Object proxy;

    private VaultBridge(EconomyCore pl) { this.pl = pl; }

    /** Registers the Vault Economy service if Vault is present. Never throws. */
    public static void tryRegister(EconomyCore pl) {
        if (!pl.getConfig().getBoolean("vault.enabled", true)) return;
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) return;
        try {
            Class<?> eco = Class.forName("net.milkbowl.vault.economy.Economy");
            VaultBridge b = new VaultBridge(pl);
            b.respClass = Class.forName("net.milkbowl.vault.economy.EconomyResponse", true, eco.getClassLoader());
            b.typeClass = Class.forName("net.milkbowl.vault.economy.EconomyResponse$ResponseType", true, eco.getClassLoader());
            b.proxy = Proxy.newProxyInstance(eco.getClassLoader(), new Class<?>[]{eco}, b);
            @SuppressWarnings({"unchecked", "rawtypes"})
            Class<Object> svc = (Class) eco;
            b.svc = svc;
            Bukkit.getServicesManager().register(svc, b.proxy, pl, ServicePriority.Highest);
            if (pl.getConfig().getBoolean("vault.force-priority", true)) {
                Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
                    @org.bukkit.event.EventHandler
                    public void onLoad(org.bukkit.event.server.ServerLoadEvent e) { b.enforce(); }
                    @org.bukkit.event.EventHandler
                    public void onEnable(org.bukkit.event.server.PluginEnableEvent e) {
                        if (e.getPlugin() != pl) com.economycore.core.Tasks.later(1L, b::enforce);
                    }
                }, pl);
                b.enforce();
            }
            pl.getLogger().info("Vault detectado: EconomyCore registrado como proveedor de economía.");
        } catch (Throwable t) {
            pl.getLogger().log(Level.WARNING, "No se pudo registrar el puente de Vault", t);
        }
    }

    /** Removes any other Vault economy provider so EconomyCore is always the one in use. */
    private void enforce() {
        try {
            for (var reg : new java.util.ArrayList<>(Bukkit.getServicesManager().getRegistrations(svc))) {
                if (reg.getPlugin() == pl) continue;
                Bukkit.getServicesManager().unregister(svc, reg.getProvider());
                pl.getLogger().info("Proveedor de economía de " + reg.getPlugin().getName() + " desactivado en Vault (vault.force-priority).");
            }
            if (!Bukkit.getServicesManager().isProvidedFor(svc)
                    || Bukkit.getServicesManager().getRegistrations(svc).stream().noneMatch(r -> r.getPlugin() == pl)) {
                Bukkit.getServicesManager().register(svc, proxy, pl, ServicePriority.Highest);
            }
        } catch (Throwable t) {
            pl.getLogger().log(Level.WARNING, "No se pudo forzar la prioridad en Vault", t);
        }
    }

    private String cur() {
        String c = pl.getConfig().getString("vault.currency", "");
        return c != null && pl.currencies().exists(c) ? c.toLowerCase() : pl.currencies().defId();
    }

    @SuppressWarnings("deprecation")
    private static OfflinePlayer player(Object o) {
        if (o instanceof OfflinePlayer op) return op;
        if (o instanceof String s) {
            OfflinePlayer c = Bukkit.getOfflinePlayerIfCached(s);
            return c != null ? c : Bukkit.getOfflinePlayer(s);
        }
        return null;
    }

    private static double amountOf(Object[] a) {
        if (a == null) return 0;
        for (int i = a.length - 1; i >= 0; i--) if (a[i] instanceof Double d) return d;
        return 0;
    }

    private Object resp(double amount, double balance, String type, String err) {
        try {
            Object t = null;
            for (Object k : typeClass.getEnumConstants()) if (((Enum<?>) k).name().equals(type)) t = k;
            Constructor<?> c = respClass.getConstructor(double.class, double.class, typeClass, String.class);
            return c.newInstance(amount, balance, t, err);
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    private BigDecimal balance(UUID u) {
        String c = cur();
        if (!Ledger.CACHE.containsKey(Acct.player(u).key(c))) {
            pl.db().tx(tx -> {
                BigDecimal b = Ledger.bal(tx, Acct.player(u), c);
                Ledger.CACHE.putIfAbsent(Acct.player(u).key(c), b);
                return null;
            });
            return BigDecimal.ZERO;
        }
        return Ledger.cached(u, c);
    }

    private BigDecimal available(UUID u) {
        return balance(u).subtract(pending.getOrDefault(u, BigDecimal.ZERO));
    }

    private BigDecimal amt(double d) { return Money.scale(cur(), BigDecimal.valueOf(d)); }

    private Object withdraw(OfflinePlayer p, double d) {
        if (p == null) return resp(0, 0, "FAILURE", "Jugador no encontrado");
        if (d < 0 || Double.isNaN(d) || Double.isInfinite(d)) return resp(0, 0, "FAILURE", "Cantidad inválida");
        UUID u = p.getUniqueId();
        BigDecimal a = amt(d);
        BigDecimal av = available(u);
        if (a.signum() == 0) return resp(d, av.doubleValue(), "SUCCESS", null);
        if (av.compareTo(a) < 0) return resp(0, av.doubleValue(), "FAILURE", "Fondos insuficientes");
        pending.merge(u, a, BigDecimal::add);
        String c = cur();
        pl.db().tx(tx -> {
            Ledger.transfer(tx, Acct.player(u), Acct.SYSTEM, c, a, "VAULT_WITHDRAW", "vault");
            return null;
        }).whenComplete((r, t) -> {
            pending.compute(u, (k, v) -> { BigDecimal n = v == null ? BigDecimal.ZERO : v.subtract(a); return n.signum() <= 0 ? null : n; });
            if (t != null) pl.getLogger().warning("Vault withdraw failed for " + u + ": " + t.getMessage());
        });
        return resp(d, av.subtract(a).doubleValue(), "SUCCESS", null);
    }

    private Object deposit(OfflinePlayer p, double d) {
        if (p == null) return resp(0, 0, "FAILURE", "Jugador no encontrado");
        if (d < 0 || Double.isNaN(d) || Double.isInfinite(d)) return resp(0, 0, "FAILURE", "Cantidad inválida");
        UUID u = p.getUniqueId();
        BigDecimal a = amt(d);
        BigDecimal av = available(u);
        if (a.signum() == 0) return resp(d, av.doubleValue(), "SUCCESS", null);
        String c = cur();
        pl.db().tx(tx -> {
            Ledger.transfer(tx, Acct.SYSTEM, Acct.player(u), c, a, "VAULT_DEPOSIT", "vault");
            return null;
        }).whenComplete((r, t) -> { if (t != null) pl.getLogger().warning("Vault deposit failed for " + u + ": " + t.getMessage()); });
        return resp(d, av.add(a).doubleValue(), "SUCCESS", null);
    }

    @Override
    public Object invoke(Object proxy, Method m, Object[] a) {
        String n = m.getName();
        switch (n) {
            case "toString": return "EconomyCore-Vault";
            case "hashCode": return System.identityHashCode(proxy);
            case "equals": return a != null && a.length == 1 && proxy == a[0];
            case "isEnabled": return pl.isEnabled();
            case "getName": return "EconomyCore";
            case "hasBankSupport": return false;
            case "fractionalDigits": return pl.currencies().getOrDefault(cur()).decimals();
            case "format": return Money.plain(cur(), amt((Double) a[0]));
            case "currencyNamePlural":
            case "currencyNameSingular": return pl.currencies().getOrDefault(cur()).name();
            case "hasAccount":
            case "createPlayerAccount": return player(a[0]) != null;
            case "getBalance": {
                OfflinePlayer p = player(a[0]);
                return p == null ? 0.0 : available(p.getUniqueId()).doubleValue();
            }
            case "has": {
                OfflinePlayer p = player(a[0]);
                return p != null && available(p.getUniqueId()).compareTo(amt(amountOf(a))) >= 0;
            }
            case "withdrawPlayer": return withdraw(player(a[0]), amountOf(a));
            case "depositPlayer": return deposit(player(a[0]), amountOf(a));
            case "getBanks": return Collections.emptyList();
            default:
                if (n.startsWith("bank") || n.endsWith("Bank") || n.startsWith("isBank")) {
                    if (m.getReturnType() == boolean.class) return false;
                    return resp(0, 0, "NOT_IMPLEMENTED", "EconomyCore no soporta bancos de Vault");
                }
                if (m.getReturnType() == boolean.class) return false;
                if (m.getReturnType() == double.class) return 0.0;
                return null;
        }
    }
}
