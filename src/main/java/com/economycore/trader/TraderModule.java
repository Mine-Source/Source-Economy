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
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.metadata.MetadataValue;

/** Traders are bound to NPCs from other plugins through identifiers; this module never creates NPCs. */
public final class TraderModule extends Module implements Listener {
    private static final Map<UUID, String> CAPTURE = new ConcurrentHashMap<>();
    private TraderService svc;
    private TraderMenus menus;

    public static void capture(UUID admin, String trader) { CAPTURE.put(admin, trader); }

    /** Open native villager windows: which offers each recipe index maps to. */
    private record Session(String trader, List<Long> offers, Runnable back) {}
    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final java.util.Set<UUID> BUSY = ConcurrentHashMap.newKeySet();

    static void startSession(UUID p, String trader, List<Long> offers, Runnable back) { SESSIONS.put(p, new Session(trader, offers, back)); }

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
                String mode = a.length > 3 ? a[3].toUpperCase() : null;
                if (mode != null && !mode.equals("VILLAGER") && !mode.equals("MENU")) mode = null;
                menus.open(target, a[1].toLowerCase(), target::closeInventory, mode);
                return;
            }
            if (s instanceof Player p) menus.list(p, p::closeInventory); else Msg.send(s, "usage-trader");
        }, (s, a) -> a.length == 1 ? Cmds.filter(List.of("open"), a[0]) : a.length == 4 ? Cmds.filter(List.of("villager", "menu"), a[3]) : null);
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

    /** The result slot of our virtual villager window runs the real (database-backed) trade instead of vanilla's. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMerchantClick(InventoryClickEvent e) {
        if (!(e.getInventory() instanceof MerchantInventory mi)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (mi.getMerchant() instanceof Entity) return;
        Session s = SESSIONS.get(p.getUniqueId());
        if (s == null) return;
        if (e.getRawSlot() != 2 || e.getSlotType() != InventoryType.SlotType.RESULT) return;
        e.setCancelled(true);
        int idx = mi.getSelectedRecipeIndex();
        if (idx < 0 || idx >= s.offers().size()) return;
        if (!BUSY.add(p.getUniqueId())) return;
        long offerId = s.offers().get(idx);
        // inputs go back to the inventory; the service then takes the exact cost from it
        for (int i = 0; i < 2; i++) {
            ItemStack it = mi.getItem(i);
            if (it == null || it.getType().isAir()) continue;
            mi.setItem(i, null);
            for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
        }
        svc.execute(p, offerId).thenCompose(v -> svc.offer(offerId)).whenComplete((o, t) -> com.economycore.core.Tasks.sync(() -> {
            BUSY.remove(p.getUniqueId());
            if (t != null) Msg.error(p, t);
            else Msg.send(p, "trade-done", o == null ? "" : svc.describeResult(o));
            if (p.isOnline()) menus.open(p, s.trader(), s.back());
        }));
    }

    @EventHandler
    public void onMerchantClose(InventoryCloseEvent e) {
        if (e.getInventory() instanceof MerchantInventory mi && !(mi.getMerchant() instanceof Entity)) SESSIONS.remove(e.getPlayer().getUniqueId());
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
