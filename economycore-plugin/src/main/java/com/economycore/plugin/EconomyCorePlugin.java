package com.economycore.plugin;

import com.economycore.api.EconomyService;
import com.economycore.api.MarketService;
import com.economycore.core.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.List;
import java.util.logging.Level;

/** Arranque: monedas, almacenamiento SQLite/MySQL, servicios públicos (ServicesManager) y recuperación periódica de entregas. */
public final class EconomyCorePlugin extends JavaPlugin {
    private Storage storage;
    private MarketEngine markets;

    @Override public void onEnable() {
        saveDefaultConfig();
        try {
            CurrencyRegistry currencies = new CurrencyRegistry();
            ConfigurationSection cs = getConfig().getConfigurationSection("currencies");
            if (cs != null) for (String id : cs.getKeys(false)) {
                currencies.register(new com.economycore.api.Currency(id, cs.getString(id + ".name", id),
                    cs.getString(id + ".symbol", "$"), cs.getInt(id + ".decimals", 2)));
            }
            storage = openStorage();
            EconomyEngine economy = new EconomyEngine(storage, currencies, Clock.systemUTC());
            markets = new MarketEngine(storage, currencies, new MarketRegistry(), Clock.systemUTC(), getConfig().getInt("max-delivery-attempts", 5));
            getServer().getServicesManager().register(EconomyService.class, economy, this, ServicePriority.Normal);
            getServer().getServicesManager().register(MarketService.class, markets, this, ServicePriority.Normal);
            long ticks = Math.max(10, getConfig().getLong("recovery-interval-seconds", 60)) * 20L;
            getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
                try { markets.recover(30_000); } catch (Throwable t) { getLogger().log(Level.WARNING, "recover falló", t); }
            }, ticks, ticks);
            getLogger().info("EconomyCore listo: " + currencies.all().size() + " monedas.");
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "No se pudo iniciar EconomyCore", t);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private Storage openStorage() {
        String type = getConfig().getString("storage.type", "sqlite").toLowerCase();
        if (type.equals("mysql")) {
            String url = "jdbc:mysql://" + getConfig().getString("storage.mysql.host") + ":" + getConfig().getInt("storage.mysql.port")
                + "/" + getConfig().getString("storage.mysql.database") + "?useUnicode=true&characterEncoding=utf8";
            String user = getConfig().getString("storage.mysql.user"), pass = getConfig().getString("storage.mysql.password", "");
            return new JdbcStorage(() -> DriverManager.getConnection(url, user, pass), JdbcStorage.Dialect.MYSQL);
        }
        getDataFolder().mkdirs();
        String url = "jdbc:sqlite:" + new File(getDataFolder(), "economy.db").getAbsolutePath();
        return new JdbcStorage(() -> DriverManager.getConnection(url), JdbcStorage.Dialect.SQLITE);
    }

    @Override public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        if (storage != null) storage.close();
    }

    @Override public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (markets == null) { sender.sendMessage("EconomyCore no está activo."); return true; }
        List<String> ids = markets.marketIds();
        sender.sendMessage(ids.isEmpty() ? "No hay mercados registrados." : "Mercados: " + String.join(", ", ids));
        return true;
    }
}
