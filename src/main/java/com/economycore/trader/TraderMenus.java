package com.economycore.trader;

import com.economycore.core.EcoException;
import com.economycore.core.EconomyCore;
import com.economycore.core.Inv;
import com.economycore.core.Money;
import com.economycore.core.Msg;
import com.economycore.core.Tasks;
import com.economycore.gui.Btn;
import com.economycore.gui.ConfirmMenu;
import com.economycore.gui.EditMenu;
import com.economycore.gui.Form;
import com.economycore.gui.Items;
import com.economycore.gui.PagedMenu;
import com.economycore.gui.Prompt;
import com.economycore.gui.Setters;
import com.economycore.trader.TraderService.Offer;
import com.economycore.trader.TraderService.Trader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class TraderMenus {
    private final EconomyCore pl;
    private final TraderService svc;

    public TraderMenus(EconomyCore pl, TraderService svc) {
        this.pl = pl;
        this.svc = svc;
    }

    private static String id(String s) { return s.toLowerCase().replaceAll("[^a-z0-9_\\-]", ""); }

    public boolean canUse(Player p, Trader t) {
        if (p.hasPermission("economycore.trader.admin")) return true;
        if (!t.enabled()) return false;
        return t.permission() == null || t.permission().isEmpty() || p.hasPermission(t.permission());
    }

    // ---------- list (hub entry) ----------
    public void list(Player p, Runnable back) {
        boolean admin = p.hasPermission("economycore.trader.admin");
        PagedMenu<Trader> m = new PagedMenu<>(pl, p, "&6Comerciantes", svc::traders, t ->
                Items.of(MarketIcons.icon(t.icon(), Material.VILLAGER_SPAWN_EGG), "&e" + t.name(), "&7ID: &f" + t.id(), t.enabled() ? "&aActivo" : "&cInactivo",
                        admin ? "&aClic izq: abrir  &cClic der: administrar" : "&eClic para abrir"), (t, ci) -> {
            if (admin && ci.right()) { edit(p, t.id(), () -> list(p, back)); return; }
            if (!canUse(p, t)) { Msg.send(p, "no-permission"); return; }
            open(p, t.id(), () -> list(p, back));
        }).searchable(Trader::name);
        if (admin) m.extras(() -> List.of(Btn.of(47, Items.of(Material.EMERALD, "&aNuevo comerciante", "&7Se enlaza a NPCs por identificador"), () ->
                Form.of(p).ask("id", "ID del comerciante (sin espacios)").ask("name", "Nombre visible").onCancel(() -> list(p, back)).then(a ->
                        svc.create(p.getName(), id(a.get("id")), a.get("name")).whenComplete((r, t) -> Tasks.sync(() -> {
                            if (t != null) { Msg.error(p, t); list(p, back); } else edit(p, id(a.get("id")), () -> list(p, back));
                        }))))));
        m.back(back).load();
    }

    // ---------- trading ----------
    public void open(Player p, String traderId, Runnable back) {
        svc.get(traderId).thenAccept(t -> Tasks.sync(() -> {
            if (t == null) { Msg.send(p, "trader-not-found"); return; }
            if (!canUse(p, t)) { Msg.send(p, "no-permission"); return; }
            Runnable self = () -> open(p, traderId, back);
            Map<Long, Integer>[] uses = new Map[1];
            new PagedMenu<Offer>(pl, p, "&6" + t.name(), () -> svc.uses(p.getUniqueId(), traderId).thenCompose(u -> { uses[0] = u; return svc.offers(traderId); }), o -> offerIcon(o, uses[0]),
                    (o, ci) -> svc.execute(p, o.id()).whenComplete((r, e) -> Tasks.sync(() -> {
                        if (e != null) Msg.error(p, e); else Msg.send(p, "trade-done", svc.describeResult(o));
                        self.run();
                    }))).back(back).load();
        }));
    }

    private ItemStack offerIcon(Offer o, Map<Long, Integer> uses) {
        ItemStack base = o.resItem() != null ? Inv.decode(o.resItem()) : null;
        List<String> lore = new ArrayList<>();
        lore.add("&7Cuesta: &f" + svc.describeCost(o));
        lore.add("&7Recibes: &f" + svc.describeResult(o));
        int used = uses == null ? 0 : uses.getOrDefault(o.id(), 0);
        if (o.maxUses() > 0) lore.add("&7Usos: &f" + used + "/" + o.maxUses());
        if (o.globalStock() >= 0) lore.add("&7Stock global: &f" + o.globalStock());
        lore.add(o.enabled() ? "&eClic para intercambiar" : "&cDesactivada");
        if (base != null) {
            base.setAmount(Math.max(1, Math.min(o.resItemAmt(), base.getMaxStackSize())));
            return Items.withLore(base, lore);
        }
        return Items.of(Material.GOLD_INGOT, "&6Oferta #" + o.id(), lore);
    }

    // ---------- admin ----------
    public void edit(Player p, String id, Runnable back) {
        svc.get(id).thenAccept(t -> Tasks.sync(() -> {
            if (t == null) { back.run(); return; }
            Runnable reload = () -> edit(p, id, back);
            EditMenu e = new EditMenu(pl, p, "&cComerciante: " + t.name(), reload);
            e.field(Material.NAME_TAG, "Nombre", t.name(), "Nuevo nombre", Setters.text("traders", "id", id, "name", false));
            e.field(Material.TRIPWIRE_HOOK, "Permiso", t.permission() == null ? "-" : t.permission(), "Permiso requerido (o -)", Setters.text("traders", "id", id, "permission", true));
            e.field(Material.ITEM_FRAME, "Icono", t.icon(), "Material del icono", s -> {
                Material m = Material.matchMaterial(s.trim());
                return m == null ? CompletableFuture.failedFuture(new EcoException("invalid-material")) : EditMenu.update("UPDATE traders SET icon=? WHERE id=?", m.name(), id);
            });
            e.toggle(Material.LIME_DYE, "Activo", t.enabled(), () -> Setters.flip("traders", "id", id, "enabled"));
            e.action(Material.BOOK, "&eOfertas", List.of("&7Crear y editar ofertas"), c -> offers(p, id, reload));
            e.action(Material.LEAD, "&bEnlaces a NPC", List.of("&7Identificadores de NPCs de otros plugins"), c -> bindings(p, id, reload));
            e.action(Material.TNT, "&cEliminar comerciante", List.of("&7Borra ofertas y enlaces"), c ->
                    ConfirmMenu.ask(pl, p, "&cEliminar " + t.name(), List.of("&7Se borra todo"), () ->
                            svc.delete(p.getName(), id).whenComplete((r, x) -> Tasks.sync(back)), reload));
            e.back(back).open();
        }));
    }

    public void offers(Player p, String trader, Runnable back) {
        new PagedMenu<Offer>(pl, p, "&cOfertas", () -> svc.offers(trader), o -> offerIcon(o, null), (o, ci) -> editOffer(p, o.id(), () -> offers(p, trader, back)))
                .extras(() -> List.of(Btn.of(47, Items.of(Material.EMERALD, "&aNueva oferta"), () ->
                        svc.newOffer(trader).whenComplete((r, t) -> Tasks.sync(() -> editOffer(p, r, () -> offers(p, trader, back)))))))
                .back(back).load();
    }

    private void editOffer(Player p, long id, Runnable back) {
        svc.offer(id).thenAccept(o -> Tasks.sync(() -> {
            if (o == null) { back.run(); return; }
            Runnable reload = () -> editOffer(p, id, back);
            EditMenu e = new EditMenu(pl, p, "&cOferta #" + id, reload);
            String T = "trader_offers";
            e.field(Material.EMERALD, "Costo en dinero", o.costMoney() == null ? "-" : o.costMoney().toPlainString(), "Dinero que paga el jugador (o -)", Setters.money(T, "id", id, "cost_money", true));
            e.field(Material.SUNFLOWER, "Moneda del costo", o.costCur(), "ID de moneda", s -> pl.currencies().exists(s.trim()) ? EditMenu.update("UPDATE trader_offers SET cost_currency=? WHERE id=?", pl.currencies().get(s.trim()).id(), id) : CompletableFuture.failedFuture(new EcoException("unknown-currency", s)));
            e.field(Material.CHEST, "Cantidad de objeto de costo", String.valueOf(o.costItemAmt()), "Cantidad (0 = sin objeto)", Setters.integer(T, "id", id, "cost_item_amt", 0, 100000));
            e.field(Material.GOLD_INGOT, "Pago en dinero", o.resMoney() == null ? "-" : o.resMoney().toPlainString(), "Dinero que recibe el jugador (o -)", Setters.money(T, "id", id, "res_money", true));
            e.field(Material.SUNFLOWER, "Moneda del pago", o.resCur(), "ID de moneda", s -> pl.currencies().exists(s.trim()) ? EditMenu.update("UPDATE trader_offers SET res_currency=? WHERE id=?", pl.currencies().get(s.trim()).id(), id) : CompletableFuture.failedFuture(new EcoException("unknown-currency", s)));
            e.field(Material.CHEST, "Cantidad de objeto recibido", String.valueOf(o.resItemAmt()), "Cantidad (0 = sin objeto)", Setters.integer(T, "id", id, "res_item_amt", 0, 100000));
            e.field(Material.CLOCK, "Usos por jugador", String.valueOf(o.maxUses()), "0 = ilimitado", Setters.integer(T, "id", id, "max_uses", 0, 1000000));
            e.field(Material.BARREL, "Stock global", String.valueOf(o.globalStock()), "-1 = ilimitado", Setters.integer(T, "id", id, "global_stock", -1, 100000000));
            e.toggle(Material.LIME_DYE, "Activa", o.enabled(), () -> Setters.flip(T, "id", id, "enabled"));
            e.action(Material.HOPPER, "&aObjeto de costo = objeto en mano", List.of("&7Costo: " + (o.costItem() == null ? "ninguno" : "definido")), c -> {
                ItemStack h = Inv.hand(p);
                if (h == null) { Msg.send(p, "hold-item"); return; }
                EditMenu.update("UPDATE trader_offers SET cost_item=?, cost_item_amt=? WHERE id=?", Inv.encode(h), h.getAmount(), id).whenComplete((r, t) -> Tasks.sync(reload));
            });
            e.action(Material.BARRIER, "&cQuitar objeto de costo", List.of(), c -> EditMenu.update("UPDATE trader_offers SET cost_item=NULL, cost_item_amt=0 WHERE id=?", id).whenComplete((r, t) -> Tasks.sync(reload)));
            e.action(Material.DISPENSER, "&aObjeto recibido = objeto en mano", List.of("&7Resultado: " + (o.resItem() == null ? "ninguno" : "definido")), c -> {
                ItemStack h = Inv.hand(p);
                if (h == null) { Msg.send(p, "hold-item"); return; }
                EditMenu.update("UPDATE trader_offers SET res_item=?, res_item_amt=? WHERE id=?", Inv.encode(h), h.getAmount(), id).whenComplete((r, t) -> Tasks.sync(reload));
            });
            e.action(Material.BARRIER, "&cQuitar objeto recibido", List.of(), c -> EditMenu.update("UPDATE trader_offers SET res_item=NULL, res_item_amt=0 WHERE id=?", id).whenComplete((r, t) -> Tasks.sync(reload)));
            e.action(Material.TNT, "&cEliminar oferta", List.of(), c -> ConfirmMenu.ask(pl, p, "&cEliminar oferta", List.of("&7#" + id), () ->
                    EditMenu.update("DELETE FROM trader_offers WHERE id=?", id).whenComplete((r, t) -> Tasks.sync(back)), reload));
            e.back(back).open();
        }));
    }

    public void bindings(Player p, String trader, Runnable back) {
        Runnable self = () -> bindings(p, trader, back);
        new PagedMenu<String>(pl, p, "&bEnlaces NPC", () -> CompletableFuture.completedFuture(keysOf(trader)),
                k -> Items.of(Material.LEAD, "&e" + k, "&cClic para quitar"), (k, ci) -> svc.unbind(k).whenComplete((r, t) -> Tasks.sync(self)))
                .extras(() -> List.of(
                        Btn.of(47, Items.of(Material.VILLAGER_SPAWN_EGG, "&aEnlazar NPC (clic derecho)", "&7Pulsa y luego haz clic derecho al NPC"), () -> {
                            TraderModule.capture(p.getUniqueId(), trader);
                            p.closeInventory();
                            Msg.send(p, "trader-capture");
                        }),
                        Btn.of(51, Items.of(Material.NAME_TAG, "&aEnlace manual", "&7Formatos: name:Pepe  tag:mercader  uuid:...  citizens:5"), () ->
                                Prompt.ask(p, "Identificador (name:..., tag:..., uuid:..., citizens:ID)", s ->
                                        svc.bind(p.getName(), s.trim().toLowerCase().startsWith("name:") ? "name:" + s.trim().substring(5).toLowerCase() : s.trim(), trader).whenComplete((r, t) -> Tasks.sync(self)), self))))
                .back(back).load();
    }

    private List<String> keysOf(String trader) {
        List<String> l = new ArrayList<>();
        for (Map.Entry<String, String> e : svc.bindings.entrySet()) if (e.getValue().equals(trader)) l.add(e.getKey());
        return l;
    }
}
