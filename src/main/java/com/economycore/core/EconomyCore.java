package com.economycore.core;

import com.economycore.admin.AdminHub;
import com.economycore.ah.AuctionModule;
import com.economycore.analytics.AnalyticsModule;
import com.economycore.api.EconomyAPI;
import com.economycore.api.EconomyApiImpl;
import com.economycore.bank.BankModule;
import com.economycore.bazaar.BazaarModule;
import com.economycore.company.CompanyModule;
import com.economycore.eco.EconomyModule;
import com.economycore.gui.GuiListener;
import com.economycore.gui.Prompt;
import com.economycore.market.MarketModule;
import com.economycore.market.ShopModule;
import com.economycore.pshop.PShopModule;
import com.economycore.stock.StockModule;
import com.economycore.trade.TradeModule;
import com.economycore.trader.TraderModule;
import com.economycore.treasury.TreasuryModule;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class EconomyCore extends JavaPlugin {
    private static EconomyCore instance;
    private Db db;
    private Currencies currencies;
    private JoinListener joinListener;
    private AdminHub hub;
    private final Map<String, Module> modules = new LinkedHashMap<>();
    private final List<BukkitTask> tasks = new ArrayList<>();

    public static EconomyCore get() { return instance; }

    public Db db() { return db; }
    public Currencies currencies() { return currencies; }
    public AdminHub hub() { return hub; }
    public Map<String, Module> modules() { return modules; }

    public Module module(String id) { return modules.get(id); }

    public void track(BukkitTask t) { if (t != null) tasks.add(t); }

    @Override
    public void onEnable() {
        instance = this;
        Tasks.init(this);
        Compat.init();
        Cfg.mergeDefaults(this, "config.yml");
        Cfg.mergeDefaults(this, "messages.yml");
        reloadConfig();
        Msg.load(this);

        db = new Db(this);
        currencies = new Currencies(this);
        try {
            db.open();
            currencies.load(db.raw());
        } catch (Exception e) {
            getLogger().severe("Could not open the database: " + e);
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        for (String cmd : getDescription().getCommands().keySet()) Cmds.disabled(this, cmd);

        joinListener = new JoinListener(this);
        getServer().getPluginManager().registerEvents(new GuiListener(), this);
        getServer().getPluginManager().registerEvents(new Prompt(), this);
        getServer().getPluginManager().registerEvents(joinListener, this);

        hub = new AdminHub(this);

        List<Module> all = new ArrayList<>();
        all.add(new EconomyModule());
        all.add(new ShopModule());
        all.add(new MarketModule());
        all.add(new AuctionModule());
        all.add(new BazaarModule());
        all.add(new TraderModule());
        all.add(new TradeModule());
        all.add(new PShopModule());
        all.add(new BankModule());
        all.add(new CompanyModule());
        all.add(new StockModule());
        all.add(new TreasuryModule());
        all.add(new AnalyticsModule());
        for (Module m : all) {
            m.bind(this);
            if (!getConfig().getBoolean("modules." + m.id(), true) && !m.id().equals("economy")) continue;
            try {
                m.enable();
                modules.put(m.id(), m);
            } catch (Throwable t) {
                getLogger().severe("Module " + m.id() + " failed to start: " + t);
                t.printStackTrace();
            }
        }
        hub.registerCommand();

        getServer().getServicesManager().register(EconomyAPI.class, new EconomyApiImpl(this), this, ServicePriority.Normal);
        joinListener.prepareAll();

        if (getConfig().getBoolean("database.backup.enabled", true)) {
            long hours = Math.max(1, getConfig().getLong("database.backup.interval-hours", 6));
            long ticks = hours * 60L * 60L * 20L;
            track(Tasks.timer(ticks, ticks, () -> db.backup("economy")));
            db.backup("economy-startup");
        }
        getLogger().info("EconomyCore enabled (" + modules.size() + " modules, MC " + Compat.version() + ")");
    }

    @Override
    public void onDisable() {
        for (BukkitTask t : tasks) t.cancel();
        tasks.clear();
        for (Module m : new ArrayList<>(modules.values())) {
            try { m.disable(); } catch (Throwable t) { getLogger().warning("Module " + m.id() + " disable: " + t); }
        }
        getServer().getServicesManager().unregisterAll(this);
        if (db != null) {
            if (getConfig().getBoolean("database.backup.on-shutdown", false)) {
                try { db.backup("economy-shutdown").get(); } catch (Exception ignored) {}
            }
            db.shutdown();
        }
    }

    public void reloadAll() {
        Cfg.mergeDefaults(this, "config.yml");
        Cfg.mergeDefaults(this, "messages.yml");
        reloadConfig();
        Msg.load(this);
    }
}
