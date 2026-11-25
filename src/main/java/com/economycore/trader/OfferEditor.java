package com.economycore.trader;

import com.economycore.core.EconomyCore;
import com.economycore.core.Fmt;
import com.economycore.core.Inv;
import com.economycore.core.Money;
import com.economycore.core.Msg;
import com.economycore.core.Tasks;
import com.economycore.gui.ClickInfo;
import com.economycore.gui.ConfirmMenu;
import com.economycore.gui.EditMenu;
import com.economycore.gui.Items;
import com.economycore.gui.Menu;
import com.economycore.gui.Prompt;
import com.economycore.gui.Setters;
import com.economycore.trader.TraderService.Offer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Visual offer editor. Layout mirrors the villager window: cost 1 + cost 2 -> result.
 * Pick an item up with the cursor and click a slot to set it; click an already-set slot to change its amount.
 */
final class OfferEditor extends Menu {
    private static final String T = "trader_offers";
    private static final int MAX_AMOUNT = 2304;

    private final TraderService svc;
    private final Offer o;
    private final Runnable back;
    private final Runnable reload;

    OfferEditor(EconomyCore pl, Player p, TraderService svc, Offer o, Runnable back, Runnable reload) {
        super(pl, p, "&cEditor de oferta &8➜ &c#" + o.id(), 6);
        this.svc = svc;
        this.o = o;
        this.back = back;
        this.reload = reload;
        this.parent = back;
    }

    private String cur(String c) { return c == null ? plugin.currencies().defId() : c; }

    private ItemStack itemSlot(String data, int amt, String label, String hint) {
        ItemStack base = data == null ? null : Inv.decode(data);
        if (base == null || amt <= 0) {
            return Items.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&7" + label + " &8(vacío)", "&8" + hint, "",
                    "&e▸ Toma un objeto de tu inventario", "&e  con el cursor y haz clic aquí");
        }
        base.setAmount(Math.max(1, Math.min(amt, base.getMaxStackSize())));
        return Items.withLore(base, "", "&8" + label, "&7Cantidad: &f" + amt, "",
                "&e▸ Clic con otro objeto: reemplazar", "&e▸ Clic izq/der: +1 / -1 &8(shift: 16)", "&c▸ Shift + clic der: quitar");
    }

    private void save(String sql, Object... params) {
        EditMenu.update(sql, params).whenComplete((r, t) -> Tasks.sync(() -> {
            if (t != null) Msg.error(viewer, t);
            reload.run();
        }));
    }

    private void itemClick(ClickInfo ci, String itemCol, String amtCol, String data, int amt) {
        if (ci.hasCursor()) {
            ItemStack t = ci.cursor().clone();
            int a = t.getAmount();
            t.setAmount(1);
            save("UPDATE " + T + " SET " + itemCol + "=?, " + amtCol + "=? WHERE id=?", Inv.encode(t), a, o.id());
            return;
        }
        if (data == null || amt <= 0) {
            viewer.sendMessage(Msg.color("&7Toma un objeto de tu inventario con el cursor y haz clic en esta ranura."));
            return;
        }
        if (ci.right() && ci.shift()) {
            save("UPDATE " + T + " SET " + itemCol + "=NULL, " + amtCol + "=0 WHERE id=?", o.id());
            return;
        }
        int delta = ci.left() ? (ci.shift() ? 16 : 1) : (ci.right() ? -1 : 0);
        if (delta == 0) return;
        int n = Math.min(MAX_AMOUNT, amt + delta);
        if (n <= 0) save("UPDATE " + T + " SET " + itemCol + "=NULL, " + amtCol + "=0 WHERE id=?", o.id());
        else save("UPDATE " + T + " SET " + amtCol + "=? WHERE id=?", n, o.id());
    }

    private void prompt(String question, Function<String, CompletableFuture<?>> setter) {
        Prompt.ask(viewer, question, s -> setter.apply(s).whenComplete((r, t) -> Tasks.sync(() -> {
            if (t != null) Msg.error(viewer, t);
            reload.run();
        })), reload);
    }

    @Override
    protected void render() {
        set(10, itemSlot(o.costItem(), o.costItemAmt(), "Costo 1", "Lo primero que paga el jugador"), c -> itemClick(c, "cost_item", "cost_item_amt", o.costItem(), o.costItemAmt()));
        set(11, itemSlot(o.costItem2(), o.costItem2Amt(), "Costo 2", "Segundo objeto opcional"), c -> itemClick(c, "cost_item2", "cost_item2_amt", o.costItem2(), o.costItem2Amt()));
        set(13, Items.of(Material.ARROW, "&f➜", "&7El jugador entrega los costos", "&7y recibe el resultado."));
        set(15, itemSlot(o.resItem(), o.resItemAmt(), "Resultado", "Lo que recibe el jugador"), c -> itemClick(c, "res_item", "res_item_amt", o.resItem(), o.resItemAmt()));
        set(4, Items.of(Material.BOOK, "&6Oferta #" + o.id(), "&7Vista previa: " + (o.nativeOk() ? "&aventana de aldeano" : "&emenú con dinero/comandos"), "",
                "&8Se muestra como aldeano si solo hay", "&8objetos de costo y de resultado."));

        String money = o.costMoney() == null || o.costMoney().signum() <= 0 ? "ninguno" : Money.plain(cur(o.costCur()), o.costMoney());
        set(19, Items.of(Material.GOLD_NUGGET, "&6Costo en dinero", "&7Actual: &f" + money, "", "&e▸ Clic para editar &8(- para quitar)"),
                c -> prompt("Dinero que paga el jugador (o -)", Setters.money(T, "id", o.id(), "cost_money", true)));
        String pay = o.resMoney() == null || o.resMoney().signum() <= 0 ? "ninguno" : Money.plain(cur(o.resCur()), o.resMoney());
        set(25, Items.of(Material.EMERALD, "&aPago en dinero", "&7Actual: &f" + pay, "", "&e▸ Clic para editar &8(- para quitar)"),
                c -> prompt("Dinero que recibe el jugador (o -)", Setters.money(T, "id", o.id(), "res_money", true)));
        set(22, Items.of(Material.SUNFLOWER, "&eMoneda", "&7Actual: &f" + cur(o.costCur()), "", "&e▸ Clic para cambiar"), c -> {
            List<String> ids = new ArrayList<>();
            for (var cu : plugin.currencies().all()) ids.add(cu.id());
            if (ids.isEmpty()) return;
            int i = Math.max(0, ids.indexOf(cur(o.costCur())));
            String next = ids.get((i + 1) % ids.size());
            save("UPDATE " + T + " SET cost_currency=?, res_currency=? WHERE id=?", next, next, o.id());
        });

        set(28, Items.of(Material.CLOCK, "&6Usos por jugador", "&7Actual: &f" + (o.maxUses() <= 0 ? "ilimitado" : String.valueOf(o.maxUses())), "", "&e▸ Clic para editar &80 = ilimitado"),
                c -> prompt("Usos por jugador (0 = ilimitado)", Setters.integer(T, "id", o.id(), "max_uses", 0, 1_000_000)));
        set(29, Items.of(Material.BARREL, "&6Stock global", "&7Actual: &f" + (o.globalStock() < 0 ? "ilimitado" : String.valueOf(o.globalStock())), "", "&e▸ Clic para editar &8-1 = ilimitado"),
                c -> prompt("Stock global (-1 = ilimitado)", Setters.integer(T, "id", o.id(), "global_stock", -1, 100_000_000)));
        set(30, Items.of(Material.COMPASS, "&6Espera entre usos", "&7Actual: &f" + (o.cooldownSec() <= 0 ? "sin espera" : Fmt.duration(o.cooldownSec() * 1000L)), "", "&e▸ Clic para editar &8(segundos)"),
                c -> prompt("Segundos de espera entre usos (0 = ninguna)", Setters.integer(T, "id", o.id(), "cooldown_sec", 0, 31_536_000)));
        set(32, Items.of(Material.TRIPWIRE_HOOK, "&6Permiso", "&7Actual: &f" + (o.permission() == null || o.permission().isBlank() ? "ninguno" : o.permission()), "", "&e▸ Clic para editar &8(- para quitar)"),
                c -> prompt("Permiso requerido (o -)", Setters.text(T, "id", o.id(), "permission", true)));
        set(33, Items.of(Material.COMMAND_BLOCK, "&6Comandos de recompensa", "&7Actual: &f" + (o.command() == null || o.command().isBlank() ? "ninguno" : o.command()), "",
                "&8Se ejecutan desde consola. Usa %player%.", "&8Separa varios con ||", "", "&e▸ Clic para editar &8(- para quitar)"),
                c -> prompt("Comando(s), ej: give %player% diamond 1 || say hola (o -)", Setters.text(T, "id", o.id(), "command", true)));
        set(34, Items.of(o.enabled() ? Material.LIME_DYE : Material.GRAY_DYE, "&6Oferta " + (o.enabled() ? "&aactiva" : "&cdesactivada"), "", "&e▸ Clic para alternar"),
                c -> Setters.flip(T, "id", o.id(), "enabled").whenComplete((r, t) -> Tasks.sync(reload)));

        set(48, Items.of(Material.ARROW, "&a◀ Volver", "&7A la lista de ofertas", "", "&e▸ Clic para volver"), c -> back.run());
        closeButton(49);
        set(53, Items.of(Material.TNT, "&cEliminar oferta", "&7No se puede deshacer.", "", "&c▸ Clic para eliminar"), c ->
                ConfirmMenu.ask(plugin, viewer, "&cEliminar oferta #" + o.id(), List.of("&7Se borra con sus usos registrados"), () ->
                        EditMenu.update("DELETE FROM trader_uses WHERE offer_id=?", o.id()).thenCompose(v -> EditMenu.update("DELETE FROM trader_offers WHERE id=?", o.id()))
                                .whenComplete((r, t) -> Tasks.sync(back)), reload));
        fillEmpty(Items.pane("GRAY"));
    }
}
