package com.economycore.company;

import com.economycore.core.Cmds;
import com.economycore.core.Module;
import com.economycore.core.Msg;
import com.economycore.core.Tasks;
import org.bukkit.entity.Player;

public final class CompanyModule extends Module {
    private CompanyService svc;
    private CompanyMenus menus;

    @Override public String id() { return "company"; }
    @Override public String title() { return "&bEmpresas"; }
    @Override public String icon() { return "GOLD_BLOCK"; }
    @Override public String description() { return "Empresas, nóminas, productos, contabilidad y dividendos"; }

    public CompanyService service() { return svc; }

    @Override
    public void enable() {
        svc = new CompanyService(pl);
        try { svc.seedDefaults(); } catch (Exception e) { pl.getLogger().warning("Default companies: " + e); }
        menus = new CompanyMenus(pl, svc);
        pl.track(Tasks.timer(1200L, 1200L, svc::produce));
        long pay = Math.max(1, pl.getConfig().getLong("companies.payroll-interval-minutes", 60)) * 1200L;
        pl.track(Tasks.timer(pay, pay, svc::payroll));
        long div = Math.max(1, pl.getConfig().getLong("companies.dividend-interval-hours", 24)) * 72000L;
        pl.track(Tasks.timer(div, div, svc::autoDividends));
        Cmds.register(pl, "company", "economycore.use", true, (s, a) -> {
            Player p = (Player) s;
            if (a.length >= 3 && a[0].equalsIgnoreCase("create")) {
                if (!p.hasPermission("economycore.company.create")) { Msg.send(p, "no-permission"); return; }
                svc.create(p, a[1], a[2]).whenComplete((id, t) -> Tasks.sync(() -> {
                    if (t != null) Msg.error(p, t); else menus.detail(p, id, p::closeInventory);
                }));
                return;
            }
            open(p, p::closeInventory);
        }, (s, a) -> a.length == 1 ? Cmds.filter(java.util.List.of("create"), a[0]) : null);
    }

    @Override
    public void open(Player p, Runnable back) { menus.main(p, back); }
}
