package com.economycore.treasury;

import com.economycore.core.EconomyCore;
import com.economycore.core.Fmt;
import com.economycore.core.Money;
import com.economycore.core.Msg;
import com.economycore.core.Tasks;
import com.economycore.eco.EconomyModule;
import com.economycore.gui.Btn;
import com.economycore.gui.ButtonMenu;
import com.economycore.gui.ConfirmMenu;
import com.economycore.gui.Form;
import com.economycore.gui.Items;
import com.economycore.gui.PagedMenu;
import com.economycore.gui.Prompt;
import com.economycore.treasury.TreasuryService.Budget;
import com.economycore.treasury.TreasuryService.Project;
import com.economycore.treasury.TreasuryService.Reward;
import com.economycore.treasury.TreasuryService.Subsidy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Material;
import org.bukkit.entity.Player;

public final class TreasuryMenus {
    private final EconomyCore pl;
    private final TreasuryService svc;

    public TreasuryMenus(EconomyCore pl, TreasuryService svc) {
        this.pl = pl;
        this.svc = svc;
    }

    private static boolean admin(Player p) { return p.hasPermission("economycore.treasury.admin"); }

    private static Integer parseInt(String s) {
        try { int n = Integer.parseInt(s.trim()); return n >= 0 ? n : null; } catch (NumberFormatException e) { return null; }
    }

    private static Long parseLong(String s) {
        try { long n = Long.parseLong(s.trim()); return n >= 0 ? n : null; } catch (NumberFormatException e) { return null; }
    }

    private Form withCur(Form f) {
        if (pl.currencies().all().size() > 1) f.ask("cur", "Moneda (id)");
        return f;
    }

    private String cur(java.util.Map<String, String> a) {
        if (!a.containsKey("cur")) return pl.currencies().defId();
        var c = pl.currencies().get(a.get("cur").trim());
        return c == null ? null : c.id();
    }

    private void act(Player p, CompletableFuture<?> f, String okKey, Runnable again) {
        f.whenComplete((r, t) -> Tasks.sync(() -> {
            if (t != null) Msg.error(p, t); else if (okKey != null) Msg.send(p, okKey);
            again.run();
        }));
    }

    // ---------- main ----------
    public void main(Player p, Runnable back) {
        boolean adm = admin(p);
        Runnable self = () -> main(p, back);
        svc.balances().whenComplete((bals, t) -> Tasks.sync(() -> {
            if (t != null) { Msg.error(p, t); return; }
            new ButtonMenu(pl, p, "&6Tesorería", 4, true, () -> {
                List<Btn> l = new ArrayList<>();
                List<String> lore = new ArrayList<>();
                for (Map.Entry<String, BigDecimal> e : bals.entrySet()) lore.add("&7" + e.getKey() + ": &f" + Money.fmt(e.getKey(), e.getValue()));
                l.add(Btn.of(4, Items.of(Material.GOLD_BLOCK, "&6Fondos del Estado", lore), () -> {}));
                l.add(Btn.of(10, Items.of(Material.PAPER, "&bProyectos y votaciones", "&7Vota proyectos financiados por la tesorería"), () -> projects(p, self)));
                l.add(Btn.of(11, Items.of(Material.EMERALD, "&aDonar a la tesorería"), () -> withCur(Form.of(p).ask("amt", "Cantidad").onCancel(self)).then(a -> {
                    BigDecimal v = Money.parse(a.get("amt"));
                    String c = cur(a);
                    if (v == null || c == null) { Msg.send(p, "invalid-amount"); self.run(); return; }
                    act(p, svc.deposit(p, c, v), "saved", self);
                })));
                if (adm) {
                    l.add(Btn.of(12, Items.of(Material.BOOK, "&eRecaudación", "&7Impuestos y comisiones cobrados"), () -> collection(p, self)));
                    l.add(Btn.of(13, Items.of(Material.GOLD_INGOT, "&6Impuestos progresivos", "&7Tramos y exenciones"), () -> ((EconomyModule) pl.module("economy")).menus().taxes(p, self)));
                    l.add(Btn.of(14, Items.of(Material.CHEST, "&bPresupuestos", "&7Partidas de gasto"), () -> budgets(p, self)));
                    l.add(Btn.of(15, Items.of(Material.CLOCK, "&dSubsidios", "&7Pagos periódicos"), () -> subsidies(p, self)));
                    l.add(Btn.of(16, Items.of(Material.DIAMOND, "&aRecompensas", "&7Por entrar, matar mobs o picar bloques"), () -> rewards(p, self)));
                    l.add(Btn.of(20, Items.of(Material.WRITABLE_BOOK, "&fAuditoría", "&7Registro de acciones administrativas"), () -> ((EconomyModule) pl.module("economy")).menus().audit(p, self)));
                    l.add(Btn.of(21, Items.of(Material.PLAYER_HEAD, "&6Pagar desde tesorería", "&7Pago directo a un jugador"), () ->
                            withCur(Form.of(p).ask("name", "Jugador").ask("amt", "Cantidad").ask("note", "Concepto").onCancel(self)).then(a -> {
                                BigDecimal v = Money.parse(a.get("amt"));
                                String c = cur(a);
                                if (v == null || c == null) { Msg.send(p, "invalid-amount"); self.run(); return; }
                                act(p, svc.payout(p, a.get("name"), c, v, 0, a.get("note")), "saved", self);
                            })));
                    l.add(Btn.of(22, Items.of(Material.TNT, "&cCobrar impuesto al patrimonio ahora", "&7Aplica los tramos WEALTH a todos"), () ->
                            ConfirmMenu.ask(pl, p, "&cImpuesto al patrimonio", List.of("&7Se cobra a todos los jugadores según los tramos"), () -> { svc.wealthTax(); Msg.send(p, "saved"); self.run(); }, self)));
                }
                return l;
            }).back(back).open();
        }));
    }

    private void collection(Player p, Runnable back) {
        String c = pl.currencies().defId();
        long now = System.currentTimeMillis();
        CompletableFuture<Map<String, BigDecimal>> d = svc.collection(c, now - 86_400_000L), w = svc.collection(c, now - 7 * 86_400_000L), all = svc.collection(c, 0);
        CompletableFuture.allOf(d, w, all).whenComplete((v, t) -> Tasks.sync(() -> {
            if (t != null) { Msg.error(p, t); return; }
            new ButtonMenu(pl, p, "&eRecaudación (" + c + ")", 3, true, () -> List.of(
                    Btn.of(10, rep("&e24 horas", d.join(), c), () -> {}), Btn.of(13, rep("&e7 días", w.join(), c), () -> {}), Btn.of(16, rep("&eTotal", all.join(), c), () -> {}))).back(back).open();
        }));
    }

    private org.bukkit.inventory.ItemStack rep(String title, Map<String, BigDecimal> m, String c) {
        List<String> lore = new ArrayList<>();
        BigDecimal tot = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> e : m.entrySet()) { lore.add("&7" + e.getKey() + ": &f" + Money.fmt(c, e.getValue())); tot = tot.add(e.getValue()); }
        lore.add("&6Total: &f" + Money.fmt(c, tot));
        return Items.of(Material.PAPER, title, lore);
    }

    // ---------- projects ----------
    public void projects(Player p, Runnable back) {
        boolean adm = admin(p);
        Runnable self = () -> projects(p, back);
        new PagedMenu<Project>(pl, p, "&bProyectos", svc::projects, pr -> {
            Material m = switch (pr.status()) { case "VOTING" -> Material.BLUE_CONCRETE; case "FUNDED" -> Material.LIME_CONCRETE; case "APPROVED" -> Material.YELLOW_CONCRETE; default -> Material.RED_CONCRETE; };
            List<String> lore = new ArrayList<>();
            lore.add("&7" + pr.description());
            lore.add("&7Coste: &f" + Money.fmt(pr.currency(), pr.cost()));
            lore.add("&7Estado: &f" + pr.status());
            lore.add("&7Votos: &a" + pr.yes() + " sí &7/ &c" + pr.no() + " no");
            if (pr.status().equals("VOTING")) {
                lore.add("&7Cierra en: &f" + Fmt.duration(pr.votingEnds() - System.currentTimeMillis()));
                lore.add("&aIzq: votar SÍ &7| &cDer: votar NO");
                if (adm) lore.add("&eShift+Izq: aprobar &7| &eShift+Der: rechazar");
            }
            return Items.of(m, "&e" + pr.name(), lore);
        }, (pr, ci) -> {
            if (!pr.status().equals("VOTING") && !(adm && pr.status().equals("APPROVED"))) return;
            if (ci.shift() && adm) act(p, svc.decide(p, pr.id(), ci.left()), "saved", self);
            else if (pr.status().equals("VOTING")) act(p, svc.vote(p, pr.id(), ci.left()), "vote-saved", self);
        }).searchable(pr -> pr.name() + " " + pr.status())
                .extras(() -> {
                    List<Btn> l = new ArrayList<>();
                    l.add(Btn.of(47, Items.of(Material.WRITABLE_BOOK, "&aProponer proyecto", adm ? "&7Puedes elegir el beneficiario" : "&7Cobrarás tú si se aprueba"), () ->
                            withCur(Form.of(p).ask("name", "Nombre del proyecto").ask("desc", "Descripción").ask("cost", "Coste").ask("hours", "Horas de votación")
                                    .ask("payee", adm ? "Beneficiario (jugador, o 'yo')" : "Escribe 'yo'").onCancel(self)).then(a -> {
                                BigDecimal cost = Money.parse(a.get("cost"));
                                Integer h = parseInt(a.get("hours"));
                                String c = cur(a);
                                if (cost == null || h == null || h == 0 || c == null) { Msg.send(p, "invalid-number"); self.run(); return; }
                                String payee = a.get("payee").equalsIgnoreCase("yo") ? null : a.get("payee");
                                act(p, svc.createProject(p, adm, a.get("name"), a.get("desc"), c, cost, payee, h), "saved", self);
                            })));
                    return l;
                }).back(back).load();
    }

    // ---------- budgets ----------
    private void budgets(Player p, Runnable back) {
        Runnable self = () -> budgets(p, back);
        new PagedMenu<Budget>(pl, p, "&bPresupuestos", svc::budgets, b -> {
            double ratio = b.allocated().signum() == 0 ? 0 : b.spent().doubleValue() / b.allocated().doubleValue();
            return Items.of(Material.CHEST, "&e" + b.name() + " &7(id " + b.id() + ")", "&7Asignado: &f" + Money.fmt(b.currency(), b.allocated()), "&7Gastado: &f" + Money.fmt(b.currency(), b.spent()),
                    "&7Disponible: &f" + Money.fmt(b.currency(), b.left()), Fmt.bar(ratio, 20), "&aIzq: cambiar asignación", "&eDer: reiniciar gasto", "&cShift+Der: eliminar");
        }, (b, ci) -> {
            if (ci.shift() && ci.right()) ConfirmMenu.ask(pl, p, "&cEliminar presupuesto", List.of("&7" + b.name()), () -> act(p, svc.deleteBudget(p, b.id()), "saved", self), self);
            else if (ci.right()) act(p, svc.setBudget(p, b.id(), b.allocated(), true), "saved", self);
            else Prompt.ask(p, "Nueva asignación", s -> {
                BigDecimal v = Money.parse(s);
                if (v == null) { Msg.send(p, "invalid-amount"); self.run(); return; }
                act(p, svc.setBudget(p, b.id(), v, false), "saved", self);
            }, self);
        }).extras(() -> List.of(Btn.of(47, Items.of(Material.EMERALD, "&aNuevo presupuesto"), () ->
                withCur(Form.of(p).ask("name", "Nombre").ask("amt", "Asignación").onCancel(self)).then(a -> {
                    BigDecimal v = Money.parse(a.get("amt"));
                    String c = cur(a);
                    if (v == null || c == null) { Msg.send(p, "invalid-amount"); self.run(); return; }
                    act(p, svc.createBudget(p, a.get("name"), c, v), "saved", self);
                })))).back(back).load();
    }

    // ---------- subsidies ----------
    private void subsidies(Player p, Runnable back) {
        Runnable self = () -> subsidies(p, back);
        new PagedMenu<Subsidy>(pl, p, "&dSubsidios", svc::subsidies, s ->
                Items.of(s.active() ? Material.LIME_CONCRETE : Material.GRAY_CONCRETE, "&e" + s.name(), "&7Importe: &f" + Money.fmt(s.currency(), s.amount()), "&7Cada: &f" + s.intervalMin() + " min",
                        "&7Presupuesto: &f" + (s.budgetId() > 0 ? "#" + s.budgetId() : "ninguno"), "&7Próximo: &f" + Fmt.duration(s.lastPaid() + s.intervalMin() * 60_000L - System.currentTimeMillis()),
                        "&aIzq: activar/pausar", "&cShift+Der: eliminar"),
                (s, ci) -> {
                    if (ci.shift() && ci.right()) ConfirmMenu.ask(pl, p, "&cEliminar subsidio", List.of("&7" + s.name()), () -> act(p, svc.deleteSubsidy(p, s.id()), "saved", self), self);
                    else act(p, svc.toggleSubsidy(s.id()), null, self);
                }).extras(() -> List.of(Btn.of(47, Items.of(Material.EMERALD, "&aNuevo subsidio"), () ->
                withCur(Form.of(p).ask("type", "Destino (PLAYER, COMPANY o BANK)").ask("target", "Jugador / id de empresa / id de cuenta").ask("amt", "Importe por pago")
                        .ask("min", "Intervalo en minutos").ask("budget", "Id de presupuesto (0 = ninguno)").onCancel(self)).then(a -> {
                    BigDecimal v = Money.parse(a.get("amt"));
                    Integer mi = parseInt(a.get("min"));
                    Long b = parseLong(a.get("budget"));
                    String c = cur(a);
                    if (v == null || mi == null || b == null || c == null) { Msg.send(p, "invalid-number"); self.run(); return; }
                    act(p, svc.createSubsidy(p, a.get("type").toUpperCase(), a.get("target"), c, v, mi, b), "saved", self);
                })))).back(back).load();
    }

    // ---------- rewards ----------
    private void rewards(Player p, Runnable back) {
        Runnable self = () -> rewards(p, back);
        new PagedMenu<Reward>(pl, p, "&aRecompensas", svc::rewards, r ->
                Items.of(r.active() ? Material.DIAMOND : Material.COAL, "&e" + r.name(), "&7Evento: &f" + r.trigger() + " &7(" + r.key() + ")", "&7Pago: &f" + Money.fmt(r.currency(), r.amount()),
                        "&7Origen: &f" + r.source(), "&7Enfriamiento: &f" + r.cooldown() + "s", "&aIzq: activar/pausar", "&cShift+Der: eliminar"),
                (r, ci) -> {
                    if (ci.shift() && ci.right()) ConfirmMenu.ask(pl, p, "&cEliminar recompensa", List.of("&7" + r.name()), () -> act(p, svc.deleteReward(p, r.id()), "saved", self), self);
                    else act(p, svc.toggleReward(r.id()), null, self);
                }).extras(() -> List.of(Btn.of(47, Items.of(Material.EMERALD, "&aNueva recompensa", "&7JOIN, KILL_MOB (ZOMBIE...), BREAK (STONE...)"), () ->
                withCur(Form.of(p).ask("name", "Nombre").ask("trigger", "Evento (JOIN, KILL_MOB, BREAK)").ask("key", "Clave (ej. ZOMBIE, STONE o *)").ask("amt", "Importe")
                        .ask("source", "Origen (TREASURY o SYSTEM)").ask("cd", "Enfriamiento en segundos").onCancel(self)).then(a -> {
                    BigDecimal v = Money.parse(a.get("amt"));
                    Integer cd = parseInt(a.get("cd"));
                    String c = cur(a);
                    String src = a.get("source").toUpperCase();
                    if (v == null || cd == null || c == null || !(src.equals("TREASURY") || src.equals("SYSTEM"))) { Msg.send(p, "invalid-number"); self.run(); return; }
                    act(p, svc.createReward(p, a.get("name"), a.get("trigger").toUpperCase(), a.get("key"), c, v, src, cd), "saved", self);
                })))).back(back).load();
    }
}
