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
        boolean shared = a.type().equals("SHARED");
        return Items.of(shared ? Material.ENDER_CHEST : Material.GOLD_BLOCK, (shared ? "&b" : "&6") + a.name(),
                "&8" + (shared ? "Cuenta compartida" : "Cuenta personal") + " · " + a.id(), "",
                "&7Saldo: " + Money.fmt(a.currency(), a.balance()), "&7Tu rol: &f" + roleName(a.role()), "",
                "&e▸ Clic para abrir");
    }

    private static String roleName(String r) {
        return switch (r) { case "OWNER" -> "Dueño"; case "ADMIN" -> "Gestor"; default -> "Miembro"; };
    }

    public void main(Player p, Runnable back) {
        svc.mine(p.getUniqueId()).whenComplete((list, t) -> Tasks.sync(() -> {
            if (t != null) { Msg.error(p, t); return; }
            if (list.size() > 21) { pagedMain(p, back); return; }
            boolean admin = p.hasPermission("economycore.bank.admin");
            boolean can = p.hasPermission("economycore.bank.create");
            Runnable self = () -> main(p, back);
            new ButtonMenu(pl, p, "&6Banco", 6, true, () -> {
                List<Btn> l = new ArrayList<>();
                java.util.Map<String, BigDecimal> totals = new java.util.LinkedHashMap<>();
                for (Account a : list) totals.merge(a.currency(), a.balance(), BigDecimal::add);
                List<String> info = new ArrayList<>();
                info.add("&7Guarda tu dinero a salvo y");
                info.add("&7gana intereses con el tiempo.");
                info.add("");
                if (totals.isEmpty()) info.add("&7Aún no tienes cuentas.");
                for (var e : totals.entrySet()) info.add("&7Total depositado: " + Money.fmt(e.getKey(), e.getValue()));
                info.add("&7Cuentas: &f" + list.size() + "&8/&7" + pl.getConfig().getInt("bank.max-accounts", 5));
                l.add(Btn.of(4, Items.head(p, "&6Banco de " + p.getName(), info.toArray(new String[0])), () -> {}));
                int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
                for (int i = 0; i < list.size(); i++) {
                    Account a = list.get(i);
                    l.add(Btn.of(slots[i], accountIcon(a), () -> account(p, a.id(), self, false)));
                }
                l.add(Btn.of(38, Items.of(Material.GOLD_INGOT, "&6Nueva cuenta personal", "&7Una cuenta solo para ti.", "", can ? "&e▸ Clic para crear" : "&cNo tienes permiso"), () -> create(p, "PERSONAL", self)));
                l.add(Btn.of(42, Items.of(Material.ENDER_CHEST, "&bNueva cuenta compartida", "&7Con gestores y miembros.", "", can ? "&e▸ Clic para crear" : "&cNo tienes permiso"), () -> create(p, "SHARED", self)));
                if (admin) l.add(Btn.of(40, Items.of(Material.COMMAND_BLOCK, "&cTodas las cuentas", "&7Administración del banco.", "", "&e▸ Clic para ver"), () -> adminAll(p, self)));
                return l;
            }).centerNav().back(back).open();
        }));
    }

    private void pagedMain(Player p, Runnable back) {
        Runnable self = () -> main(p, back);
        new PagedMenu<Account>(pl, p, "&6Banco", () -> svc.mine(p.getUniqueId()), this::accountIcon, (a, ci) -> account(p, a.id(), self, false))
                .searchable(a -> a.name() + " " + a.id()).back(back).load();
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
        svc.get(id, p.getUniqueId(), admin).thenCompose(a -> svc.statement(id).thenApply(st -> new Object[]{a, st})).whenComplete((box, t) -> Tasks.sync(() -> {
            if (t != null) { Msg.error(p, t); back.run(); return; }
            Account a = (Account) box[0];
            @SuppressWarnings("unchecked") List<BankService.Line> st = (List<BankService.Line>) box[1];
            Runnable self = () -> account(p, id, back, admin);
            boolean shared = a.type().equals("SHARED");
            new ButtonMenu(pl, p, "&6" + (shared ? "Cuenta compartida" : "Cuenta personal"), 4, true, () -> {
                List<Btn> l = new ArrayList<>();
                BigDecimal purse = Ledger.cached(p.getUniqueId(), a.currency());
                l.add(Btn.of(11, Items.of(Material.CHEST, "&aDepositar monedas", "&7Guarda monedas en el banco para", "&7tenerlas a salvo.", "",
                        "&7Saldo del banco: " + Money.fmt(a.currency(), a.balance()), "&7En tu bolsillo: " + Money.fmt(a.currency(), purse), "", "&e▸ Clic para depositar"), () -> amountMenu(p, a, true, self)));
                if (a.canWithdraw() || admin) {
                    l.add(Btn.of(13, Items.of(Material.DISPENSER, "&aRetirar monedas", "&7Saca monedas de la cuenta.", "",
                            "&7Saldo del banco: " + Money.fmt(a.currency(), a.balance()), "", "&e▸ Clic para retirar"), () -> amountMenu(p, a, false, self)));
                    l.add(Btn.of(20, Items.of(Material.PAPER, "&bTransferir", "&7Envía dinero a otra cuenta", "&7(ej. B1001) o a un jugador.", "", "&e▸ Clic para transferir"), () ->
                            Form.of(p).ask("to", "Destino: ID de cuenta (ej B1001) o nombre de jugador").ask("amt", "Cantidad").onCancel(self).then(f -> {
                                BigDecimal v = amount(f.get("amt"), a.balance());
                                if (v == null || v.signum() <= 0) { Msg.send(p, "invalid-amount"); self.run(); return; }
                                svc.transfer(p.getUniqueId(), id, f.get("to"), v).whenComplete((r, x) -> Tasks.sync(() -> { if (x != null) Msg.error(p, x); else Msg.send(p, "bank-transferred", Money.fmt(a.currency(), v), r); self.run(); }));
                            })));
                }
                List<String> ml = new ArrayList<>();
                ml.add("&7Tus últimas transacciones.");
                ml.add("");
                long now = System.currentTimeMillis();
                if (st.isEmpty()) ml.add("&8Sin movimientos todavía.");
                for (int i = 0; i < Math.min(5, st.size()); i++) {
                    BankService.Line ln = st.get(i);
                    ml.add((ln.incoming() ? "&a+ " : "&c- ") + "&6" + Money.plain(a.currency(), ln.amount()) + " &8· &7hace " + Fmt.duration(Math.max(0, now - ln.ts())));
                }
                ml.add("");
                ml.add("&e▸ Clic para ver todo");
                l.add(Btn.of(15, Items.of(Material.MAP, "&aMovimientos recientes", ml), () -> statement(p, id, self)));
                if (shared) l.add(Btn.of(22, Items.of(Material.PLAYER_HEAD, "&dMiembros", "&7Gestores y miembros de la cuenta.", "", "&e▸ Clic para gestionar"), () -> members(p, a, self)));
                if (a.role().equals("OWNER") || admin) l.add(Btn.of(24, Items.of(Material.TNT, "&cCerrar cuenta", "&7El saldo vuelve al dueño.", "", "&c▸ Clic para cerrar"), () ->
                        ConfirmMenu.ask(pl, p, "&cCerrar " + a.name(), List.of("&7Saldo: " + Money.plain(a.currency(), a.balance()), "&7Se transfiere al dueño"), () ->
                                svc.close(p.getUniqueId(), id, admin).whenComplete((r, x) -> Tasks.sync(() -> { if (x != null) Msg.error(p, x); back.run(); })), self)));
                double rate = pl.getConfig().getDouble(shared ? "bank.interest.shared-percent" : "bank.interest.personal-percent", 0.5);
                BigDecimal maxBal = Money.dec(pl.getConfig().getString("bank.interest.max-balance", "1000000"), BigDecimal.valueOf(1_000_000));
                BigDecimal base = a.balance().min(maxBal);
                BigDecimal proj = Money.scale(a.currency(), base.multiply(BigDecimal.valueOf(rate)).divide(BigDecimal.valueOf(100), 12, java.math.RoundingMode.DOWN));
                l.add(Btn.of(32, Items.of(Material.REDSTONE_TORCH, "&aInformación", "&7" + a.name() + " &8· " + a.id(), "&7Tu rol: &f" + roleName(a.role()), "",
                        "&7Mantén tus monedas a salvo en el banco.", "",
                        "&7Saldo: " + Money.fmt(a.currency(), a.balance()), "&7En tu bolsillo: " + Money.fmt(a.currency(), purse), "",
                        "&7El banco te recompensa cada &f" + pl.getConfig().getLong("bank.interest.interval-minutes", 60) + " min",
                        "&7con intereses por tus monedas.", "",
                        "&7Tasa: &b" + rate + "%", "&7Proyectado: " + Money.fmt(a.currency(), proj),
                        "&7Saldo máximo con interés: &6" + Money.plain(a.currency(), maxBal), "",
                        "&7Intereses: " + (a.interest() ? "&aActivos" : "&cDesactivados"), admin ? "" : null, admin ? "&e▸ Clic para alternar intereses" : null), () -> {
                    if (admin) svc.toggleInterest(p.getName(), id).whenComplete((r, x) -> Tasks.sync(self));
                }));
                return l;
            }).back(back).open();
        }));
    }

    /** Hypixel layout: deposit = 11/13/15 (whole purse, half, custom); withdraw = 10/12/14/16 (all, half, 20%, custom). The stack size on each icon hints at the fraction. */
    private void amountMenu(Player p, Account a, boolean deposit, Runnable back) {
        BigDecimal source = deposit ? Ledger.cached(p.getUniqueId(), a.currency()) : a.balance();
        BigDecimal half = Money.scale(a.currency(), source.divide(BigDecimal.valueOf(2), 12, java.math.RoundingMode.DOWN));
        BigDecimal fifth = Money.scale(a.currency(), source.multiply(BigDecimal.valueOf(0.2)));
        String verb = deposit ? "depositar" : "retirar";
        String tag = deposit ? "&8Depósito en el banco" : "&8Retiro del banco";
        new ButtonMenu(pl, p, deposit ? "&6Depositar monedas" : "&6Retirar monedas", 4, true, () -> {
            List<Btn> l = new ArrayList<>();
            String all = deposit ? "&aTodo tu bolsillo" : "&aTodo el saldo";
            String hf = deposit ? "&aMitad de tu bolsillo" : "&aMitad del saldo";
            String bal = "&7Saldo del banco: " + Money.fmt(a.currency(), a.balance());
            l.add(Btn.of(deposit ? 11 : 10, Items.qty(Items.of(deposit ? Material.CHEST : Material.DISPENSER, all, tag, "", bal, "&7Cantidad: " + Money.fmt(a.currency(), source), "", "&e▸ Clic para " + verb), 64),
                    () -> run(p, a, deposit, source, back)));
            l.add(Btn.of(deposit ? 13 : 12, Items.qty(Items.of(deposit ? Material.CHEST : Material.DROPPER, hf, tag, "", bal, "&7Cantidad: " + Money.fmt(a.currency(), half), "", "&e▸ Clic para " + verb), 32),
                    () -> run(p, a, deposit, half, back)));
            if (!deposit) l.add(Btn.of(14, Items.qty(Items.of(Material.DROPPER, "&aRetirar 20%", tag, "", bal, "&7Cantidad: " + Money.fmt(a.currency(), fifth), "", "&e▸ Clic para retirar"), 16),
                    () -> run(p, a, false, fifth, back)));
            l.add(Btn.of(deposit ? 15 : 16, Items.of(Material.OAK_SIGN, "&aCantidad personalizada", tag, "", bal, "&8Admite 1.5k, 2m, 3b o 'todo'.", "", "&e▸ Clic para escribir"), () ->
                    Prompt.ask(p, "Cantidad a " + verb, s -> {
                        BigDecimal v = amount(s, source);
                        run(p, a, deposit, v, back);
                    }, () -> amountMenu(p, a, deposit, back))));
            return l;
        }).back(back).open();
    }

    private void run(Player p, Account a, boolean deposit, BigDecimal v, Runnable back) {
        if (v == null || v.signum() <= 0) { Msg.send(p, "invalid-amount"); back.run(); return; }
        (deposit ? svc.deposit(p.getUniqueId(), a.id(), v) : svc.withdraw(p.getUniqueId(), a.id(), v)).whenComplete((r, x) -> Tasks.sync(() -> {
            if (x != null) Msg.error(p, x); else Msg.send(p, deposit ? "bank-deposited" : "bank-withdrawn", Money.fmt(a.currency(), v));
            back.run();
        }));
    }

    private void statement(Player p, String id, Runnable back) {
        new PagedMenu<BankService.Line>(pl, p, "&6Banco &8➜ &6Movimientos", () -> svc.statement(id), ln -> Items.of(ln.incoming() ? Material.LIME_DYE : Material.RED_DYE, (ln.incoming() ? "&a+" : "&c-") + ln.amount().toPlainString() + " &8· &f" + ln.type(),
                "&7" + (ln.note() == null ? "" : ln.note()), "&8" + Fmt.dateFull(ln.ts())), (ln, ci) -> {}).back(back).load();
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
