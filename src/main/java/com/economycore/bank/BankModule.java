package com.economycore.bank;

import com.economycore.core.Cmds;
import com.economycore.core.Module;
import com.economycore.core.Tasks;
import org.bukkit.entity.Player;

public final class BankModule extends Module {
    private BankMenus menus;

    @Override public String id() { return "bank"; }
    @Override public String title() { return "&6Banco"; }
    @Override public String icon() { return "GOLD_BLOCK"; }
    @Override public String description() { return "Cuentas personales y compartidas con intereses"; }

    @Override
    public void enable() {
        BankService svc = new BankService(pl);
        menus = new BankMenus(pl, svc);
        long min = Math.max(1, pl.getConfig().getLong("bank.interest.interval-minutes", 60));
        pl.track(Tasks.timer(min * 1200L, min * 1200L, svc::payInterest));
        Cmds.register(pl, "bank", "economycore.use", true, (s, a) -> open((Player) s, ((Player) s)::closeInventory));
    }

    @Override
    public void open(Player p, Runnable back) { menus.main(p, back); }
}
