package com.economycore.company;

import com.economycore.company.CompanyModels.Company;
import com.economycore.company.CompanyModels.Member;
import com.economycore.company.CompanyModels.Product;
import com.economycore.company.CompanyService.LedgerRow;
import com.economycore.core.EconomyCore;
import com.economycore.core.Fmt;
import com.economycore.core.Inv;
import com.economycore.core.Mailbox;
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
import com.economycore.stock.StockModule;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class CompanyMenus {
    private final EconomyCore pl;
    private final CompanyService svc;

    public CompanyMenus(EconomyCore pl, CompanyService svc) {
        this.pl = pl;
        this.svc = svc;
    }

    private static Integer parseInt(String s) {
        try { int n = Integer.parseInt(s.trim()); return n > 0 ? n : null; } catch (NumberFormatException e) { return null; }
    }

    private boolean admin(Player p) { return p.hasPermission("economycore.company.admin"); }

    private static Material iconOf(Company c) {
        Material m = c.icon() == null ? null : Material.matchMaterial(c.icon());
        return m == null ? Material.GOLD_BLOCK : m;
    }

    /** Runs a service call, reports errors, then runs `again` on the main thread. */
    private void act(Player p, CompletableFuture<?> f, String okKey, Runnable again) {
        f.whenComplete((r, t) -> Tasks.sync(() -> {
            if (t != null) Msg.error(p, t); else if (okKey != null) Msg.send(p, okKey);
            again.run();
        }));
    }

    // ---------- list ----------
    public void main(Player p, Runnable back) { list(p, back, false); }

    public void list(Player p, Runnable back, boolean mine) {
        Runnable self = () -> list(p, back, mine);
        new PagedMenu<Company>(pl, p, mine ? "&6Mis empresas" : "&6Empresas", () -> mine ? svc.mine(p.getUniqueId()) : svc.list(), c ->
                Items.of(iconOf(c), "&e" + c.name() + " &7(" + c.ticker() + ")", "&7Sector: &f" + c.industry(), "&7Tipo: &f" + (c.isDefault() ? "Predeterminada" : "Jugador"),
                        "&7Acción: &f" + Money.fmt(c.currency(), c.price()), "&7En bolsa: " + (c.listed() ? "&aSí" : "&cNo"), "&eClic para abrir"),
                (c, ci) -> detail(p, c.id(), self))
                .searchable(c -> c.name() + " " + c.ticker() + " " + c.industry())
                .extras(() -> {
                    List<Btn> l = new ArrayList<>();
                    l.add(Btn.of(47, Items.of(mine ? Material.BOOK : Material.WRITTEN_BOOK, mine ? "&bVer todas" : "&bMis empresas"), () -> list(p, back, !mine)));
                    if (p.hasPermission("economycore.company.create")) l.add(Btn.of(51, Items.of(Material.EMERALD, "&aCrear empresa",
                            "&7Coste: &f" + Money.fmt(pl.currencies().defId(), Money.dec(pl.getConfig().getString("companies.creation-cost", "5000"), BigDecimal.ZERO))), () ->
                            Form.of(p).ask("name", "Nombre de la empresa").ask("ticker", "Ticker (2-5 letras)").onCancel(self).then(a ->
                                    svc.create(p, a.get("name"), a.get("ticker")).whenComplete((id, t) -> Tasks.sync(() -> {
                                        if (t != null) { Msg.error(p, t); self.run(); } else detail(p, id, self);
                                    })))));
                    if (admin(p)) l.add(Btn.of(50, Items.of(Material.COMMAND_BLOCK, "&cAdmin: crear empresa predeterminada", "&7Propiedad del servidor, con capital del sistema"), () ->
                            Form.of(p).ask("name", "Nombre").ask("ticker", "Ticker").ask("industry", "Sector").ask("shares", "Acciones totales").ask("price", "Precio por acción").ask("capital", "Capital inicial").onCancel(self).then(a -> {
                                Integer sh = parseInt(a.get("shares"));
                                BigDecimal pr = Money.parse(a.get("price")), cap = Money.parse(a.get("capital"));
                                if (sh == null || pr == null || cap == null) { Msg.send(p, "invalid-number"); self.run(); return; }
                                act(p, svc.adminCreate(p, a.get("name"), a.get("ticker"), a.get("industry"), sh, pr, cap), "saved", self);
                            })));
                    return l;
                }).back(back).load();
    }

    // ---------- detail ----------
    public void detail(Player p, String id, Runnable back) {
        CompletableFuture<Company> fc = svc.get(id);
        CompletableFuture<Member> fm = svc.member(id, p.getUniqueId());
        CompletableFuture<BigDecimal> fb = svc.balance(id);
        CompletableFuture.allOf(fc, fm, fb).whenComplete((v, t) -> Tasks.sync(() -> {
            if (t != null) { Msg.error(p, t); return; }
            Company c = fc.join();
            Member m = fm.join();
            if (c == null || !c.active()) { Msg.error(p, new com.economycore.core.EcoException("company-gone")); back.run(); return; }
            BigDecimal bal = fb.join();
            boolean adm = admin(p);
            boolean staff = m != null || adm;
            Runnable self = () -> detail(p, id, back);
            new ButtonMenu(pl, p, "&6" + c.name(), 5, true, () -> {
                List<Btn> l = new ArrayList<>();
                boolean showBal = staff && (adm || m != null && (m.has("FINANCE") || m.has("ACCOUNTING")));
                l.add(Btn.of(4, Items.of(iconOf(c), "&e" + c.name() + " &7(" + c.ticker() + ")", "&7" + c.description(), "&7Sector: &f" + c.industry(),
                        "&7Tu rol: &f" + (m == null ? "-" : m.role()), "&7Acción: &f" + Money.fmt(c.currency(), c.price()), "&7Acciones: &f" + c.totalShares(),
                        showBal ? "&7Caja: &f" + Money.fmt(c.currency(), bal) : "&7Caja: &8(privado)"), () -> {}));
                l.add(Btn.of(19, Items.of(Material.CHEST, "&aTienda de la empresa", "&7Productos y servicios a la venta"), () -> shop(p, id, self)));
                l.add(Btn.of(20, Items.of(Material.NETHER_STAR, "&bAcciones en bolsa", c.listed() ? "&7Cotiza como &f" + c.ticker() : "&cNo cotiza"), () -> {
                    if (pl.module("stocks") instanceof StockModule sm) sm.menus().detail(p, id, self); else Msg.send(p, "module-disabled");
                }));
                l.add(Btn.of(21, Items.of(Material.PLAYER_HEAD, "&fEquipo", "&7Directivos y empleados"), () -> members(p, id, c, m, self)));
                if (staff) {
                    if (adm || m.has("FINANCE")) l.add(Btn.of(23, Items.of(Material.GOLD_INGOT, "&6Finanzas", "&7Depositar y retirar de la caja"), () -> finance(p, c, self)));
                    if (adm || m.has("PRODUCTS")) l.add(Btn.of(24, Items.of(Material.CRAFTING_TABLE, "&eProductos y servicios", "&7Crear, reponer, precio, quitar"), () -> manageProducts(p, id, self)));
                    if (adm || m.has("ACCOUNTING")) l.add(Btn.of(25, Items.of(Material.WRITABLE_BOOK, "&bContabilidad", "&7Ingresos, gastos y libro mayor"), () -> accounting(p, c, self)));
                    if (adm || m.has("DIVIDENDS")) l.add(Btn.of(31, Items.of(Material.EMERALD, "&aDividendos", "&7Pagar a accionistas, reparto automático: &f" + c.autoDivPct() + "%"), () -> dividends(p, c, self)));
                    if (adm || m.role().equals("CEO")) l.add(Btn.of(33, Items.of(Material.TNT, "&cDisolver empresa", "&7La caja pasa al CEO"), () ->
                            ConfirmMenu.ask(pl, p, "&cDisolver " + c.name(), List.of("&7Esta acción no se puede deshacer"), () ->
                                    act(p, svc.disband(p, id, adm), "saved", back), self)));
                }
                if (adm) l.add(Btn.of(32, Items.of(Material.COMMAND_BLOCK, "&cAdmin: asignar CEO"), () ->
                        Prompt.ask(p, "Nombre del nuevo CEO", s -> act(p, svc.setCeo(p, id, s), "saved", self), self)));
                return l;
            }).back(back).open();
        }));
    }

    // ---------- customer shop ----------
    public void shop(Player p, String id, Runnable back) {
        Runnable self = () -> shop(p, id, back);
        new PagedMenu<Product>(pl, p, "&6Tienda de empresa", () -> svc.products(id, false), pr -> {
            boolean item = pr.kind().equals("PRODUCT");
            ItemStack base = item ? Inv.decodeOr(pr.itemData(), pr.material()) : new ItemStack(Material.NAME_TAG);
            List<String> lore = new ArrayList<>();
            lore.add("&7Precio: &f" + Money.fmt(svc.cur(id), pr.price()));
            lore.add(item ? "&7Stock: &f" + pr.stock() : "&7Servicio");
            lore.add("&aIzq: comprar 1 &7| &aDer: elegir cantidad");
            return Items.withLore(item ? base : Items.of(Material.NAME_TAG, "&e" + pr.name()), lore);
        }, (pr, ci) -> {
            if (ci.right()) Prompt.ask(p, "Cantidad a comprar", s -> {
                Integer n = parseInt(s);
                if (n == null) { Msg.send(p, "invalid-number"); self.run(); return; }
                buy(p, pr, n, self);
            }, self);
            else buy(p, pr, 1, self);
        }).searchable(pr -> pr.name() + " " + (pr.material() == null ? "" : pr.material())).back(back).load();
    }

    private void buy(Player p, Product pr, int qty, Runnable again) {
        svc.buy(p, pr.id(), qty).whenComplete((r, t) -> Tasks.sync(() -> {
            if (t != null) Msg.error(p, t);
            else Msg.send(p, "company-bought", r.name(), qty, Money.fmt(r.currency(), r.total()));
            again.run();
        }));
    }

    // ---------- finance ----------
    private void finance(Player p, Company c, Runnable back) {
        Runnable self = () -> finance(p, c, back);
        svc.balance(c.id()).whenComplete((bal, t) -> Tasks.sync(() -> {
            if (t != null) { Msg.error(p, t); return; }
            boolean adm = admin(p);
            new ButtonMenu(pl, p, "&6Finanzas: " + c.name(), 3, true, () -> List.of(
                    Btn.of(4, Items.of(Material.GOLD_BLOCK, "&eCaja", "&f" + Money.fmt(c.currency(), bal)), () -> {}),
                    Btn.of(11, Items.of(Material.LIME_CONCRETE, "&aDepositar", "&7Desde tu saldo"), () -> Prompt.ask(p, "Cantidad a depositar", s -> {
                        BigDecimal a = Money.parse(s);
                        if (a == null) { Msg.send(p, "invalid-amount"); self.run(); return; }
                        act(p, svc.deposit(p, c.id(), a, adm), "saved", self);
                    }, self)),
                    Btn.of(15, Items.of(Material.ORANGE_CONCRETE, "&6Retirar", "&7A tu saldo"), () -> Prompt.ask(p, "Cantidad a retirar", s -> {
                        BigDecimal a = Money.parse(s);
                        if (a == null) { Msg.send(p, "invalid-amount"); self.run(); return; }
                        act(p, svc.withdraw(p, c.id(), a, adm), "saved", self);
                    }, self)))).back(back).open();
        }));
    }

    // ---------- accounting ----------
    private void accounting(Player p, Company c, Runnable back) {
        Runnable self = () -> accounting(p, c, back);
        long now = System.currentTimeMillis();
        CompletableFuture<Map<String, BigDecimal>> d = svc.report(c.id(), now - 86_400_000L), w = svc.report(c.id(), now - 7 * 86_400_000L), all = svc.report(c.id(), 0);
        CompletableFuture.allOf(d, w, all).whenComplete((v, t) -> Tasks.sync(() -> {
            if (t != null) { Msg.error(p, t); return; }
            new ButtonMenu(pl, p, "&bContabilidad: " + c.name(), 3, true, () -> List.of(
                    Btn.of(10, report("&e24 horas", d.join(), c.currency()), () -> {}),
                    Btn.of(12, report("&e7 días", w.join(), c.currency()), () -> {}),
                    Btn.of(14, report("&eTotal", all.join(), c.currency()), () -> {}),
                    Btn.of(16, Items.of(Material.BOOK, "&bLibro mayor", "&7Últimos 300 movimientos"), () -> ledger(p, c, self)))).back(back).open();
        }));
    }

    private ItemStack report(String title, Map<String, BigDecimal> m, String cur) {
        List<String> lore = new ArrayList<>();
        BigDecimal net = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> e : m.entrySet()) {
            lore.add("&7" + e.getKey() + ": " + (e.getValue().signum() >= 0 ? "&a" : "&c") + Money.fmt(cur, e.getValue()));
            if (!e.getKey().equals("DEPOSIT") && !e.getKey().equals("WITHDRAW") && !e.getKey().equals("CAPITAL") && !e.getKey().equals("INVEST")) net = net.add(e.getValue());
        }
        lore.add("&fResultado operativo: " + (net.signum() >= 0 ? "&a" : "&c") + Money.fmt(cur, net));
        return Items.of(Material.PAPER, title, lore);
    }

    private void ledger(Player p, Company c, Runnable back) {
        new PagedMenu<LedgerRow>(pl, p, "&bLibro mayor", () -> svc.ledger(c.id()), r ->
                Items.of(r.amount().signum() >= 0 ? Material.LIME_DYE : Material.RED_DYE, "&f" + r.kind(), "&7Importe: " + Money.fmt(c.currency(), r.amount()), "&7" + r.note(), "&8" + Fmt.dateFull(r.ts())), (r, ci) -> {})
                .searchable(r -> r.kind() + " " + r.note()).back(back).load();
    }

    // ---------- dividends ----------
    private void dividends(Player p, Company c, Runnable back) {
        Runnable self = () -> dividends(p, c, back);
        boolean adm = admin(p);
        new ButtonMenu(pl, p, "&aDividendos: " + c.name(), 3, true, () -> List.of(
                Btn.of(11, Items.of(Material.EMERALD, "&aPagar dividendo", "&7Importe por acción, desde la caja"), () -> Prompt.ask(p, "Dividendo por acción", s -> {
                    BigDecimal a = Money.parse(s);
                    if (a == null) { Msg.send(p, "invalid-amount"); self.run(); return; }
                    svc.dividend(p, c.id(), a, adm).whenComplete((r, t) -> Tasks.sync(() -> {
                        if (t != null) Msg.error(p, t); else Msg.send(p, "dividend-paid", Money.fmt(c.currency(), r));
                        self.run();
                    }));
                }, self)),
                Btn.of(15, Items.of(Material.CLOCK, "&bReparto automático", "&7% de la caja repartido periódicamente", "&7Actual: &f" + c.autoDivPct() + "%"), () -> Prompt.ask(p, "Porcentaje (0-100)", s -> {
                    BigDecimal a = Money.parse(s);
                    if (a == null) { Msg.send(p, "invalid-amount"); self.run(); return; }
                    act(p, svc.setAutoDiv(p, c.id(), a, adm), "saved", () -> detail(p, c.id(), back));
                }, self)))).back(back).open();
    }

    // ---------- members ----------
    private void members(Player p, String id, Company c, Member me, Runnable back) {
        Runnable self = () -> members(p, id, c, me, back);
        boolean adm = admin(p);
        new PagedMenu<Member>(pl, p, "&fEquipo: " + c.name(), () -> svc.members(id), m ->
                Items.of(Material.PLAYER_HEAD, "&e" + m.name(), "&7Rol: &f" + m.role(), "&7Salario: &f" + Money.fmt(c.currency(), m.salary()), "&7Permisos: &f" + (m.role().equals("CEO") ? "todos" : m.flags()),
                        (adm || me != null && me.has("SALARY") || me != null && me.role().equals("CEO")) ? "&eClic para gestionar" : ""),
                (m, ci) -> { if (adm || me != null && (me.has("SALARY") || me.has("FIRE") || me.role().equals("CEO"))) member(p, id, c, m, me, self); })
                .searchable(m -> m.name() + " " + m.role())
                .extras(() -> {
                    List<Btn> l = new ArrayList<>();
                    if (adm || me != null && me.has("HIRE")) l.add(Btn.of(47, Items.of(Material.EMERALD, "&aContratar", "&7Añade un jugador"), () ->
                            Form.of(p).ask("name", "Nombre del jugador").ask("role", "Rol (EMPLOYEE o DIRECTOR)").ask("salary", "Salario por nómina").onCancel(self).then(a -> {
                                BigDecimal sal = Money.parse(a.get("salary"));
                                String role = a.get("role").toUpperCase();
                                if (sal == null || !(role.equals("EMPLOYEE") || role.equals("DIRECTOR"))) { Msg.send(p, "invalid-number"); self.run(); return; }
                                act(p, svc.hire(p, id, a.get("name"), role, sal, adm), "saved", self);
                            })));
                    return l;
                }).back(back).load();
    }

    private void member(Player p, String id, Company c, Member m, Member me, Runnable back) {
        Runnable reopen = () -> svc.member(id, java.util.UUID.fromString(m.uuid())).whenComplete((nm, t) -> Tasks.sync(() -> {
            if (t != null || nm == null) back.run(); else member(p, id, c, nm, me, back);
        }));
        boolean adm = admin(p);
        boolean ceo = adm || me != null && me.role().equals("CEO");
        new ButtonMenu(pl, p, "&e" + m.name(), 4, true, () -> {
            List<Btn> l = new ArrayList<>();
            l.add(Btn.of(4, Items.of(Material.PLAYER_HEAD, "&e" + m.name(), "&7Rol: &f" + m.role(), "&7Salario: &f" + Money.fmt(c.currency(), m.salary())), () -> {}));
            if (!m.role().equals("CEO")) {
                if (adm || me != null && me.has("SALARY")) l.add(Btn.of(10, Items.of(Material.GOLD_NUGGET, "&6Cambiar salario"), () -> Prompt.ask(p, "Nuevo salario", s -> {
                    BigDecimal a = Money.parse(s);
                    if (a == null) { Msg.send(p, "invalid-amount"); reopen.run(); return; }
                    act(p, svc.setSalary(p, id, m.uuid(), a, adm), "saved", reopen);
                }, reopen)));
                if (ceo) {
                    l.add(Btn.of(12, Items.of(Material.NAME_TAG, "&bCambiar rol", "&7Ahora: &f" + m.role(), "&7Alterna empleado/directivo"), () ->
                            act(p, svc.setRole(p, id, m.uuid(), m.role().equals("DIRECTOR") ? "EMPLOYEE" : "DIRECTOR", adm), "saved", reopen)));
                    l.add(Btn.of(14, Items.of(Material.GOLD_BLOCK, "&6Traspasar CEO", "&7Este miembro pasa a ser CEO"), () ->
                            ConfirmMenu.ask(pl, p, "&6Traspasar CEO", List.of("&7" + m.name() + " será el nuevo CEO"), () -> act(p, svc.setRole(p, id, m.uuid(), "CEO", adm), "saved", back), reopen)));
                    int slot = 18;
                    for (String f : CompanyModels.FLAGS) {
                        boolean on = m.has(f);
                        l.add(Btn.of(slot++, Items.of(on ? Material.LIME_DYE : Material.GRAY_DYE, (on ? "&a" : "&7") + f, "&7Clic para " + (on ? "quitar" : "dar")), () ->
                                act(p, svc.toggleFlag(p, id, m.uuid(), f, adm), null, reopen)));
                    }
                }
                if (adm || me != null && me.has("FIRE")) l.add(Btn.of(16, Items.of(Material.BARRIER, "&cDespedir"), () ->
                        ConfirmMenu.ask(pl, p, "&cDespedir a " + m.name(), List.of("&7Se elimina del equipo"), () -> act(p, svc.fire(p, id, m.uuid(), adm), "saved", back), reopen)));
            }
            return l;
        }).back(back).open();
    }

    // ---------- products ----------
    private void manageProducts(Player p, String id, Runnable back) {
        Runnable self = () -> manageProducts(p, id, back);
        boolean adm = admin(p);
        String cur = svc.cur(id);
        new PagedMenu<Product>(pl, p, "&eProductos y servicios", () -> svc.products(id, false), pr -> {
            ItemStack base = pr.kind().equals("PRODUCT") ? Inv.decodeOr(pr.itemData(), pr.material()) : Items.of(Material.NAME_TAG, "&e" + pr.name());
            return Items.withLore(base, "&7Precio: &f" + Money.fmt(cur, pr.price()), pr.kind().equals("PRODUCT") ? "&7Stock: &f" + pr.stock() : "&7Servicio",
                    pr.produceAmount() > 0 ? "&7Produce &f" + pr.produceAmount() + " &7cada &f" + pr.produceInterval() + " min" : "",
                    "&aIzq: reponer stock desde tu mano", "&eDer: cambiar precio", "&cShift+Der: quitar");
        }, (pr, ci) -> {
            if (ci.shift() && ci.right()) {
                ConfirmMenu.ask(pl, p, "&cQuitar producto", List.of("&7El stock vuelve a tu buzón"), () -> act(p, svc.removeProduct(p, pr.id(), adm), "saved", self), self);
            } else if (ci.right()) {
                Prompt.ask(p, "Nuevo precio", s -> {
                    BigDecimal a = Money.parse(s);
                    if (a == null) { Msg.send(p, "invalid-amount"); self.run(); return; }
                    act(p, svc.setProductPrice(p, pr.id(), a, adm), "saved", self);
                }, self);
            } else if (pr.kind().equals("PRODUCT")) {
                Prompt.ask(p, "Cantidad a reponer (llevándola en el inventario)", s -> {
                    Integer n = parseInt(s);
                    ItemStack proto = Inv.decodeOr(pr.itemData(), pr.material());
                    if (n == null) { Msg.send(p, "invalid-number"); self.run(); return; }
                    if (!Inv.take(p, proto, n)) { Msg.send(p, "not-enough-items"); self.run(); return; }
                    svc.addStock(p, pr.id(), n, adm).whenComplete((r, t) -> Tasks.sync(() -> {
                        if (t != null) {
                            Msg.error(p, t);
                            pl.db().tx(tx -> { Mailbox.add(tx, p.getUniqueId(), proto, n, "company-return"); return null; });
                        } else Msg.send(p, "saved");
                        self.run();
                    }));
                }, self);
            }
        }).searchable(pr -> pr.name() + " " + pr.kind())
                .extras(() -> {
                    List<Btn> l = new ArrayList<>();
                    l.add(Btn.of(47, Items.of(Material.CHEST, "&aAñadir producto", "&7Usa el objeto de tu mano", "&7Los objetos salen de tu inventario"), () -> {
                        ItemStack hand = Inv.hand(p);
                        if (Inv.isEmpty(hand)) { Msg.send(p, "hold-item"); return; }
                        ItemStack tmpl = hand.clone();
                        tmpl.setAmount(1);
                        Form.of(p).ask("price", "Precio por unidad").ask("stock", "Cantidad inicial (0 = ninguna)").onCancel(self).then(a -> {
                            BigDecimal pr = Money.parse(a.get("price"));
                            int st;
                            try { st = Integer.parseInt(a.get("stock").trim()); } catch (NumberFormatException e) { st = -1; }
                            if (pr == null || st < 0) { Msg.send(p, "invalid-number"); self.run(); return; }
                            if (st > 0 && !Inv.take(p, tmpl, st)) { Msg.send(p, "not-enough-items"); self.run(); return; }
                            act(p, svc.addProduct(p, id, "PRODUCT", Inv.name(tmpl), tmpl, st, pr, null, adm), "saved", self);
                        });
                    }));
                    l.add(Btn.of(51, Items.of(Material.NAME_TAG, "&bAñadir servicio", "&7Ejecuta un comando al comprarlo", "&7(comando: sólo administradores)"), () ->
                            Form.of(p).ask("name", "Nombre del servicio").ask("price", "Precio").ask("cmd", "Comando (%player%) o 'ninguno'").onCancel(self).then(a -> {
                                BigDecimal pr = Money.parse(a.get("price"));
                                if (pr == null) { Msg.send(p, "invalid-amount"); self.run(); return; }
                                String cmd = a.get("cmd").equalsIgnoreCase("ninguno") ? "" : a.get("cmd");
                                act(p, svc.addProduct(p, id, "SERVICE", a.get("name"), null, 0, pr, cmd, adm), "saved", self);
                            })));
                    return l;
                }).back(back).load();
    }
}
