package com.economycore.ah;

import com.economycore.core.Acct;
import com.economycore.core.Audit;
import com.economycore.core.EcoException;
import com.economycore.core.EconomyCore;
import com.economycore.core.Inv;
import com.economycore.core.Ledger;
import com.economycore.core.Mailbox;
import com.economycore.core.Money;
import com.economycore.core.Msg;
import com.economycore.core.Sql;
import com.economycore.core.Tx;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class AuctionService {
    private final EconomyCore pl;
    public static final Acct ESCROW = Acct.escrow("AH");

    public AuctionService(EconomyCore pl) { this.pl = pl; }

    public record Auction(long id, String seller, String sellerName, String itemData, String material, String itemName, int amount, String category,
                          String currency, String type, BigDecimal startPrice, BigDecimal buyNow, BigDecimal currentBid, String bidder, String bidderName,
                          int bidCount, long created, long ends, int extensions, String state) {
        public boolean isBin() { return type.equals("BIN"); }
        public BigDecimal shownPrice() { return isBin() ? startPrice : (currentBid != null ? currentBid : startPrice); }
    }

    public record Filter(String category, String type, String sort, UUID favoritesOf, UUID sellerOnly) {
        public Filter withCategory(String c) { return new Filter(c, type, sort, favoritesOf, sellerOnly); }
        public Filter withType(String t) { return new Filter(category, t, sort, favoritesOf, sellerOnly); }
        public Filter withSort(String s) { return new Filter(category, type, s, favoritesOf, sellerOnly); }
        public Filter withFavorites(UUID u) { return new Filter(category, type, sort, u, sellerOnly); }
    }

    public static final String[] CATEGORIES = {"blocks", "weapons", "armor", "tools", "food", "misc"};
    public static final String[] SORTS = {"ending", "newest", "price_asc", "price_desc"};

    public static String categoryOf(Material m) {
        String n = m.name();
        if (n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS") || n.equals("SHIELD") || n.equals("ELYTRA") || n.equals("TURTLE_HELMET")) return "armor";
        if (n.endsWith("_SWORD") || n.equals("BOW") || n.equals("CROSSBOW") || n.equals("TRIDENT") || n.equals("ARROW") || n.equals("MACE")) return "weapons";
        if (n.endsWith("_PICKAXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE") || n.endsWith("_AXE") || n.equals("FISHING_ROD") || n.equals("SHEARS") || n.equals("FLINT_AND_STEEL")) return "tools";
        if (m.isEdible()) return "food";
        if (m.isBlock()) return "blocks";
        return "misc";
    }

    static Auction map(ResultSet rs) throws SQLException {
        return new Auction(rs.getLong("id"), rs.getString("seller"), rs.getString("seller_name"), rs.getString("item_data"), rs.getString("material"), rs.getString("item_name"),
                rs.getInt("amount"), rs.getString("category"), rs.getString("currency"), rs.getString("type"), Sql.decOr0(rs, "start_price"), Sql.dec(rs, "buy_now"),
                Sql.dec(rs, "current_bid"), rs.getString("bidder"), rs.getString("bidder_name"), rs.getInt("bid_count"), rs.getLong("created"), rs.getLong("ends"),
                rs.getInt("extensions"), rs.getString("state"));
    }

    public CompletableFuture<List<Auction>> list(Filter f) {
        return pl.db().tx(tx -> {
            StringBuilder sql = new StringBuilder("SELECT * FROM auctions WHERE state='ACTIVE' AND ends>?");
            List<Object> p = new ArrayList<>();
            p.add(System.currentTimeMillis());
            if (f.category() != null) { sql.append(" AND category=?"); p.add(f.category()); }
            if (f.type() != null) { sql.append(" AND type=?"); p.add(f.type()); }
            if (f.sellerOnly() != null) { sql.append(" AND seller=?"); p.add(f.sellerOnly().toString()); }
            if (f.favoritesOf() != null) { sql.append(" AND id IN (SELECT auction_id FROM auction_favorites WHERE player=?)"); p.add(f.favoritesOf().toString()); }
            switch (f.sort()) {
                case "newest" -> sql.append(" ORDER BY id DESC");
                case "price_asc" -> sql.append(" ORDER BY CAST(COALESCE(current_bid,start_price) AS REAL) ASC");
                case "price_desc" -> sql.append(" ORDER BY CAST(COALESCE(current_bid,start_price) AS REAL) DESC");
                default -> sql.append(" ORDER BY ends ASC");
            }
            sql.append(" LIMIT 1500");
            return Sql.list(tx, sql.toString(), AuctionService::map, p.toArray());
        });
    }

    public CompletableFuture<Auction> get(long id) {
        return pl.db().tx(tx -> Sql.one(tx, "SELECT * FROM auctions WHERE id=?", AuctionService::map, id));
    }

    public CompletableFuture<Set<Long>> favorites(UUID p) {
        return pl.db().tx(tx -> new HashSet<>(Sql.list(tx, "SELECT auction_id FROM auction_favorites WHERE player=?", rs -> rs.getLong(1), p.toString())));
    }

    public CompletableFuture<Boolean> toggleFavorite(UUID p, long id) {
        return pl.db().tx(tx -> {
            int n = Sql.exec(tx, "DELETE FROM auction_favorites WHERE player=? AND auction_id=?", p, id);
            if (n > 0) return false;
            Sql.exec(tx, "INSERT INTO auction_favorites(player,auction_id) VALUES(?,?)", p, id);
            return true;
        });
    }

    public int maxListings(Player p) {
        int best = pl.getConfig().getInt("auction-house.max-listings", 10);
        for (var pe : p.getEffectivePermissions()) {
            String n = pe.getPermission();
            if (pe.getValue() && n.startsWith("economycore.ah.limit.")) {
                try { best = Math.max(best, Integer.parseInt(n.substring("economycore.ah.limit.".length()))); } catch (NumberFormatException ignored) {}
            }
        }
        return best;
    }

    /** Creates a listing. The item must already be removed from the player's hand; it is returned through the mailbox on failure. */
    public CompletableFuture<Long> create(Player p, ItemStack item, int amount, String type, BigDecimal price, BigDecimal buyNow, int hours, String cur) {
        UUID id = p.getUniqueId();
        String name = p.getName();
        int max = maxListings(p);
        String data = Inv.encode(item);
        String itemName = Inv.name(item);
        String cat = categoryOf(item.getType());
        BigDecimal feePct = Money.dec(pl.getConfig().getString("auction-house.listing-fee-percent", "1"), BigDecimal.ZERO);
        CompletableFuture<Long> f = pl.db().tx(tx -> {
            BigDecimal minP = Money.dec(pl.getConfig().getString("auction-house.min-price", "1"), BigDecimal.ONE);
            BigDecimal maxP = Money.dec(pl.getConfig().getString("auction-house.max-price", "1000000000"), null);
            if (price.compareTo(minP) < 0 || (maxP != null && price.compareTo(maxP) > 0)) throw new EcoException("ah-price-range", Money.plain(cur, minP));
            if (buyNow != null && buyNow.compareTo(price) <= 0) throw new EcoException("ah-buynow-low");
            if (Sql.count(tx, "SELECT COUNT(*) FROM auctions WHERE seller=? AND state='ACTIVE'", id.toString()) >= max) throw new EcoException("ah-limit", max);
            BigDecimal fee = Money.scale(cur, Money.pct(price, feePct));
            if (fee.signum() > 0) Ledger.transfer(tx, Acct.player(id), Acct.TREASURY, cur, fee, "AH_LISTING_FEE", "listing");
            long now = System.currentTimeMillis();
            return Sql.insert(tx, "INSERT INTO auctions(seller,seller_name,item_data,material,item_name,amount,category,currency,type,start_price,buy_now,created,ends,state) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,'ACTIVE')",
                    id, name, data, item.getType().name(), itemName, amount, cat, cur, type, price, buyNow, now, now + hours * 3_600_000L);
        });
        f.whenComplete((r, t) -> { if (t != null) pl.db().tx(tx -> { Mailbox.add(tx, id, item, amount, "ah-return"); return null; }); });
        return f;
    }

    public record BidResult(BigDecimal amount, boolean bought, long endsAt) {}

    public CompletableFuture<BidResult> bid(Player p, long auctionId, BigDecimal amount) {
        UUID id = p.getUniqueId();
        String name = p.getName();
        return pl.db().tx(tx -> {
            Auction a = Sql.one(tx, "SELECT * FROM auctions WHERE id=?", AuctionService::map, auctionId);
            long now = System.currentTimeMillis();
            if (a == null || !a.state().equals("ACTIVE") || a.ends() <= now) throw new EcoException("ah-gone");
            if (a.isBin()) throw new EcoException("ah-not-auction");
            if (a.seller().equals(id.toString())) throw new EcoException("ah-own");
            if (a.buyNow() != null && amount.compareTo(a.buyNow()) >= 0) return doBuy(tx, a, id, name, a.buyNow(), now);
            BigDecimal min = minBid(a);
            if (Money.scale(a.currency(), amount).compareTo(min) < 0) throw new EcoException("ah-bid-low", Money.plain(a.currency(), min));
            BigDecimal amt = Money.scale(a.currency(), amount);
            if (a.bidder() != null) {
                Ledger.transfer(tx, ESCROW, Acct.player(a.bidder()), a.currency(), a.currentBid(), "AH_REFUND", "outbid #" + a.id());
                String prev = a.bidder();
                tx.afterCommit(() -> {
                    Player pp = Bukkit.getPlayer(UUID.fromString(prev));
                    if (pp != null) Msg.send(pp, "ah-outbid", a.itemName(), Money.fmt(a.currency(), amt));
                });
            }
            Ledger.transfer(tx, Acct.player(id), ESCROW, a.currency(), amt, "AH_BID", "bid #" + a.id());
            long ends = a.ends();
            int ext = a.extensions();
            long window = pl.getConfig().getLong("auction-house.anti-snipe.window-seconds", 120) * 1000L;
            long extend = pl.getConfig().getLong("auction-house.anti-snipe.extend-seconds", 120) * 1000L;
            int maxExt = pl.getConfig().getInt("auction-house.anti-snipe.max-extensions", 10);
            if (window > 0 && ends - now <= window && ext < maxExt) { ends += extend; ext++; }
            Sql.exec(tx, "UPDATE auctions SET current_bid=?, bidder=?, bidder_name=?, bid_count=bid_count+1, ends=?, extensions=? WHERE id=?", amt, id, name, ends, ext, a.id());
            Sql.exec(tx, "INSERT INTO auction_bids(auction_id,bidder,bidder_name,amount,ts) VALUES(?,?,?,?,?)", a.id(), id, name, amt, now);
            return new BidResult(amt, false, ends);
        });
    }

    public BigDecimal minBid(Auction a) {
        if (a.bidder() == null) return Money.scale(a.currency(), a.startPrice());
        BigDecimal pct = Money.dec(pl.getConfig().getString("auction-house.min-bid-increment-percent", "5"), BigDecimal.valueOf(5));
        BigDecimal inc = Money.pct(a.currentBid(), pct);
        BigDecimal unit = BigDecimal.ONE.movePointLeft(pl.currencies().getOrDefault(a.currency()).decimals());
        return Money.scale(a.currency(), a.currentBid().add(inc.max(unit)));
    }

    private BidResult doBuy(Tx tx, Auction a, UUID buyer, String buyerName, BigDecimal price, long now) throws SQLException {
        BigDecimal commission = Money.dec(pl.getConfig().getString("auction-house.commission-percent", "5"), BigDecimal.ZERO);
        if (a.bidder() != null) Ledger.transfer(tx, ESCROW, Acct.player(a.bidder()), a.currency(), a.currentBid(), "AH_REFUND", "buy-now #" + a.id());
        UUID seller = UUID.fromString(a.seller());
        Ledger.Settlement st = Ledger.settle(tx, Acct.player(buyer), Acct.player(seller), a.currency(), price, commission, "INCOME", seller, "AH_SALE", "#" + a.id() + " " + a.itemName());
        Mailbox.add(tx, buyer, Inv.decodeOr(a.itemData(), a.material()), a.amount(), "ah:" + a.itemName());
        Sql.exec(tx, "UPDATE auctions SET state='SOLD', bidder=?, bidder_name=?, current_bid=?, ends=? WHERE id=?", buyer, buyerName, price, now, a.id());
        tx.afterCommit(() -> {
            Player sp = Bukkit.getPlayer(seller);
            if (sp != null) Msg.send(sp, "ah-sold", a.itemName(), Money.fmt(a.currency(), st.net()), buyerName);
        });
        return new BidResult(price, true, now);
    }

    public CompletableFuture<BidResult> buyNow(Player p, long auctionId) {
        UUID id = p.getUniqueId();
        String name = p.getName();
        return pl.db().tx(tx -> {
            Auction a = Sql.one(tx, "SELECT * FROM auctions WHERE id=?", AuctionService::map, auctionId);
            long now = System.currentTimeMillis();
            if (a == null || !a.state().equals("ACTIVE") || a.ends() <= now) throw new EcoException("ah-gone");
            if (a.seller().equals(id.toString())) throw new EcoException("ah-own");
            BigDecimal price = a.isBin() ? a.startPrice() : a.buyNow();
            if (price == null) throw new EcoException("ah-no-buynow");
            return doBuy(tx, a, id, name, price, now);
        });
    }

    public CompletableFuture<Void> cancel(Player p, long auctionId, boolean admin) {
        UUID id = p.getUniqueId();
        String actor = p.getName();
        return pl.db().tx(tx -> {
            Auction a = Sql.one(tx, "SELECT * FROM auctions WHERE id=?", AuctionService::map, auctionId);
            if (a == null || !a.state().equals("ACTIVE")) throw new EcoException("ah-gone");
            boolean own = a.seller().equals(id.toString());
            if (!own && !admin) throw new EcoException("no-permission");
            if (a.bidCount() > 0 && !admin) throw new EcoException("ah-has-bids");
            if (a.bidder() != null) Ledger.transfer(tx, ESCROW, Acct.player(a.bidder()), a.currency(), a.currentBid(), "AH_REFUND", "cancelled #" + a.id());
            Mailbox.add(tx, UUID.fromString(a.seller()), Inv.decodeOr(a.itemData(), a.material()), a.amount(), "ah-cancelled");
            Sql.exec(tx, "UPDATE auctions SET state='CANCELLED' WHERE id=?", a.id());
            if (!own) Audit.log(tx, actor, "ah.cancel", String.valueOf(a.id()), a.itemName());
            return null;
        });
    }

    /** Settles finished listings. Runs every few seconds. */
    public void settleExpired() {
        pl.db().tx(tx -> {
            long now = System.currentTimeMillis();
            List<Auction> list = Sql.list(tx, "SELECT * FROM auctions WHERE state='ACTIVE' AND ends<=? LIMIT 100", AuctionService::map, now);
            BigDecimal commission = Money.dec(pl.getConfig().getString("auction-house.commission-percent", "5"), BigDecimal.ZERO);
            for (Auction a : list) {
                UUID seller = UUID.fromString(a.seller());
                if (a.bidder() == null) {
                    Mailbox.add(tx, seller, Inv.decodeOr(a.itemData(), a.material()), a.amount(), "ah-expired");
                    Sql.exec(tx, "UPDATE auctions SET state='EXPIRED' WHERE id=?", a.id());
                    tx.afterCommit(() -> { Player sp = Bukkit.getPlayer(seller); if (sp != null) Msg.send(sp, "ah-expired", a.itemName()); });
                } else {
                    UUID winner = UUID.fromString(a.bidder());
                    Ledger.Settlement st = Ledger.settle(tx, ESCROW, Acct.player(seller), a.currency(), a.currentBid(), commission, "INCOME", seller, "AH_SALE", "#" + a.id() + " " + a.itemName());
                    Mailbox.add(tx, winner, Inv.decodeOr(a.itemData(), a.material()), a.amount(), "ah:" + a.itemName());
                    Sql.exec(tx, "UPDATE auctions SET state='SOLD' WHERE id=?", a.id());
                    tx.afterCommit(() -> {
                        Player sp = Bukkit.getPlayer(seller);
                        if (sp != null) Msg.send(sp, "ah-sold", a.itemName(), Money.fmt(a.currency(), st.net()), a.bidderName());
                        Player wp = Bukkit.getPlayer(winner);
                        if (wp != null) Msg.send(wp, "ah-won", a.itemName(), Money.fmt(a.currency(), a.currentBid()));
                    });
                }
            }
            return null;
        });
    }
}
