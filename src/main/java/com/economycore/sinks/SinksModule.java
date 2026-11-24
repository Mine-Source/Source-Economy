package com.economycore.sinks;

import com.economycore.core.Acct;
import com.economycore.core.Charge;
import com.economycore.core.EcoException;
import com.economycore.core.Ledger;
import com.economycore.core.Module;
import com.economycore.core.Money;
import com.economycore.core.Msg;
import com.economycore.core.Notify;
import com.economycore.core.Sql;
import com.economycore.core.Tasks;
import com.economycore.gui.Btn;
import com.economycore.gui.ButtonMenu;
import com.economycore.gui.Items;
import com.economycore.gui.Prompt;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;

/** Money sinks that keep long-term inflation under control: anvil fee, upkeep of shops/companies, inactivity decay. */
public final class SinksModule extends Module implements Listener {
    private SinkSettings set;

    @Override public String id() { return "sinks"; }
    @Override public String title() { return "&cSumideros"; }
    @Override public String icon() { return "LAVA_BUCKET"; }
    @Override public String description() { return "Tarifas y mantenimiento que retiran dinero de la economía"; }
    @Override public String permission() { return "economycore.admin"; }

    @Override
    public void enable() {
        set = new SinkSettings(pl);
        pl.getServer().getPluginManager().registerEvents(this, pl);
        long min = Math.max(5, pl.getConfig().getLong("sinks.interval-minutes", 60));
        pl.track(Tasks.timer(min * 1200L, min * 1200L, () -> run(min)));
    }

    private Acct dest() { return set.burn() ? Acct.SYSTEM : Acct.TREASURY; }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAnvil(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!(e.getClickedInventory() instanceof AnvilInventory ai) || e.getSlot() != 2) return;
        ItemStack res = ai.getItem(2);
        if (res == null || res.getType() == Material.AIR) return;
        if (p.hasPermission("economycore.sinks.bypass")) return;
        BigDecimal per = set.dec("anvil.per-level");
        if (e.getCursor() != null && e.getCursor().getType() != Material.AIR && !e.isShiftClick()) return;
        int levels;
        try { levels = ai.getRepairCost(); } catch (Throwable t) { return; }
        if (per.signum() <= 0 || levels <= 0) return;
        BigDecimal fee = per.multiply(BigDecimal.valueOf(levels));
        String cur = pl.currencies().defId();
        if (!Charge.take(p, cur, fee, dest(), "SINK_ANVIL", "yunque"))  {
            e.setCancelled(true);
            Msg.send(p, "sink-anvil-short", Money.fmt(cur, fee));
            return;
        }
        Msg.send(p, "sink-anvil-paid", Money.fmt(cur, fee));
    }

    /** Periodic job: prorated upkeep and inactivity decay. Runs on the main thread, writes on the DB thread. */
    private void run(long intervalMin) {
        Set<String> online = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) online.add(p.getUniqueId().toString());
        BigDecimal frac = BigDecimal.valueOf(intervalMin).divide(BigDecimal.valueOf(1440), 8, RoundingMode.HALF_UP);
        BigDecimal shopFee = set.dec("shop.per-day").multiply(frac);
        BigDecimal compFee = set.dec("company.per-day").multiply(frac);
        BigDecimal decayPct = set.dec("inactivity.percent-per-day").multiply(frac);
        long cutoff = System.currentTimeMillis() - set.dec("inactivity.days").longValue() * 86_400_000L;
        BigDecimal exempt = set.dec("inactivity.exempt-below");
        Acct dest = dest();
        pl.db().tx(tx -> {
            if (shopFee.signum() > 0) {
                for (String[] s : Sql.list(tx, "SELECT id,owner,name,currency FROM pshops WHERE open=1", rs -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)})) {
                    UUID owner = UUID.fromString(s[1]);
                    try {
                        Ledger.transfer(tx, Acct.player(owner), dest, s[3], Money.scale(s[3], shopFee), "SINK_SHOP_UPKEEP", s[2]);
                    } catch (EcoException ex) {
                        Sql.exec(tx, "UPDATE pshops SET open=0 WHERE id=?", s[0]);
                        Notify.to(tx, owner, "sink-shop-closed", s[2]);
                    }
                }
            }
            if (compFee.signum() > 0) {
                for (String[] c : Sql.list(tx, "SELECT id,name,currency,ceo FROM companies WHERE kind='PLAYER' AND active=1", rs -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)})) {
                    try {
                        Ledger.transfer(tx, Acct.company(c[0]), dest, c[2], Money.scale(c[2], compFee), "SINK_COMPANY_UPKEEP", c[1]);
                    } catch (EcoException ex) {
                        if (c[3] != null && !c[3].isEmpty()) Notify.to(tx, UUID.fromString(c[3]), "sink-company-unpaid", c[1]);
                    }
                }
            }
            if (decayPct.signum() > 0) {
                List<String[]> rows = Sql.list(tx, "SELECT b.owner_id,b.currency,b.amount FROM balances b JOIN players p ON p.uuid=b.owner_id WHERE b.owner_type='PLAYER' AND p.last_seen<? LIMIT 5000",
                        rs -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}, cutoff);
                for (String[] r : new ArrayList<>(rows)) {
                    if (online.contains(r[0])) continue;
                    BigDecimal bal = new BigDecimal(r[2]);
                    BigDecimal base = bal.subtract(exempt);
                    if (base.signum() <= 0) continue;
                    BigDecimal d = Money.scale(r[1], base.multiply(decayPct).divide(BigDecimal.valueOf(100), 8, RoundingMode.DOWN));
                    if (d.signum() <= 0) continue;
                    Ledger.transfer(tx, Acct.player(r[0]), dest, r[1], d, "SINK_INACTIVITY", "inactividad");
                }
            }
            return null;
        });
    }

    @Override
    public void open(Player p, Runnable back) {
        new ButtonMenu(pl, p, "&c&lSumideros de dinero", 4, true, () -> {
            List<Btn> l = new ArrayList<>();
            int slot = 10;
            for (SinkSettings.Def d : SinkSettings.DEFS.values()) {
                Material m = Material.matchMaterial(d.icon());
                l.add(new Btn(slot, Items.of(m == null ? Material.PAPER : m, "&e" + d.name(), "&7Valor: &f" + set.get(d.key()), "&8" + d.help(), "", "&eClic para cambiar"),
                        c -> Prompt.ask(p, d.name() + ": escribe el nuevo valor", s -> {
                            if (d.key().equals("destination")) {
                                String v = s.trim().toUpperCase();
                                if (!v.equals("BURN") && !v.equals("TREASURY")) { Msg.send(p, "invalid-number"); open(p, back); return; }
                                set.set(d.key(), v).whenComplete((r, t) -> Tasks.sync(() -> open(p, back)));
                            } else {
                                BigDecimal v = Money.parse(s);
                                if (v == null || v.signum() < 0) { Msg.send(p, "invalid-number"); open(p, back); return; }
                                set.set(d.key(), v.toPlainString()).whenComplete((r, t) -> Tasks.sync(() -> open(p, back)));
                            }
                        }, () -> open(p, back))));
                slot += slot == 12 ? 2 : 1;
                if (slot == 17) slot = 19;
            }
            return l;
        }).back(back).open();
    }
}
