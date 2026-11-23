package com.economycore.analytics;

import com.economycore.analytics.AnalyticsService.AlertRow;
import com.economycore.analytics.AnalyticsService.Overview;
import com.economycore.analytics.AnalyticsService.Rank;
import com.economycore.analytics.AnalyticsService.Snap;
import com.economycore.core.Currency;
import com.economycore.core.EconomyCore;
import com.economycore.core.Fmt;
import com.economycore.core.Money;
import com.economycore.core.Msg;
import com.economycore.core.Tasks;
import com.economycore.gui.Btn;
import com.economycore.gui.ButtonMenu;
import com.economycore.gui.Items;
import com.economycore.gui.PagedMenu;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

public final class AnalyticsMenus {
    private final EconomyCore pl;
    private final AnalyticsService svc;

    public AnalyticsMenus(EconomyCore pl, AnalyticsService svc) {
        this.pl = pl;
        this.svc = svc;
    }

    private static boolean admin(Player p) { return p.hasPermission("economycore.analytics.admin"); }

    private String nextCur(String cur) {
        List<Currency> cs = new ArrayList<>(pl.currencies().all());
        int i = 0;
        for (int k = 0; k < cs.size(); k++) if (cs.get(k).id().equals(cur)) i = k;
        return cs.get((i + 1) % cs.size()).id();
    }

    private static double[] arr(List<BigDecimal> l) {
        double[] d = new double[l.size()];
        for (int i = 0; i < d.length; i++) d[i] = l.get(i).doubleValue();
        return d;
    }

    private ItemStack skull(String uuid, String name, List<String> lore) {
        ItemStack it = Items.of(Material.PLAYER_HEAD, name, lore);
        try {
            if (it.getItemMeta() instanceof SkullMeta sm) {
                OfflinePlayer op = org.bukkit.Bukkit.getOfflinePlayer(UUID.fromString(uuid));
                sm.setOwningPlayer(op);
                it.setItemMeta(sm);
            }
        } catch (Exception ignored) {}
        return it;
    }

    public void main(Player p, Runnable back) { main(p, pl.currencies().defId(), back); }

    public void main(Player p, String cur, Runnable back) {
        Runnable self = () -> main(p, cur, back);
        boolean adm = admin(p);
        svc.overview(cur).whenComplete((Overview o, Throwable t) -> Tasks.sync(() -> {
            if (t != null) { Msg.error(p, t); return; }
            new ButtonMenu(pl, p, "&bAnalítica (" + cur + ")", 4, true, () -> {
                List<Btn> l = new ArrayList<>();
                Snap s = o.latest();
                if (s == null) {
                    l.add(Btn.of(13, Items.of(Material.BARRIER, "&cSin datos todavía", "&7La primera instantánea se genera en unos minutos"), () -> {}));
                } else {
                    l.add(Btn.of(4, Items.of(Material.GOLD_BLOCK, "&6Masa monetaria: &f" + Money.fmt(cur, s.supply()),
                            "&7Jugadores: &f" + Money.fmt(cur, s.players()), "&7Bancos: &f" + Money.fmt(cur, s.banks()), "&7Empresas: &f" + Money.fmt(cur, s.companies()),
                            "&7Tesorería: &f" + Money.fmt(cur, s.treasury()), "&7En reserva (órdenes): &f" + Money.fmt(cur, s.escrow()), "&7Evolución: " + Fmt.spark(arr(o.supplyHistory()))), () -> {}));
                    l.add(Btn.of(10, Items.of(Material.REDSTONE, (o.inflationPct().signum() > 0 ? "&c" : "&a") + "Inflación 24h: " + (o.inflationPct().signum() > 0 ? "+" : "") + o.inflationPct() + "%",
                            "&7Variación de la masa monetaria", "&7Última instantánea: &f" + Fmt.date(s.ts())), () -> {}));
                    l.add(Btn.of(11, Items.of(Material.HOPPER, "&bCirculación: &f" + s.txCount() + " tx",
                            "&7Volumen último periodo: &f" + Money.fmt(cur, s.txVolume()), "&7Velocidad del dinero (24h): &f" + o.velocity() + "x"), () -> {}));
                    l.add(Btn.of(12, Items.of(Material.DIAMOND, "&eConcentración", "&7El jugador más rico posee &f" + o.topShare() + "%", "&7de la masa monetaria", "&7Cuentas de jugador: &f" + s.accounts()), () -> {}));
                }
                l.add(Btn.of(14, Items.of(Material.PLAYER_HEAD, "&6Ranking de saldo"), () -> ranking(p, cur, "balance", self)));
                l.add(Btn.of(15, Items.of(Material.NETHER_STAR, "&6Ranking de patrimonio", "&7Efectivo + bancos + acciones"), () -> ranking(p, cur, "worth", self)));
                l.add(Btn.of(16, Items.of(Material.EMERALD, "&6Mayores comerciantes (7 días)", "&7Volumen operado"), () -> ranking(p, cur, "traders", self)));
                l.add(Btn.of(20, Items.of(Material.BOOK, "&fEstadísticas generales"), () -> stats(p, self)));
                l.add(Btn.of(21, Items.of(Material.PAPER, "&fVolumen por tipo (24h)"), () -> volume(p, cur, self)));
                if (adm) l.add(Btn.of(22, Items.of(Material.BELL, "&cAlertas", "&7Avisos del sistema"), () -> alerts(p, self)));
                l.add(Btn.of(31, Items.of(Material.SUNFLOWER, "&eCambiar moneda", "&7Actual: &f" + cur), () -> main(p, nextCur(cur), back)));
                return l;
            }).back(back).open();
        }));
    }

    private void ranking(Player p, String cur, String kind, Runnable back) {
        CompletableFuture<List<Rank>> f = switch (kind) {
            case "worth" -> svc.netWorth(cur);
            case "traders" -> svc.topTraders(cur, System.currentTimeMillis() - 7 * 86_400_000L);
            default -> ((com.economycore.eco.EconomyModule) pl.module("economy")).service().top(cur, 100).thenApply(l -> {
                List<Rank> out = new ArrayList<>();
                for (var t : l) out.add(new Rank(t.uuid(), t.name(), t.amount()));
                return out;
            });
        };
        String title = switch (kind) { case "worth" -> "Patrimonio"; case "traders" -> "Comerciantes"; default -> "Saldo"; };
        int[] pos = {0};
        new PagedMenu<Rank>(pl, p, "&6Ranking: " + title + " (" + cur + ")", () -> f, r -> skull(r.uuid(), "&e" + r.name(), List.of("&7Valor: &f" + Money.fmt(cur, r.value()))), (r, ci) -> {})
                .searchable(Rank::name).back(back).load();
    }

    private void stats(Player p, Runnable back) {
        svc.stats().whenComplete((Map<String, String> m, Throwable t) -> Tasks.sync(() -> {
            if (t != null) { Msg.error(p, t); return; }
            new ButtonMenu(pl, p, "&fEstadísticas", 4, true, () -> {
                List<Btn> l = new ArrayList<>();
                int slot = 10;
                for (Map.Entry<String, String> e : m.entrySet()) {
                    l.add(Btn.of(slot, Items.of(Material.PAPER, "&e" + e.getKey(), "&f" + e.getValue()), () -> {}));
                    slot++;
                    if (slot % 9 == 8) slot += 2;
                }
                return l;
            }).back(back).open();
        }));
    }

    private void volume(Player p, String cur, Runnable back) {
        svc.volumeByType(cur, System.currentTimeMillis() - 86_400_000L).whenComplete((Map<String, BigDecimal> m, Throwable t) -> Tasks.sync(() -> {
            if (t != null) { Msg.error(p, t); return; }
            new ButtonMenu(pl, p, "&fVolumen 24h (" + cur + ")", 4, true, () -> {
                List<Btn> l = new ArrayList<>();
                int slot = 10;
                for (Map.Entry<String, BigDecimal> e : m.entrySet()) {
                    l.add(Btn.of(slot, Items.of(Material.PAPER, "&e" + e.getKey(), "&f" + Money.fmt(cur, e.getValue())), () -> {}));
                    slot++;
                    if (slot % 9 == 8) slot += 2;
                    if (slot > 34) break;
                }
                if (m.isEmpty()) l.add(Btn.of(13, Items.of(Material.BARRIER, "&cSin movimientos"), () -> {}));
                return l;
            }).back(back).open();
        }));
    }

    private void alerts(Player p, Runnable back) {
        Runnable self = () -> alerts(p, back);
        new PagedMenu<AlertRow>(pl, p, "&cAlertas", svc::alerts, a ->
                Items.of(a.ack() ? Material.GRAY_DYE : a.level().equals("WARN") ? Material.RED_DYE : Material.YELLOW_DYE, (a.ack() ? "&7" : "&c") + a.kind(), "&f" + a.message(), "&8" + Fmt.dateFull(a.ts()),
                        a.ack() ? "&7Leída" : "&eClic para marcar como leída"),
                (a, ci) -> { if (!a.ack()) svc.ack(a.id()).whenComplete((r, t) -> Tasks.sync(self)); })
                .searchable(a -> a.kind() + " " + a.message())
                .extras(() -> List.of(Btn.of(47, Items.of(Material.LIME_DYE, "&aMarcar todas como leídas"), () -> svc.ackAll().whenComplete((r, t) -> Tasks.sync(self)))))
                .back(back).load();
    }
}
