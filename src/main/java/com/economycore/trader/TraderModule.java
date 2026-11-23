package com.economycore.trader;

import com.economycore.core.Cmds;
import com.economycore.core.Module;
import com.economycore.core.Msg;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.metadata.MetadataValue;

/** Traders are bound to NPCs from other plugins through identifiers; this module never creates NPCs. */
public final class TraderModule extends Module implements Listener {
    private static final Map<UUID, String> CAPTURE = new ConcurrentHashMap<>();
    private TraderService svc;
    private TraderMenus menus;

    public static void capture(UUID admin, String trader) { CAPTURE.put(admin, trader); }

    @Override public String id() { return "traders"; }
    @Override public String title() { return "&aComerciantes"; }
    @Override public String icon() { return "VILLAGER_SPAWN_EGG"; }
    @Override public String description() { return "Ofertas enlazadas a NPCs por identificador"; }

    @Override
    public void enable() {
        svc = new TraderService(pl);
        try { svc.loadBindings(); } catch (Exception e) { pl.getLogger().warning("Trader bindings: " + e); }
        menus = new TraderMenus(pl, svc);
        pl.getServer().getPluginManager().registerEvents(this, pl);
        Cmds.register(pl, "trader", "economycore.use", false, (s, a) -> {
            if (a.length >= 2 && a[0].equalsIgnoreCase("open")) {
                Player target = a.length > 2 ? Bukkit.getPlayerExact(a[2]) : (s instanceof Player pp ? pp : null);
                if (target == null) { Msg.send(s, "player-not-found", a.length > 2 ? a[2] : "?"); return; }
                if (!s.equals(target) && !s.hasPermission("economycore.trader.admin")) { Msg.send(s, "no-permission"); return; }
                menus.open(target, a[1].toLowerCase(), target::closeInventory);
                return;
            }
            if (s instanceof Player p) menus.list(p, p::closeInventory); else Msg.send(s, "usage-trader");
        }, (s, a) -> a.length == 1 ? Cmds.filter(List.of("open"), a[0]) : null);
    }

    @Override
    public void open(Player p, Runnable back) { menus.list(p, back); }

    private List<String> keys(Entity e) {
        List<String> k = new ArrayList<>();
        k.add("uuid:" + e.getUniqueId());
        if (e.getCustomName() != null) k.add("name:" + Msg.strip(e.getCustomName()).toLowerCase());
        e.getScoreboardTags().forEach(t -> k.add("tag:" + t));
        List<MetadataValue> m = e.getMetadata("NPC-ID");
        if (!m.isEmpty()) k.add("citizens:" + m.get(0).asString());
        return k;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        String cap = CAPTURE.remove(p.getUniqueId());
        if (cap != null) {
            e.setCancelled(true);
            List<String> ks = keys(e.getRightClicked());
            String key = ks.stream().filter(x -> x.startsWith("citizens:")).findFirst().orElse(ks.size() > 1 && ks.get(1).startsWith("name:") ? ks.get(1) : ks.get(0));
            svc.bind(p.getName(), key, cap).whenComplete((r, t) -> com.economycore.core.Tasks.sync(() -> Msg.send(p, t == null ? "trader-bound" : "internal-error", key)));
            return;
        }
        if (svc.bindings.isEmpty()) return;
        for (String k : keys(e.getRightClicked())) {
            String trader = svc.bindings.get(k);
            if (trader != null) {
                e.setCancelled(true);
                menus.open(p, trader, p::closeInventory);
                return;
            }
        }
    }
}
