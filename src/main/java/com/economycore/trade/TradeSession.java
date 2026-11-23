package com.economycore.trade;

import com.economycore.core.Acct;
import com.economycore.core.EconomyCore;
import com.economycore.core.Inv;
import com.economycore.core.Ledger;
import com.economycore.core.Mailbox;
import com.economycore.core.Money;
import com.economycore.core.Msg;
import com.economycore.core.Sql;
import com.economycore.core.Tasks;
import com.economycore.gui.Items;
import com.economycore.gui.ClickInfo;
import com.economycore.gui.Menu;
import com.economycore.gui.Prompt;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Bilateral trade. Items stay in the players' inventories during negotiation (offers are only references);
 * at execution everything is re-verified, removed and delivered through the durable mailbox in one DB transaction.
 */
public final class TradeSession {
    static final int[] MINE = slots(0), THEIRS = slots(5);
    static final int MAX_ITEMS = 20;

    private static int[] slots(int col) {
        int[] s = new int[20];
        for (int r = 0; r < 5; r++) for (int c = 0; c < 4; c++) s[r * 4 + c] = r * 9 + col + c;
        return s;
    }

    private final EconomyCore pl;
    private final TradeModule mod;
    final Player a, b;
    final List<ItemStack> itemsA = new ArrayList<>(), itemsB = new ArrayList<>();
    BigDecimal moneyA = BigDecimal.ZERO, moneyB = BigDecimal.ZERO;
    String curA, curB;
    boolean acceptA, acceptB, confirmA, confirmB, locked, finished;
    long lockedAt;
    final long created = System.currentTimeMillis();
    private final TradeMenu menuA, menuB;

    TradeSession(EconomyCore pl, TradeModule mod, Player a, Player b) {
        this.pl = pl;
        this.mod = mod;
        this.a = a;
        this.b = b;
        this.curA = pl.currencies().defId();
        this.curB = pl.currencies().defId();
        this.menuA = new TradeMenu(a, true);
        this.menuB = new TradeMenu(b, false);
    }

    void start() {
        menuA.open();
        menuB.open();
    }

    boolean involves(Player p) { return p.getUniqueId().equals(a.getUniqueId()) || p.getUniqueId().equals(b.getUniqueId()); }

    private boolean isA(Player p) { return p.getUniqueId().equals(a.getUniqueId()); }

    long confirmDelayMs() { return pl.getConfig().getLong("trade.confirm-delay-seconds", 3) * 1000L; }

    void refresh() {
        menuA.refresh();
        menuB.refresh();
    }

    private void resetAccept() {
        acceptA = false;
        acceptB = false;
    }

    void offerItem(Player who, ItemStack stack) {
        if (finished || locked) return;
        List<ItemStack> l = isA(who) ? itemsA : itemsB;
        if (l.size() >= MAX_ITEMS) { Msg.send(who, "trade-max-items"); return; }
        int already = 0;
        for (ItemStack s : l) if (s.isSimilar(stack)) already += s.getAmount();
        if (already + stack.getAmount() > Inv.count(who, stack)) { Msg.send(who, "trade-item-already"); return; }
        l.add(stack.clone());
        resetAccept();
        refresh();
    }

    void removeItem(Player who, int idx) {
        if (finished || locked) return;
        List<ItemStack> l = isA(who) ? itemsA : itemsB;
        if (idx >= 0 && idx < l.size()) l.remove(idx);
        resetAccept();
        refresh();
    }

    void setMoney(Player who, BigDecimal amt, String cur) {
        if (finished || locked) return;
        if (isA(who)) { moneyA = amt; curA = cur; } else { moneyB = amt; curB = cur; }
        resetAccept();
        refresh();
    }

    void toggleAccept(Player who) {
        if (finished || locked) return;
        BigDecimal m = isA(who) ? moneyA : moneyB;
        String c = isA(who) ? curA : curB;
        if (m.signum() > 0 && Ledger.cached(who.getUniqueId(), c).compareTo(m) < 0) { Msg.send(who, "insufficient-funds"); return; }
        if (isA(who)) acceptA = !acceptA; else acceptB = !acceptB;
        if (acceptA && acceptB) { locked = true; lockedAt = System.currentTimeMillis(); }
        refresh();
        if (locked) Tasks.later(confirmDelayMs() / 50L + 2L, this::refresh);
    }

    void confirm(Player who) {
        if (finished || !locked) return;
        if (System.currentTimeMillis() - lockedAt < confirmDelayMs()) { Msg.send(who, "trade-wait"); return; }
        if (isA(who)) confirmA = true; else confirmB = true;
        refresh();
        if (confirmA && confirmB) execute();
    }

    void cancel(String key) {
        if (finished) return;
        finished = true;
        mod.end(this);
        Msg.send(a, key);
        Msg.send(b, key);
        a.closeInventory();
        b.closeInventory();
    }

    private static boolean has(Player p, List<ItemStack> l) {
        for (ItemStack s : l) {
            int total = 0;
            for (ItemStack o : l) if (o.isSimilar(s)) total += o.getAmount();
            if (Inv.count(p, s) < total) return false;
        }
        return true;
    }

    static String summary(List<ItemStack> l) {
        if (l.isEmpty()) return "-";
        StringBuilder sb = new StringBuilder();
        for (ItemStack s : l) { if (sb.length() > 0) sb.append(", "); sb.append(s.getAmount()).append("x ").append(Inv.name(s)); }
        return sb.toString();
    }

    private void execute() {
        if (finished) return;
        finished = true;
        mod.end(this);
        if (!a.isOnline() || !b.isOnline() || !has(a, itemsA) || !has(b, itemsB)) {
            Msg.send(a, "trade-items-missing");
            Msg.send(b, "trade-items-missing");
            a.closeInventory();
            b.closeInventory();
            return;
        }
        double maxD = pl.getConfig().getDouble("trade.max-distance", 0);
        if (maxD > 0 && (!a.getWorld().equals(b.getWorld()) || a.getLocation().distance(b.getLocation()) > maxD)) {
            Msg.send(a, "trade-too-far");
            Msg.send(b, "trade-too-far");
            a.closeInventory();
            b.closeInventory();
            return;
        }
        List<ItemStack> ia = new ArrayList<>(), ib = new ArrayList<>();
        for (ItemStack s : itemsA) { Inv.take(a, s, s.getAmount()); ia.add(s.clone()); }
        for (ItemStack s : itemsB) { Inv.take(b, s, s.getAmount()); ib.add(s.clone()); }
        BigDecimal mA = moneyA, mB = moneyB;
        String cA = curA, cB = curB;
        String nameA = a.getName(), nameB = b.getName();
        var idA = a.getUniqueId();
        var idB = b.getUniqueId();
        a.closeInventory();
        b.closeInventory();
        pl.db().tx(tx -> {
            if (mA.signum() > 0) Ledger.transfer(tx, Acct.player(idA), Acct.player(idB), cA, mA, "TRADE", nameA + " -> " + nameB);
            if (mB.signum() > 0) Ledger.transfer(tx, Acct.player(idB), Acct.player(idA), cB, mB, "TRADE", nameB + " -> " + nameA);
            for (ItemStack s : ia) Mailbox.add(tx, idB, s, s.getAmount(), "trade:" + nameA);
            for (ItemStack s : ib) Mailbox.add(tx, idA, s, s.getAmount(), "trade:" + nameB);
            Sql.exec(tx, "INSERT INTO trade_log(ts,a,a_name,b,b_name,a_items,b_items,a_money,b_money,currency,status) VALUES(?,?,?,?,?,?,?,?,?,?,'COMPLETED')",
                    System.currentTimeMillis(), idA, nameA, idB, nameB, summary(ia), summary(ib), mA, mB, cA + "/" + cB);
            return null;
        }).whenComplete((r, t) -> Tasks.sync(() -> {
            if (t != null) {
                pl.db().tx(tx -> {
                    for (ItemStack s : ia) Mailbox.add(tx, idA, s, s.getAmount(), "trade-refund");
                    for (ItemStack s : ib) Mailbox.add(tx, idB, s, s.getAmount(), "trade-refund");
                    return null;
                });
                Msg.error(a, t);
                Msg.error(b, t);
            } else {
                Msg.send(a, "trade-complete", nameB);
                Msg.send(b, "trade-complete", nameA);
            }
        }));
    }

    /** One participant's view of the trade. */
    final class TradeMenu extends Menu {
        private final boolean sideA;
        private boolean suppressClose;

        TradeMenu(Player viewer, boolean sideA) {
            super(pl, viewer, "&6Intercambio: " + (sideA ? b.getName() : a.getName()), 6);
            this.sideA = sideA;
        }

        @Override
        protected void render() {
            List<ItemStack> mine = sideA ? itemsA : itemsB;
            List<ItemStack> theirs = sideA ? itemsB : itemsA;
            BigDecimal mMoney = sideA ? moneyA : moneyB, tMoney = sideA ? moneyB : moneyA;
            String mCur = sideA ? curA : curB, tCur = sideA ? curB : curA;
            boolean mAcc = sideA ? acceptA : acceptB, tAcc = sideA ? acceptB : acceptA;
            boolean mConf = sideA ? confirmA : confirmB, tConf = sideA ? confirmB : confirmA;
            ItemStack div = Items.of(locked ? Material.RED_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE, locked ? "&cOfertas bloqueadas" : "&7");
            for (int r = 0; r < 5; r++) set(r * 9 + 4, div);
            for (int i = 0; i < mine.size(); i++) {
                final int idx = i;
                set(MINE[i], Items.withLore(mine.get(i), locked ? "&7(bloqueado)" : "&cClic para quitar"), c -> removeItem(viewer, idx));
            }
            for (int i = 0; i < theirs.size(); i++) set(THEIRS[i], Items.withLore(theirs.get(i), "&7Oferta de " + (sideA ? b.getName() : a.getName())));
            ItemStack f = Items.filler();
            for (int i = 45; i < 54; i++) set(i, f);
            set(45, Items.of(Material.GOLD_INGOT, "&6Tu dinero: " + Money.fmt(mCur, mMoney), locked ? "&7(bloqueado)" : "&eClic para cambiar"), c -> {
                if (locked || finished) return;
                suppressClose = true;
                Prompt.ask(viewer, "Dinero a ofrecer (0 para quitar)", s -> {
                    suppressClose = false;
                    BigDecimal v = Money.parse(s);
                    if (v == null || v.signum() < 0) { Msg.send(viewer, "invalid-amount"); open(); return; }
                    setMoney(viewer, v, pl.currencies().defId());
                    open();
                }, () -> { suppressClose = false; if (!finished) open(); });
            });
            set(46, Items.of(Material.PAPER, "&7Ofreces " + mine.size() + " objeto(s)", "&7Haz clic en objetos de tu inventario", "&7para añadirlos a la oferta"));
            set(50, Items.of(Material.EMERALD, "&aSu dinero: " + Money.fmt(tCur, tMoney)));
            if (!locked) {
                set(48, Items.of(mAcc ? Material.LIME_CONCRETE : Material.YELLOW_CONCRETE, mAcc ? "&aHas aceptado" : "&eAceptar oferta", "&7Ambos deben aceptar", mAcc ? "&7Clic para retirar" : "&7Clic para aceptar"), c -> toggleAccept(viewer));
            } else {
                boolean ready = System.currentTimeMillis() - lockedAt >= confirmDelayMs();
                set(48, Items.of(mConf ? Material.LIME_CONCRETE : (ready ? Material.EMERALD_BLOCK : Material.ORANGE_CONCRETE), mConf ? "&aConfirmado, esperando al otro" : ready ? "&a&lCONFIRMAR INTERCAMBIO" : "&6Espera...",
                        "&7Revisa bien la oferta final"), c -> confirm(viewer));
            }
            set(49, Items.of(Material.BOOK, "&fEstado", "&7Tú: " + (mConf ? "&aconfirmado" : mAcc ? "&aaceptó" : "&eediting"), "&7El otro: " + (tConf ? "&aconfirmado" : tAcc ? "&aaceptó" : "&eeditando"),
                    locked ? "&cOfertas bloqueadas" : "&7Ofertas abiertas"));
            set(52, Items.of(Material.BARRIER, "&c&lCANCELAR", "&7Cancela el intercambio"), c -> cancel("trade-cancelled"));
        }

        @Override
        public void handleBottom(ClickInfo ci) {
            if (finished || locked || ci.current() == null || ci.current().getType().isAir()) return;
            offerItem(viewer, ci.current());
        }

        @Override
        public void onClose() {
            super.onClose();
            if (!finished && !suppressClose) cancel("trade-cancelled");
        }
    }
}
