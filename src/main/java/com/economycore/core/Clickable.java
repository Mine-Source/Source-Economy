package com.economycore.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.entity.Player;

/**
 * Clickable chat buttons. Markup inside a message template: [[label|/command|hover text]] (hover optional).
 * Buttons are parsed from the TEMPLATE before arguments are substituted, so a player-controlled value
 * (an item name, for example) can never create a button. Only EconomyCore commands are accepted.
 */
public final class Clickable {
    private Clickable() {}

    private static final Pattern BTN = Pattern.compile("\\[\\[([^|\\]]+)\\|(/[^|\\]]+)(?:\\|([^\\]]*))?\\]\\]");
    private static final Set<String> ALLOWED = Set.of("eco", "ah", "bazaar", "bz", "trade", "mailbox", "buzon", "pay", "bank", "stocks", "bolsa", "company", "empresa",
            "shop", "sellall", "markets", "market", "pshop", "treasury", "ecostats", "balance", "bal", "baltop", "trader");

    /** Buttons appended automatically to some notifications (so they also work with an old messages.yml). */
    private static final Map<String, String> AUTO = Map.ofEntries(
            Map.entry("trade-request", " [[&aAceptar|/trade accept|Aceptar el intercambio]] [[&cRechazar|/trade deny|Rechazar la solicitud]]"),
            Map.entry("ah-outbid", " [[&ePujar de nuevo|/ah|Abrir la casa de subastas]]"),
            Map.entry("ah-won", " [[&dReclamar|/mailbox|Abrir el buzón]]"),
            Map.entry("ah-expired", " [[&dReclamar|/mailbox|Abrir el buzón]]"),
            Map.entry("ah-sold", " [[&6Casa de subastas|/ah|Abrir la casa de subastas]]"),
            Map.entry("bazaar-fill-buy", " [[&dReclamar|/mailbox|Abrir el buzón]]"),
            Map.entry("bazaar-fill-sell", " [[&bBazaar|/bazaar|Abrir el bazaar]]"),
            Map.entry("stock-fill-buy", " [[&bBolsa|/stocks|Abrir la bolsa]]"),
            Map.entry("stock-fill-sell", " [[&bBolsa|/stocks|Abrir la bolsa]]"),
            Map.entry("mailbox-full", " [[&dAbrir buzón|/mailbox|Abrir el buzón]]"));

    public static String autoSuffix(String key) {
        return EconomyCore.get().getConfig().getBoolean("gui.clickable-messages", true) ? AUTO.get(key) : null;
    }

    private static String sub(String s, Object[] args, boolean command) {
        for (int i = 0; i < args.length; i++) {
            String v = String.valueOf(args[i]);
            if (command) v = v.replaceAll("[^A-Za-z0-9_.\\-]", "");
            s = s.replace("{" + i + "}", v);
        }
        return s;
    }

    public static void send(Player p, String prefix, String template, Object[] args) {
        List<BaseComponent> out = new ArrayList<>();
        for (BaseComponent b : TextComponent.fromLegacyText(Msg.color(prefix))) out.add(b);
        Matcher m = BTN.matcher(template);
        int last = 0;
        while (m.find()) {
            addText(out, template.substring(last, m.start()), args);
            String cmd = sub(m.group(2), args, true).trim();
            String root = cmd.length() > 1 ? cmd.substring(1).split(" ")[0].toLowerCase() : "";
            String label = Msg.color("&8[" + sub(m.group(1), args, false) + "&8]");
            BaseComponent[] btn = TextComponent.fromLegacyText(label);
            for (BaseComponent b : btn) {
                if (ALLOWED.contains(root)) {
                    b.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd));
                    if (m.group(3) != null) b.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(Msg.color(sub(m.group(3), args, false)))));
                }
                out.add(b);
            }
            last = m.end();
        }
        addText(out, template.substring(last), args);
        p.spigot().sendMessage(out.toArray(new BaseComponent[0]));
    }

    private static void addText(List<BaseComponent> out, String raw, Object[] args) {
        if (raw.isEmpty()) return;
        for (BaseComponent b : TextComponent.fromLegacyText(Msg.color(sub(raw, args, false)))) out.add(b);
    }
}
