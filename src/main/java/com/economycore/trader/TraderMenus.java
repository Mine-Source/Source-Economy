package com.economycore.trader;

import com.economycore.core.EcoException;
import com.economycore.core.Fmt;
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
import com.economycore.gui.Theme;
import com.economycore.trader.TraderService.Offer;
import com.economycore.trader.TraderService.Trader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.MerchantRecipe;

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
    public void open(Player p, String traderId, Runnable back) { open(p, traderId, back, null); }

    /** forceUi: null = trader setting; VILLAGER or MENU to override. */
    public void open(Player p, String traderId, Runnable back, String forceUi) {
        svc.get(traderId).thenAccept(t -> Tasks.sync(() -> {
            if (t == null) { Msg.send(p, "trader-not-found"); return; }
            if (!canUse(p, t)) { Msg.send(p, "no-permission"); return; }
            svc.uses(p.getUniqueId(), traderId).thenCompose(uses -> svc.offers(traderId).thenApply(list -> new Object[]{uses, list})).whenComplete((box, err) -> Tasks.sync(() -> {
                if (err != null) { Msg.error(p, err); return; }
                @SuppressWarnings("unchecked") Map<Long, Integer> uses = (Map<Long, Integer>) box[0];
                @SuppressWarnings("unchecked") List<Offer> offers = (List<Offer>) box[1];
                boolean admin = p.hasPermission("economycore.trader.admin");
                List<Offer> shown = new ArrayList<>();
                for (Offer o : offers) {
                    if (!o.enabled() && !admin) continue;
                    if (o.permission() != null && !o.permission().isBlank() && !p.hasPermission(o.permission()) && !admin) continue;
                    shown.add(o);
                }
                String ui = forceUi != null ? forceUi : t.ui();
                boolean allNative = !shown.isEmpty() && shown.stream().allMatch(Offer::nativeOk);
                if (ui.equals("VILLAGER") || (ui.equals("AUTO") && allNative)) {
                    if (openVillager(p, t, shown, uses, back)) return;
                }
                openGui(p, t, back);
            }));
        }));
    }

    /** Native villager window. Returns false when nothing can be shown that way. */
    private boolean openVillager(Player p, Trader t, List<Offer> offers, Map<Long, Integer> uses, Runnable back) {
        List<MerchantRecipe> recipes = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        for (Offer o : offers) {
            if (!o.enabled() || !o.nativeOk()) continue;
            ItemStack res = Inv.decode(o.resItem());
            ItemStack c1 = Inv.decode(o.costItem());
            if (res == null || c1 == null) continue;
            if (o.resItemAmt() > res.getMaxStackSize() || o.costItemAmt() > c1.getMaxStackSize()) continue;
            res.setAmount(o.resItemAmt());
            c1.setAmount(o.costItemAmt());
            ItemStack c2 = null;
            if (o.costItem2() != null && o.costItem2Amt() > 0) {
                c2 = Inv.decode(o.costItem2());
                if (c2 == null || o.costItem2Amt() > c2.getMaxStackSize()) continue;
                c2.setAmount(o.costItem2Amt());
            }
            int used = uses.getOrDefault(o.id(), 0);
            int max = o.maxUses() > 0 ? o.maxUses() : 1_000_000;
            boolean exhausted = o.globalStock() == 0 || (o.maxUses() > 0 && used >= o.maxUses());
            MerchantRecipe r = new MerchantRecipe(res, exhausted ? max : Math.min(used, max), max, false);
            r.setIgnoreDiscounts(true);
            r.addIngredient(c1);
            if (c2 != null) r.addIngredient(c2);
            recipes.add(r);
            ids.add(o.id());
        }
        if (recipes.isEmpty()) return false;
        Merchant m = Bukkit.createMerchant(Theme.paintTitle("&6" + t.name()));
        m.setRecipes(recipes);
        p.openMerchant(m, true);
        TraderModule.startSession(p.getUniqueId(), t.id(), ids, back);
        return true;
    }

    /** Styled menu: used for offers that involve money or commands, or when the trader is set to MENU. */
    private void openGui(Player p, Trader t, Runnable back) {
        String traderId = t.id();
        boolean admin = p.hasPermission("economycore.trader.admin");
        Runnable self = () -> open(p, traderId, back, "MENU");
        Object[][] box = new Object[1][];
        new PagedMenu<Offer>(pl, p, "&6" + t.name(), () -> svc.uses(p.getUniqueId(), traderId).thenCompose(u -> svc.lastUses(p.getUniqueId(), traderId).thenCompose(l ->
                svc.offers(traderId).thenApply(list -> {
                    box[0] = new Object[]{u, l};
                    List<Offer> out = new ArrayList<>();
                    for (Offer o : list) {
                        if (!o.enabled() && !admin) continue;
                        if (o.permission() != null && !o.permission().isBlank() && !p.hasPermission(o.permission()) && !admin) continue;
                        out.add(o);
                    }
                    return out;
                }))),
                o -> {
                    @SuppressWarnings("unchecked") Map<Long, Integer> u = box[0] == null ? null : (Map<Long, Integer>) box[0][0];
                    @SuppressWarnings("unchecked") Map<Long, Long> l = box[0] == null ? null : (Map<Long, Long>) box[0][1];
                    return offerIcon(p, o, u, l);
                },
                (o, ci) -> svc.execute(p, o.id()).whenComplete((r, e) -> Tasks.sync(() -> {
                    if (e != null) Msg.error(p, e); else Msg.send(p, "trade-done", svc.describeResult(o));
                    self.run();
                }))).back(back).load();
    }

    private ItemStack offerIcon(Player p, Offer o, Map<Long, Integer> uses, Map<Long, Long> last) {
        ItemStack base = o.resItem() != null && o.resItemAmt() > 0 ? Inv.decode(o.resItem()) : null;
        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add("&7Cuesta:");
        boolean can = true;
        if (o.costItem() != null && o.costItemAmt() > 0) {
            ItemStack c = Inv.decode(o.costItem());
            int have = c == null ? 0 : Inv.count(p, c);
            can &= have >= o.costItemAmt();
            lore.add((have >= o.costItemAmt() ? " &a✔ &f" : " &c✖ &f") + o.costItemAmt() + "x " + (c == null ? "?" : Inv.name(c)) + " &8(tienes " + have + ")");
        }
        if (o.costItem2() != null && o.costItem2Amt() > 0) {
            ItemStack c = Inv.decode(o.costItem2());
            int have = c == null ? 0 : Inv.count(p, c);
            can &= have >= o.costItem2Amt();
            lore.add((have >= o.costItem2Amt() ? " &a✔ &f" : " &c✖ &f") + o.costItem2Amt() + "x " + (c == null ? "?" : Inv.name(c)) + " &8(tienes " + have + ")");
        }
        if (o.costMoney() != null && o.costMoney().signum() > 0) {
            boolean ok = com.economycore.core.Ledger.cached(p.getUniqueId(), o.costCur()).compareTo(o.costMoney()) >= 0;
            can &= ok;
            lore.add((ok ? " &a✔ " : " &c✖ ") + Money.fmt(o.costCur(), o.costMoney()));
        }
        if (lore.size() == 2) lore.add(" &aGratis");
        lore.add("");
        lore.add("&7Recibes: &f" + svc.describeResult(o));
        int used = uses == null ? 0 : uses.getOrDefault(o.id(), 0);
        if (o.maxUses() > 0) lore.add("&7Usos: &f" + used + "&8/&7" + o.maxUses());
        if (o.globalStock() >= 0) lore.add("&7Stock global: &f" + o.globalStock());
        long wait = 0;
        if (o.cooldownSec() > 0 && last != null && last.getOrDefault(o.id(), 0L) > 0) wait = o.cooldownSec() * 1000L - (System.currentTimeMillis() - last.get(o.id()));
        if (wait > 0) lore.add("&7Disponible en: &e" + Fmt.duration(wait));
        lore.add("");
        boolean exhausted = (o.maxUses() > 0 && used >= o.maxUses()) || o.globalStock() == 0;
        if (!o.enabled()) lore.add("&c✖ Desactivada");
        else if (exhausted) lore.add("&c✖ Agotada");
        else if (wait > 0) lore.add("&c✖ En espera");
        else if (!can) lore.add("&c✖ No cumples el costo");
        else lore.add("&e▸ Clic para intercambiar");
        if (base != null) {
            base.setAmount(Math.max(1, Math.min(o.resItemAmt(), base.getMaxStackSize())));
            return Items.withLore(base, lore);
        }
        return Items.of(o.resMoney() != null && o.resMoney().signum() > 0 ? Material.GOLD_INGOT : Material.NETHER_STAR, "&6" + svc.describeResult(o), lore);
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
            e.action(Material.BOOK, "&eOfertas", List.of("&7Crear y editar ofertas con el editor visual"), c -> offers(p, id, reload));
            String[] uiLabels = {"Automático (aldeano si es posible)", "Ventana de aldeano (solo objetos)", "Menú con dinero y comandos"};
            int ui = t.ui().equals("VILLAGER") ? 1 : t.ui().equals("MENU") ? 2 : 0;
            List<String> uiLore = new ArrayList<>(List.of("&8Cómo ven los jugadores este comerciante", ""));
            uiLore.addAll(Items.options(uiLabels, ui));
            uiLore.add("");
            uiLore.add("&e▸ Clic para cambiar");
            e.action(Material.VILLAGER_SPAWN_EGG, "&eInterfaz", uiLore, c ->
                    EditMenu.update("UPDATE traders SET ui=? WHERE id=?", new String[]{"VILLAGER", "MENU", "AUTO"}[ui], id).whenComplete((r, x) -> Tasks.sync(reload)));
            e.action(Material.SPYGLASS, "&bProbar como jugador", List.of("&7Abre este comerciante como lo vería un jugador."), c -> open(p, id, reload));
            e.action(Material.LEAD, "&bEnlaces a NPC", List.of("&7Identificadores de NPCs de otros plugins"), c -> bindings(p, id, reload));
            e.action(Material.TNT, "&cEliminar comerciante", List.of("&7Borra ofertas y enlaces"), c ->
                    ConfirmMenu.ask(pl, p, "&cEliminar " + t.name(), List.of("&7Se borra todo"), () ->
                            svc.delete(p.getName(), id).whenComplete((r, x) -> Tasks.sync(back)), reload));
            e.back(back).open();
        }));
    }

    public void offers(Player p, String trader, Runnable back) {
        Runnable self = () -> offers(p, trader, back);
        new PagedMenu<Offer>(pl, p, "&cOfertas", () -> svc.offers(trader), o -> {
            ItemStack base = o.resItem() != null && o.resItemAmt() > 0 ? Inv.decode(o.resItem()) : null;
            List<String> lore = new ArrayList<>(List.of("", "&7Cuesta: &f" + svc.describeCost(o), "&7Recibe: &f" + svc.describeResult(o), "&7Vista: " + (o.nativeOk() ? "&aaldeano" : "&emenú"),
                    o.enabled() ? "&aActiva" : "&cDesactivada", "", "&e▸ Clic izq: editar", "&e▸ Clic der: duplicar", "&e▸ Shift izq / der: subir / bajar"));
            if (base != null) { base.setAmount(Math.max(1, Math.min(o.resItemAmt(), base.getMaxStackSize()))); return Items.withLore(base, lore); }
            return Items.of(Material.GOLD_INGOT, "&6Oferta #" + o.id(), lore);
        }, (o, ci) -> {
            if (ci.shift()) { svc.move(trader, o.id(), ci.right() ? 1 : -1).whenComplete((r, t) -> Tasks.sync(self)); return; }
            if (ci.right()) { svc.duplicate(o.id()).whenComplete((r, t) -> Tasks.sync(self)); return; }
            editOffer(p, o.id(), self);
        }).extras(() -> List.of(Btn.of(47, Items.of(Material.EMERALD, "&aNueva oferta", "&7Se abre el editor visual", "", "&e▸ Clic para crear"), () ->
                svc.newOffer(trader).whenComplete((r, t) -> Tasks.sync(() -> editOffer(p, r, self))))))
                .back(back).load();
    }

    private void editOffer(Player p, long id, Runnable back) {
        svc.offer(id).thenAccept(o -> Tasks.sync(() -> {
            if (o == null) { back.run(); return; }
            new OfferEditor(pl, p, svc, o, back, () -> editOffer(p, id, back)).open();
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
