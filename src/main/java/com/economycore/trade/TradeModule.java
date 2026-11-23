package com.economycore.trade;

import com.economycore.core.Cmds;
import com.economycore.core.Fmt;
import com.economycore.core.Module;
import com.economycore.core.Msg;
import com.economycore.core.Sql;
import com.economycore.core.Tasks;
import com.economycore.gui.Btn;
import com.economycore.gui.Items;
import com.economycore.gui.PagedMenu;
import com.economycore.gui.Prompt;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public final class TradeModule extends Module implements Listener {
    private final Map<UUID, TradeSession> sessions = new ConcurrentHashMap<>();
    /** target -> requester */
    private final Map<UUID, UUID> requests = new ConcurrentHashMap<>();

    @Override public String id() { return "trade"; }
    @Override public String title() { return "&bIntercambios"; }
    @Override public String icon() { return "ENDER_EYE"; }
    @Override public String description() { return "Intercambio seguro entre jugadores"; }

    @Override
    public void enable() {
        pl.getServer().getPluginManager().registerEvents(this, pl);
        pl.track(Tasks.timer(200L, 200L, () -> {
            long timeout = pl.getConfig().getLong("trade.session-timeout-seconds", 300) * 1000L;
            for (TradeSession s : new ArrayList<>(sessions.values())) if (System.currentTimeMillis() - s.created > timeout) s.cancel("trade-timeout");
        }));
        Cmds.register(pl, "trade", "economycore.trade", true, (s, a) -> {
            Player p = (Player) s;
            if (a.length == 0) { open(p, p::closeInventory); return; }
            switch (a[0].toLowerCase()) {
                case "accept" -> accept(p);
                case "deny" -> {
                    UUID from = requests.remove(p.getUniqueId());
                    if (from == null) { Msg.send(p, "trade-no-request"); return; }
                    Player rp = Bukkit.getPlayer(from);
                    if (rp != null) Msg.send(rp, "trade-denied", p.getName());
                    Msg.send(p, "trade-deny-ok");
                }
                case "history" -> history(p, p::closeInventory);
                default -> request(p, a[0]);
            }
        }, (s, a) -> {
            if (a.length == 1) {
                List<String> o = new ArrayList<>(Arrays.asList("accept", "deny", "history"));
                o.addAll(Cmds.players(""));
                return Cmds.filter(o, a[0]);
            }
            return null;
        });
    }

    @Override
    public void disable() {
        for (TradeSession s : new ArrayList<>(sessions.values())) s.cancel("trade-cancelled");
    }

    @Override
    public void open(Player p, Runnable back) {
        new com.economycore.gui.ButtonMenu(pl, p, "&bIntercambios", 3, true, () -> List.of(
                Btn.of(11, Items.of(Material.ENDER_EYE, "&aIniciar intercambio", "&7Escribe el nombre del jugador"), () ->
                        Prompt.ask(p, "Nombre del jugador", n -> request(p, n), () -> open(p, back))),
                Btn.of(15, Items.of(Material.BOOK, "&eHistorial", "&7Tus intercambios anteriores"), () -> history(p, () -> open(p, back))))).back(back).open();
    }

    void end(TradeSession s) {
        sessions.remove(s.a.getUniqueId(), s);
        sessions.remove(s.b.getUniqueId(), s);
    }

    private void request(Player p, String name) {
        Player t = Bukkit.getPlayerExact(name);
        if (t == null || !t.isOnline()) { Msg.send(p, "player-not-found", name); return; }
        if (t.equals(p)) { Msg.send(p, "trade-self"); return; }
        if (sessions.containsKey(p.getUniqueId()) || sessions.containsKey(t.getUniqueId())) { Msg.send(p, "trade-busy"); return; }
        double maxD = pl.getConfig().getDouble("trade.max-distance", 0);
        if (maxD > 0 && (!p.getWorld().equals(t.getWorld()) || p.getLocation().distance(t.getLocation()) > maxD)) { Msg.send(p, "trade-too-far"); return; }
        requests.put(t.getUniqueId(), p.getUniqueId());
        Msg.send(p, "trade-request-sent", t.getName());
        Msg.send(t, "trade-request", p.getName());
        UUID tid = t.getUniqueId(), pid = p.getUniqueId();
        Tasks.later(600L, () -> requests.remove(tid, pid));
    }

    private void accept(Player p) {
        UUID from = requests.remove(p.getUniqueId());
        Player req = from == null ? null : Bukkit.getPlayer(from);
        if (req == null) { Msg.send(p, "trade-no-request"); return; }
        if (sessions.containsKey(p.getUniqueId()) || sessions.containsKey(req.getUniqueId())) { Msg.send(p, "trade-busy"); return; }
        TradeSession s = new TradeSession(pl, this, req, p);
        sessions.put(req.getUniqueId(), s);
        sessions.put(p.getUniqueId(), s);
        s.start();
    }

    private void history(Player p, Runnable back) {
        String id = p.getUniqueId().toString();
        new PagedMenu<String[]>(pl, p, "&6Historial de intercambios", () -> pl.db().tx(tx -> Sql.list(tx,
                "SELECT ts,a,a_name,b_name,a_items,b_items,a_money,b_money FROM trade_log WHERE a=? OR b=? ORDER BY id DESC LIMIT 300",
                rs -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8)}, id, id)),
                r -> {
                    boolean iAmA = r[1].equals(id);
                    String other = iAmA ? r[3] : r[2];
                    return Items.of(Material.ENDER_EYE, "&eCon " + other, "&7Diste: &f" + (iAmA ? r[4] : r[5]) + " &7+ $" + (iAmA ? r[6] : r[7]), "&7Recibiste: &f" + (iAmA ? r[5] : r[4]) + " &7+ $" + (iAmA ? r[7] : r[6]), "&8" + Fmt.date(Long.parseLong(r[0])));
                }, (r, c) -> {}).searchable(r -> r[2] + " " + r[3] + " " + r[4] + " " + r[5]).back(back).load();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        TradeSession s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null) s.cancel("trade-cancelled");
        requests.remove(e.getPlayer().getUniqueId());
    }
}
