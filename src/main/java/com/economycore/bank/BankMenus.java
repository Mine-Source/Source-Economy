package com.economycore.bank;

import com.economycore.bank.BankService.Account;
import com.economycore.core.Currency;
import com.economycore.core.EconomyCore;
import com.economycore.core.Fmt;
import com.economycore.core.Ledger;
import com.economycore.core.Money;
import com.economycore.core.Msg;
import com.economycore.core.Tasks;
import com.economycore.gui.Btn;
import com.economycore.gui.ButtonMenu;
import com.economycore.gui.ConfirmMenu;
import com.economycore.gui.Form;
import com.economycore.gui.Items;
import com.economycore.gui.PagedMenu;
import com.economycore.gui.Prompt;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;

public final class BankMenus {
    private final EconomyCore pl;
    private final BankService svc;

    public BankMenus(EconomyCore pl, BankService svc) {
        this.pl = pl;
        this.svc = svc;
    }

    private static BigDecimal amount(String s, BigDecimal all) {
        s = s.trim().toLowerCase();
        if (s.equals("all") || s.equals("todo")) return all;
        return Money.parse(s);
    }

    private org.bukkit.inventory.ItemStack accountIcon(Account a) {
        return Items.of(a.type().equals("SHARED") ? Material.ENDER_CHEST : Material.CHEST, "&e" + a.name() + " &8(" + a.id() + ")",
                "&7Tipo: &f" + (a.type().equals("SHARED") ? "Compartida" : "Personal"), "&7Saldo: " + Money.fmt(a.currency(), a.balance()), "&7Tu rol: &f" + a.role(), "&eClic para abrir");
    }

    public void main(Player p, Runnable back) {
        boolean admin = p.hasPermission("economycore.bank.admin");
        Runnable self = () -> main(p, back);
        new PagedMenu<Account>(pl, p, "&6Banco", () -> svc.mine(p.getUniqueId()), this::accountIcon, (a, ci) -> account(p, a.id(), self, false))
                .searchable(a -> a.name() + " " + a.id())
                .extras(() -> {
                    List<Btn> l = new ArrayList<>();
                    boolean can = p.hasPermission("economycore.bank.create");
                    l.add(Btn.of(47, Items.of(Material.CHEST, "&aCuenta personal", "&7Crear una cuenta personal", can ? "" : "&cSin permiso"), () -> create(p, "PERSONAL", self)));
                    l.add(Btn.of(51, Items.of(Material.ENDER_CHEST, "&bCuenta compartida", "&7Con gestores y miembros", can ? "" : "&cSin permiso"), () -> create(p, "SHARED", self)));
                    if (admin) l.add(Btn.of(50, Items.of(Material.COMMAND_BLOCK, "&cTodas las cuentas", "&7Administración"), () -> adminAll(p, self)));
                    return l;
                }).back(back).load();
    }

    private void adminAll(Player p, Runnable back) {
        new PagedMenu<Account>(pl, p, "&cTodas las cuentas", svc::all, this::accountIcon, (a, ci) -> account(p, a.id(), () -> adminAll(p, back), true))
                .searchable(a -> a.name() + " " + a.id() + " " + a.owner()).back(back).load();
    }

    private void create(Player p, String type, Runnable back) {
        if (!p.hasPermission("economycore.bank.create")) { Msg.send(p, "no-permission"); return; }
        Form f = Form.of(p).ask("name", "Nombre de la cuenta").onCancel(back);
        boolean multi = pl.currencies().all().size() > 1;
        if (multi) f.ask("cur", "Moneda (id)");
        f.then(a -> {
            Currency c = multi ? pl.currencies().get(a.get("cur")) : pl.currencies().def();
            if (c == null) { Msg.send(p, "unknown-currency", a.get("cur")); back.run(); return; }
            svc.create(p.getUniqueId(), p.getName(), a.get("name"), type, c.id()).whenComplete((id, t) -> Tasks.sync(() -> {
                if (t != null) { Msg.error(p, t); back.run(); } else { Msg.send(p, "bank-created", id); account(p, id, back, false); }
            }));
        });
    }

    public void account(Player p, String id, Runnable back, boolean admin) {
        svc.get(id, p.getUniqueId(), admin).whenComplete((a, t) -> Tasks.sync(() -> {
            if (t != null) { Msg.error(p, t); back.run(); return; }
            Runnable self = () -> account(p, id, back, admin);
            new ButtonMenu(pl, p, "&6" + a.name() + " &8(" + a.id() + ")", 4, true, () -> {
                List<Btn> l = new ArrayList<>();
                l.add(Btn.of(4, Items.of(Material.GOLD_BLOCK, "&e" + a.name(), "&7Saldo: " + Money.fmt(a.currency(), a.balance()), "&7Tipo: &f" + a.type(), "&7Rol: &f" + a.role(), "&7Intereses: " + (a.interest() ? "&aactivos" : "&cdesactivados")), () -> {}));
                l.add(Btn.of(10, Items.of(Material.LIME_CONCRETE, "&aDepositar", "&7Tu saldo: " + Money.fmt(a.currency(), Ledger.cached(p.getUniqueId(), a.currency())), "&7Escribe 'todo' para todo"), () ->
                        Prompt.ask(p, "Cantidad a depositar", s -> {
                            BigDecimal v = amount(s, Ledger.cached(p.getUniqueId(), a.currency()));
                            if (v == null || v.signum() <= 0) { Msg.send(p, "invalid-amount"); self.run(); return; }
                            svc.deposit(p.getUniqueId(), id, v).whenComplete((r, x) -> Tasks.sync(() -> { if (x != null) Msg.error(p, x); else Msg.send(p, "bank-deposited", Money.fmt(a.currency(), v)); self.run(); }));
                        }, self)));
                if (a.canWithdraw() || admin) {
                    l.add(Btn.of(12, Items.of(Material.ORANGE_CONCRETE, "&6Retirar", "&7Escribe 'todo' para todo"), () ->
                            Prompt.ask(p, "Cantidad a retirar", s -> {
                                BigDecimal v = amount(s, a.balance());
                                if (v == null || v.signum() <= 0) { Msg.send(p, "invalid-amount"); self.run(); return; }
                                svc.withdraw(p.getUniqueId(), id, v).whenComplete((r, x) -> Tasks.sync(() -> { if (x != null) Msg.error(p, x); else Msg.send(p, "bank-withdrawn", Money.fmt(a.currency(), v)); self.run(); }));
                            }, self)));
                    l.add(Btn.of(14, Items.of(Material.PAPER, "&bTransferir", "&7A otra cuenta (B1234) o a un jugador"), () ->
                            Form.of(p).ask("to", "Destino: ID de cuenta (ej B1001) o nombre de jugador").ask("amt", "Cantidad").onCancel(self).then(f -> {
                                BigDecimal v = amount(f.get("amt"), a.balance());
                                if (v == null || v.signum() <= 0) { Msg.send(p, "invalid-amount"); self.run(); return; }
                                svc.transfer(p.getUniqueId(), id, f.get("to"), v).whenComplete((r, x) -> Tasks.sync(() -> { if (x != null) Msg.error(p, x); else Msg.send(p, "bank-transferred", Money.fmt(a.currency(), v), r); self.run(); }));
                            })));
                }
                l.add(Btn.of(16, Items.of(Material.BOOK, "&eMovimientos", "&7Extracto de la cuenta"), () -> statement(p, id, self)));
                if (a.type().equals("SHARED")) l.add(Btn.of(20, Items.of(Material.PLAYER_HEAD, "&dMiembros", "&7Gestores y miembros"), () -> members(p, a, self)));
                if (admin) l.add(Btn.of(22, Items.of(Material.SUNFLOWER, "&cAlternar intereses"), () -> svc.toggleInterest(p.getName(), id).whenComplete((r, x) -> Tasks.sync(self))));
                if (a.role().equals("OWNER") || admin) l.add(Btn.of(26, Items.of(Material.TNT, "&cCerrar cuenta", "&7El saldo vuelve al dueño"), () ->
                        ConfirmMenu.ask(pl, p, "&cCerrar " + a.name(), List.of("&7Saldo: " + Money.plain(a.currency(), a.balance()), "&7Se transfiere al dueño"), () ->
                                svc.close(p.getUniqueId(), id, admin).whenComplete((r, x) -> Tasks.sync(() -> { if (x != null) Msg.error(p, x); back.run(); })), self)));
                return l;
            }).back(back).open();
        }));
    }

    private void statement(Player p, String id, Runnable back) {
        new PagedMenu<BankService.Line>(pl, p, "&6Movimientos " + id, () -> svc.statement(id), ln -> Items.of(ln.incoming() ? Material.LIME_DYE : Material.RED_DYE, "&f" + ln.type(),
                "&7Monto: " + (ln.incoming() ? "&a+" : "&c-") + ln.amount().toPlainString(), "&7" + (ln.note() == null ? "" : ln.note()), "&8" + Fmt.dateFull(ln.ts())), (ln, ci) -> {}).back(back).load();
    }

    private void members(Player p, Account a, Runnable back) {
        Runnable self = () -> members(p, a, back);
        boolean owner = a.role().equals("OWNER");
        PagedMenu<String[]> m = new PagedMenu<>(pl, p, "&dMiembros: " + a.name(), () -> svc.members(a.id()), mem -> Items.of(Material.PLAYER_HEAD, "&e" + mem[1], "&7Rol: &f" + mem[2],
                owner && !mem[2].equals("OWNER") ? "&aClic izq: alternar gestor/miembro" : "", owner && !mem[2].equals("OWNER") ? "&cClic der: expulsar" : ""), (mem, ci) -> {
            if (!owner || mem[2].equals("OWNER")) return;
            svc.setRole(p.getUniqueId(), a.id(), mem[0], ci.right() ? null : (mem[2].equals("ADMIN") ? "MEMBER" : "ADMIN")).whenComplete((r, t) -> Tasks.sync(self));
        });
        if (owner) m.extras(() -> List.of(Btn.of(47, Items.of(Material.EMERALD, "&aAñadir miembro"), () -> Prompt.ask(p, "Nombre del jugador", n ->
                svc.addMember(p.getUniqueId(), a.id(), n).whenComplete((r, t) -> Tasks.sync(() -> { if (t != null) Msg.error(p, t); self.run(); })), self))));
        m.back(back).load();
    }
}
