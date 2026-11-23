package com.economycore.eco;

import com.economycore.core.Acct;
import com.economycore.core.Audit;
import com.economycore.core.Currency;
import com.economycore.core.EcoException;
import com.economycore.core.EconomyCore;
import com.economycore.core.Fmt;
import com.economycore.core.Inv;
import com.economycore.core.Ledger;
import com.economycore.core.Mailbox;
import com.economycore.core.Money;
import com.economycore.core.Msg;
import com.economycore.core.Players;
import com.economycore.core.Sql;
import com.economycore.core.Tasks;
import com.economycore.eco.EconomyService.AuditRow;
import com.economycore.eco.EconomyService.Top;
import com.economycore.eco.EconomyService.TxRow;
import com.economycore.gui.Btn;
import com.economycore.gui.ButtonMenu;
import com.economycore.gui.ConfirmMenu;
import com.economycore.gui.EditMenu;
import com.economycore.gui.Form;
import com.economycore.gui.Items;
import com.economycore.gui.PagedMenu;
import com.economycore.gui.Prompt;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

public final class EconomyMenus {
    private final EconomyCore pl;
    private final EconomyService svc;

    public EconomyMenus(EconomyCore pl, EconomyService svc) {
        this.pl = pl;
        this.svc = svc;
    }

    // ---------- helpers ----------
    public void doPay(Player p, String to, String cur, BigDecimal amt) {
        svc.pay(p, to, cur, amt).whenComplete((r, t) -> Tasks.sync(() -> {
            if (t != null) { Msg.error(p, t); return; }
            Msg.send(p, "pay-sent", Money.fmt(r.currency(), r.amount()), r.toName(), Money.fmt(r.currency(), r.fee().add(r.tax())));
        }));
    }

    public void showBalance(Player p) {
        Msg.send(p, "balance-header");
        for (Currency c : pl.currencies().all()) p.sendMessage(Msg.raw("balance-line", c.name(), Money.fmt(c.id(), Ledger.cached(p.getUniqueId(), c.id()))));
    }

    private ItemStack skull(UUID id, String name, List<String> lore) {
        ItemStack it = Items.of(Material.PLAYER_HEAD, name, lore);
        if (it.getItemMeta() instanceof SkullMeta sm) {
            sm.setOwningPlayer(Bukkit.getOfflinePlayer(id));
            it.setItemMeta(sm);
        }
        return it;
    }

    private String curId(String text) {
        Currency c = pl.currencies().get(text);
        return c == null ? null : c.id();
    }

    // ---------- main menu ----------
    public void main(Player p, Runnable back) {
        boolean admin = p.hasPermission("economycore.admin");
        new ButtonMenu(pl, p, "&6&lEconomía", admin ? 5 : 4, true, () -> {
            List<Btn> l = new ArrayList<>();
            List<String> lore = new ArrayList<>();
            for (Currency c : pl.currencies().all()) lore.add("&7" + c.name() + ": &f" + Money.fmt(c.id(), Ledger.cached(p.getUniqueId(), c.id())));
            l.add(Btn.of(10, Items.of(Material.GOLD_INGOT, "&6Tu balance", lore), () -> main(p, back)));
            l.add(Btn.of(12, Items.of(Material.EMERALD, "&aPagar a un jugador", "&7Transfiere dinero", "&eClic para pagar"), () -> payForm(p, () -> main(p, back))));
            l.add(Btn.of(14, Items.of(Material.BOOK, "&bHistorial", "&7Tus últimas transacciones"), () -> history(p, () -> main(p, back))));
            l.add(Btn.of(16, Items.of(Material.DIAMOND, "&bRanking de riqueza", "&7Los jugadores más ricos"), () -> top(p, pl.currencies().defId(), () -> main(p, back))));
            l.add(Btn.of(22, Items.of(Material.CHEST, "&eBuzón", "&7Objetos que te debemos entregar"), () -> mailbox(p, () -> main(p, back))));
            if (admin) {
                l.add(Btn.of(28, Items.of(Material.SUNFLOWER, "&6Monedas", "&7Crear y editar monedas"), () -> currencies(p, () -> main(p, back))));
                l.add(Btn.of(29, Items.of(Material.PAPER, "&6Impuestos", "&7Tramos progresivos y exenciones"), () -> taxes(p, () -> main(p, back))));
                l.add(Btn.of(30, Items.of(Material.WRITABLE_BOOK, "&6Auditoría", "&7Acciones administrativas"), () -> audit(p, () -> main(p, back))));
                l.add(Btn.of(31, Items.of(Material.MAP, "&6Libro de transacciones", "&7Todas las transacciones"), () -> txLog(p, () -> main(p, back))));
                l.add(Btn.of(32, Items.of(Material.PLAYER_HEAD, "&6Administrar jugador", "&7Dar, quitar o fijar saldo"), () ->
                        Prompt.ask(p, "Nombre del jugador", n -> playerAdmin(p, n, () -> main(p, back)), () -> main(p, back))));
                l.add(Btn.of(33, Items.of(Material.ENDER_CHEST, "&6Backup ahora", "&7Crea una copia de seguridad"), () ->
                        pl.db().backup("economy-manual").whenComplete((f, t) -> Tasks.sync(() -> {
                            if (t != null) Msg.send(p, "backup-failed"); else Msg.send(p, "backup-done", f.getName());
                        }))));
                l.add(Btn.of(34, Items.of(Material.REDSTONE, "&6Recargar configuración", "&7config.yml y messages.yml"), () -> { pl.reloadAll(); Msg.send(p, "reloaded"); }));
            }
            return l;
        }).back(back).open();
    }

    public void payForm(Player p, Runnable back) {
        Form f = Form.of(p).ask("to", "Nombre del jugador a pagar").ask("amt", "Cantidad (acepta 1k, 2.5m)").onCancel(back);
        boolean multi = pl.currencies().all().size() > 1;
        if (multi) {
            StringBuilder ids = new StringBuilder();
            for (Currency c : pl.currencies().all()) ids.append(c.id()).append(' ');
            f.ask("cur", "Moneda (" + ids.toString().trim() + ")");
        }
        f.then(a -> {
            BigDecimal amt = Money.parse(a.get("amt"));
            String cur = multi ? curId(a.get("cur")) : pl.currencies().defId();
            if (amt == null || amt.signum() <= 0) { Msg.send(p, "invalid-amount"); return; }
            if (cur == null) { Msg.send(p, "unknown-currency", a.get("cur")); return; }
            doPay(p, a.get("to"), cur, amt);
        });
    }

    // ---------- history / top / mailbox ----------
    private ItemStack txIcon(TxRow r, UUID viewer) {
        String v = viewer == null ? "" : viewer.toString();
        boolean in = r.toId().equals(v);
        boolean out = r.fromId().equals(v);
        String from = r.fromName() != null ? r.fromName() : r.fromType().toLowerCase() + ":" + (r.fromId().length() > 8 ? r.fromId().substring(0, 8) : r.fromId());
        String to = r.toName() != null ? r.toName() : r.toType().toLowerCase() + ":" + (r.toId().length() > 8 ? r.toId().substring(0, 8) : r.toId());
        String sign = viewer == null ? "&e" : (in ? "&a+" : out ? "&c-" : "&7");
        List<String> lore = new ArrayList<>();
        lore.add("&7De: &f" + from);
        lore.add("&7Para: &f" + to);
        lore.add("&7Monto: " + sign + Money.fmt(r.currency(), r.amount()));
        if (r.fee().signum() > 0) lore.add("&7Comisión: &f" + Money.fmt(r.currency(), r.fee()));
        if (r.tax().signum() > 0) lore.add("&7Impuesto: &f" + Money.fmt(r.currency(), r.tax()));
        if (r.note() != null && !r.note().isEmpty()) lore.add("&7Nota: &f" + r.note());
        lore.add("&8" + Fmt.dateFull(r.ts()) + " #" + r.id());
        return Items.of(in ? Material.LIME_DYE : out ? Material.RED_DYE : Material.PAPER, "&f" + r.type(), lore);
    }

    public void history(Player p, Runnable back) {
        new PagedMenu<TxRow>(pl, p, "&6Historial", () -> svc.history(p.getUniqueId(), 500), r -> txIcon(r, p.getUniqueId()), (r, c) -> {})
                .searchable(r -> r.type() + " " + r.fromName() + " " + r.toName() + " " + r.note()).back(back).load();
    }

    public void txLog(Player p, Runnable back) {
        new PagedMenu<TxRow>(pl, p, "&6Libro de transacciones", () -> svc.recent(1000), r -> txIcon(r, null), (r, c) -> {})
                .searchable(r -> r.type() + " " + r.fromName() + " " + r.toName() + " " + r.currency() + " " + r.note()).back(back).load();
    }

    public void audit(Player p, Runnable back) {
        new PagedMenu<AuditRow>(pl, p, "&6Auditoría", () -> svc.audit(1000),
                r -> Items.of(Material.WRITABLE_BOOK, "&e" + r.action(), "&7Actor: &f" + r.actor(), "&7Objetivo: &f" + r.target(), "&7" + r.detail(), "&8" + Fmt.dateFull(r.ts())), (r, c) -> {})
                .searchable(r -> r.action() + " " + r.actor() + " " + r.target() + " " + r.detail()).back(back).load();
    }

    public void top(Player p, String cur, Runnable back) {
        List<Currency> cs = new ArrayList<>(pl.currencies().all());
        new PagedMenu<Top>(pl, p, "&6Ranking: " + pl.currencies().getOrDefault(cur).name(), () -> svc.top(cur, 100), t -> skull(UUID.fromString(t.uuid()), "&e" + t.name(), List.of("&7Saldo: &f" + Money.fmt(cur, t.amount()))), (t, c) -> {})
                .searchable(Top::name)
                .extras(() -> List.of(Btn.of(47, Items.of(Material.SUNFLOWER, "&eCambiar moneda", "&7Actual: &f" + cur), () -> {
                    int i = 0;
                    for (int k = 0; k < cs.size(); k++) if (cs.get(k).id().equals(cur)) i = k;
                    top(p, cs.get((i + 1) % cs.size()).id(), back);
                })))
                .back(back).load();
    }

    public void mailbox(Player p, Runnable back) {
        PagedMenu<Mailbox.Row> m = new PagedMenu<>(pl, p, "&6Buzón", () -> pl.db().tx(tx -> Mailbox.list(tx, p.getUniqueId(), 500)),
                r -> Items.withLore(Inv.decodeOr(r.itemData(), null), "&7Cantidad: &f" + r.amount(), "&7Origen: &f" + r.source(), "&eClic para reclamar todo"), (r, c) -> {});
        m.extras(() -> List.of(Btn.of(47, Items.of(Material.HOPPER, "&aReclamar todo", "&7Mueve todo a tu inventario"), () -> {
            Mailbox.deliver(p);
            Tasks.later(20L, m::load);
        })));
        m.back(back).load();
    }

    // ---------- admin: players ----------
    public void playerAdmin(Player p, String name, Runnable back) {
        PagedMenu<Map.Entry<String, BigDecimal>> m = new PagedMenu<>(pl, p, "&6Jugador: " + name,
                () -> svc.balancesByName(name).thenApply(mp -> new ArrayList<>(mp.entrySet())),
                e -> Items.of(Material.GOLD_INGOT, "&e" + pl.currencies().getOrDefault(e.getKey()).name(), "&7Saldo: &f" + Money.fmt(e.getKey(), e.getValue()),
                        "&aClic izq: dar", "&cClic der: quitar", "&bShift+clic: fijar saldo"), (e, ci) -> {
            String op = ci.shift() ? "set" : ci.left() ? "give" : "take";
            Prompt.ask(p, "Cantidad a " + (op.equals("give") ? "dar" : op.equals("take") ? "quitar" : "fijar"), s -> {
                BigDecimal amt = Money.parse(s);
                if (amt == null) { Msg.send(p, "invalid-amount"); playerAdmin(p, name, back); return; }
                svc.admin(p.getName(), op, name, e.getKey(), amt).whenComplete((r, t) -> Tasks.sync(() -> {
                    if (t != null) Msg.error(p, t); else Msg.send(p, "admin-balance-done", name, Money.fmt(e.getKey(), r));
                    playerAdmin(p, name, back);
                }));
            }, () -> playerAdmin(p, name, back));
        });
        m.back(back).load();
    }

    // ---------- admin: currencies ----------
    public void currencies(Player p, Runnable back) {
        new PagedMenu<Currency>(pl, p, "&6Monedas", () -> CompletableFuture.completedFuture(new ArrayList<>(pl.currencies().all())),
                c -> Items.of(Material.SUNFLOWER, "&e" + c.name() + (c.isDefault() ? " &a(por defecto)" : ""), "&7ID: &f" + c.id(), "&7Símbolo: &f" + c.symbol(), "&7Decimales: &f" + c.decimals(),
                        "&7Saldo inicial: &f" + c.startBalance().toPlainString(), "&7Transferible: &f" + c.transferable(), "&eClic para editar"),
                (c, ci) -> editCurrency(p, c.id(), () -> currencies(p, back)))
                .extras(() -> List.of(Btn.of(47, Items.of(Material.EMERALD, "&aNueva moneda", "&7Monedas ilimitadas"), () ->
                        Form.of(p).ask("id", "ID de la moneda (sin espacios, ej: gems)").ask("name", "Nombre visible").ask("sym", "Símbolo (ej: $, ✦)").ask("dec", "Decimales (0-8)")
                                .onCancel(() -> currencies(p, back)).then(a -> {
                                    String id = a.get("id").toLowerCase().replaceAll("[^a-z0-9_]", "");
                                    int dec;
                                    try { dec = Math.max(0, Math.min(8, Integer.parseInt(a.get("dec")))); } catch (NumberFormatException ex) { Msg.send(p, "invalid-number"); return; }
                                    if (id.isEmpty() || pl.currencies().exists(id)) { Msg.send(p, "currency-exists"); return; }
                                    pl.currencies().save(new Currency(id, a.get("name"), a.get("sym"), dec, BigDecimal.ZERO, true, false, "&e"), p.getName())
                                            .whenComplete((r, t) -> Tasks.sync(() -> { if (t != null) Msg.error(p, t); else Msg.send(p, "saved"); currencies(p, back); }));
                                }))))
                .back(back).load();
    }

    private void editCurrency(Player p, String id, Runnable back) {
        Currency c = pl.currencies().get(id);
        if (c == null) { back.run(); return; }
        Runnable reload = () -> editCurrency(p, id, back);
        EditMenu m = new EditMenu(pl, p, "&6Moneda: " + c.name(), reload);
        m.field(Material.NAME_TAG, "Nombre", c.name(), "Nuevo nombre", s -> pl.currencies().save(new Currency(id, s, c.symbol(), c.decimals(), c.startBalance(), c.transferable(), c.isDefault(), c.color()), p.getName()));
        m.field(Material.GOLD_NUGGET, "Símbolo", c.symbol(), "Nuevo símbolo", s -> pl.currencies().save(new Currency(id, c.name(), s, c.decimals(), c.startBalance(), c.transferable(), c.isDefault(), c.color()), p.getName()));
        m.field(Material.COMPARATOR, "Decimales", String.valueOf(c.decimals()), "Decimales (0-8)", s -> {
            try {
                int d = Math.max(0, Math.min(8, Integer.parseInt(s.trim())));
                return pl.currencies().save(new Currency(id, c.name(), c.symbol(), d, c.startBalance(), c.transferable(), c.isDefault(), c.color()), p.getName());
            } catch (NumberFormatException e) { return CompletableFuture.failedFuture(new EcoException("invalid-number")); }
        });
        m.field(Material.EMERALD, "Saldo inicial", c.startBalance().toPlainString(), "Saldo inicial para nuevos jugadores", s -> {
            BigDecimal v = Money.parse(s);
            if (v == null || v.signum() < 0) return CompletableFuture.failedFuture(new EcoException("invalid-amount"));
            return pl.currencies().save(new Currency(id, c.name(), c.symbol(), c.decimals(), v, c.transferable(), c.isDefault(), c.color()), p.getName());
        });
        m.field(Material.CYAN_DYE, "Color", c.color(), "Código de color (ej: &e, &a, &b)", s -> pl.currencies().save(new Currency(id, c.name(), c.symbol(), c.decimals(), c.startBalance(), c.transferable(), c.isDefault(), s.trim()), p.getName()));
        m.toggle(Material.LIME_DYE, "Transferible", c.transferable(), () -> pl.currencies().save(new Currency(id, c.name(), c.symbol(), c.decimals(), c.startBalance(), !c.transferable(), c.isDefault(), c.color()), p.getName()));
        m.action(Material.NETHER_STAR, "&aEstablecer por defecto", List.of("&7Moneda principal del servidor"), ci ->
                pl.currencies().save(new Currency(id, c.name(), c.symbol(), c.decimals(), c.startBalance(), c.transferable(), true, c.color()), p.getName()).whenComplete((r, t) -> Tasks.sync(reload)));
        m.action(Material.TNT, "&cEliminar moneda", List.of("&7Solo si nadie tiene saldo"), ci ->
                ConfirmMenu.ask(pl, p, "&cEliminar " + c.name(), List.of("&7Se eliminará la moneda", "&7" + id), () ->
                        pl.currencies().delete(id, p.getName()).whenComplete((r, t) -> Tasks.sync(() -> { if (t != null) Msg.error(p, t); else Msg.send(p, "saved"); back.run(); })), reload));
        m.back(back).open();
    }

    // ---------- admin: taxes ----------
    private record BracketRow(long id, String kind, String cur, BigDecimal min, BigDecimal rate) {}
    private record ExRow(long id, String subject, String name, String kind) {}

    public void taxes(Player p, Runnable back) {
        new ButtonMenu(pl, p, "&6Impuestos", 3, true, () -> {
            List<Btn> l = new ArrayList<>();
            int s = 10;
            String[] names = {"Ventas", "Ingresos", "Transferencias", "Patrimonio"};
            Material[] mats = {Material.EMERALD, Material.GOLD_INGOT, Material.PAPER, Material.DIAMOND};
            for (int i = 0; i < Taxes_KINDS.length; i++) {
                String k = Taxes_KINDS[i];
                l.add(Btn.of(s + i, Items.of(mats[i], "&e" + names[i] + " &7(" + k + ")", "&7Tramos progresivos", "&eClic para administrar"), () -> taxKind(p, k, () -> taxes(p, back))));
            }
            l.add(Btn.of(16, Items.of(Material.SHIELD, "&bExenciones", "&7Jugadores exentos de impuestos"), () -> exemptions(p, () -> taxes(p, back))));
            return l;
        }).back(back).open();
    }

    private static final String[] Taxes_KINDS = com.economycore.core.Taxes.KINDS;

    private void taxKind(Player p, String kind, Runnable back) {
        new PagedMenu<BracketRow>(pl, p, "&6Impuesto " + kind, () -> pl.db().tx(tx -> Sql.list(tx, "SELECT * FROM tax_brackets WHERE kind=? ORDER BY currency, CAST(min_amount AS REAL)",
                rs -> new BracketRow(rs.getLong("id"), rs.getString("kind"), rs.getString("currency"), Money.dec(rs.getString("min_amount"), BigDecimal.ZERO), Money.dec(rs.getString("rate"), BigDecimal.ZERO)), kind)),
                b -> Items.of(Material.PAPER, "&eDesde " + b.min().toPlainString() + " &7-> &c" + b.rate().toPlainString() + "%", "&7Moneda: &f" + b.cur(), "&aClic izq: cambiar tasa", "&cClic der: eliminar"),
                (b, ci) -> {
                    if (ci.right()) {
                        ConfirmMenu.ask(pl, p, "&cEliminar tramo", List.of("&7Desde " + b.min().toPlainString()), () ->
                                EditMenu.update("DELETE FROM tax_brackets WHERE id=?", b.id()).whenComplete((r, t) -> Tasks.sync(() -> taxKind(p, kind, back))), () -> taxKind(p, kind, back));
                    } else {
                        Prompt.ask(p, "Nueva tasa en % (ej: 5)", s -> {
                            BigDecimal v = Money.parse(s);
                            if (v == null || v.signum() < 0 || v.compareTo(BigDecimal.valueOf(100)) > 0) { Msg.send(p, "invalid-number"); taxKind(p, kind, back); return; }
                            EditMenu.update("UPDATE tax_brackets SET rate=? WHERE id=?", v, b.id()).whenComplete((r, t) -> Tasks.sync(() -> taxKind(p, kind, back)));
                        }, () -> taxKind(p, kind, back));
                    }
                })
                .extras(() -> List.of(Btn.of(47, Items.of(Material.EMERALD, "&aNuevo tramo", "&7Se aplica por tramos marginales"), () ->
                        Form.of(p).ask("cur", "Moneda (id o * para todas)").ask("min", "Monto mínimo del tramo (ej: 0, 1000)").ask("rate", "Tasa % del tramo")
                                .onCancel(() -> taxKind(p, kind, back)).then(a -> {
                                    BigDecimal min = Money.parse(a.get("min"));
                                    BigDecimal rate = Money.parse(a.get("rate"));
                                    String cur = a.get("cur").equals("*") ? "*" : curId(a.get("cur"));
                                    if (min == null || rate == null || cur == null || rate.compareTo(BigDecimal.valueOf(100)) > 0) { Msg.send(p, "invalid-number"); taxKind(p, kind, back); return; }
                                    pl.db().tx(tx -> {
                                        Sql.exec(tx, "INSERT INTO tax_brackets(kind,currency,min_amount,rate) VALUES(?,?,?,?)", kind, cur, min, rate);
                                        Audit.log(tx, p.getName(), "tax.bracket.add", kind, cur + " " + min + " " + rate + "%");
                                        return null;
                                    }).whenComplete((r, t) -> Tasks.sync(() -> taxKind(p, kind, back)));
                                })),
                        Btn.of(51, Items.of(Material.CHEST, "&bRecaudado en tesorería"), () -> {
                            for (Currency c : pl.currencies().all()) {
                                pl.db().tx(tx -> Ledger.bal(tx, Acct.TREASURY, c.id())).thenAccept(b -> Tasks.sync(() -> p.sendMessage(Msg.get("treasury-balance", c.name(), Money.fmt(c.id(), b)))));
                            }
                        })))
                .back(back).load();
    }

    private void exemptions(Player p, Runnable back) {
        new PagedMenu<ExRow>(pl, p, "&6Exenciones fiscales", () -> pl.db().tx(tx -> Sql.list(tx, "SELECT e.id,e.subject,COALESCE(p.name,e.subject) AS name,e.kind FROM tax_exemptions e LEFT JOIN players p ON p.uuid=e.subject ORDER BY e.id",
                rs -> new ExRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)))),
                e -> Items.of(Material.SHIELD, "&e" + e.name(), "&7Impuesto: &f" + e.kind(), "&cClic para quitar"),
                (e, ci) -> EditMenu.update("DELETE FROM tax_exemptions WHERE id=?", e.id()).whenComplete((r, t) -> Tasks.sync(() -> exemptions(p, back))))
                .searchable(ExRow::name)
                .extras(() -> List.of(Btn.of(47, Items.of(Material.EMERALD, "&aNueva exención", "&7Jugador exento de un impuesto"), () ->
                        Form.of(p).ask("who", "Nombre del jugador (o * para todos)").ask("kind", "Tipo: SALES, INCOME, TRANSFER, WEALTH o *")
                                .onCancel(() -> exemptions(p, back)).then(a -> {
                                    String kind = a.get("kind").toUpperCase();
                                    pl.db().tx(tx -> {
                                        String subj = "*";
                                        if (!a.get("who").equals("*")) subj = Players.require(tx, a.get("who")).uuid().toString();
                                        Sql.exec(tx, "INSERT OR IGNORE INTO tax_exemptions(subject,kind,note) VALUES(?,?,?)", subj, kind, p.getName());
                                        Audit.log(tx, p.getName(), "tax.exempt.add", a.get("who"), kind);
                                        return null;
                                    }).whenComplete((r, t) -> Tasks.sync(() -> { if (t != null) Msg.error(p, t); exemptions(p, back); }));
                                }))))
                .back(back).load();
    }
}
