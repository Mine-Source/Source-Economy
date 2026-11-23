package com.economycore.gui;

import com.economycore.core.EconomyCore;
import com.economycore.core.Msg;
import com.economycore.core.Tasks;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Chat-based text input for GUI buttons. Callbacks always run on the main thread. */
public final class Prompt implements Listener {
    private record Pending(Consumer<String> ok, Runnable cancel) {}

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    public static void ask(Player p, String question, Consumer<String> ok) { ask(p, question, ok, null); }

    public static void ask(Player p, String question, Consumer<String> ok, Runnable onCancel) {
        p.closeInventory();
        PENDING.put(p.getUniqueId(), new Pending(ok, onCancel));
        p.sendMessage(Msg.get("prompt", question));
        p.sendMessage(Msg.get("prompt-cancel"));
    }

    public static boolean waiting(Player p) { return PENDING.containsKey(p.getUniqueId()); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        Pending pe = PENDING.remove(e.getPlayer().getUniqueId());
        if (pe == null) return;
        e.setCancelled(true);
        String msg = e.getMessage().trim();
        Player p = e.getPlayer();
        Tasks.sync(() -> {
            if (msg.equalsIgnoreCase("cancel") || msg.equalsIgnoreCase("cancelar")) {
                Msg.send(p, "prompt-cancelled");
                if (pe.cancel() != null) pe.cancel().run();
            } else {
                pe.ok().accept(msg);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { PENDING.remove(e.getPlayer().getUniqueId()); }
}
