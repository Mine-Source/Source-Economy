package com.economycore.api;

import com.economycore.core.Currency;
import com.economycore.core.EconomyCore;
import com.economycore.core.Ledger;
import com.economycore.core.Money;
import java.math.BigDecimal;
import java.util.List;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/**
 * Placeholders:
 * %economycore_balance% | _balance_formatted | _balance_compact | _balance_<cur> | _balance_<cur>_formatted | _balance_<cur>_compact
 * %economycore_rank% | _rank_<cur>
 * %economycore_top_<n>_name% | _top_<n>_balance% | _top_<n>_balance_formatted% | _top_<cur>_<n>_name ...
 * %economycore_stock_<TICKER>_price% | _change | _prev | _volume
 * %economycore_index% | %economycore_treasury% | %economycore_treasury_<cur>% | %economycore_symbol%
 */
public final class PapiExpansion extends PlaceholderExpansion {
    private final EconomyCore pl;
    private final PlaceholderData data;

    public PapiExpansion(EconomyCore pl, PlaceholderData data) { this.pl = pl; this.data = data; }

    @Override public @NotNull String getIdentifier() { return "economycore"; }
    @Override public @NotNull String getAuthor() { return "EconomyCore"; }
    @Override public @NotNull String getVersion() { return pl.getDescription().getVersion(); }
    @Override public boolean persist() { return true; }
    @Override public boolean canRegister() { return true; }

    private String fmt(String cur, BigDecimal v, String mode) {
        return switch (mode) {
            case "formatted" -> Money.plain(cur, v);
            case "compact" -> Money.compact(v);
            default -> Money.scale(cur, v).toPlainString();
        };
    }

    private String cur(String s) {
        Currency c = pl.currencies().get(s);
        return c == null ? null : c.id();
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        String[] p = params.toLowerCase().split("_");
        String def = pl.currencies().defId();
        switch (p[0]) {
            case "symbol": return pl.currencies().getOrDefault(def).symbol();
            case "index": return data.index.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
            case "treasury": {
                String c = p.length > 1 && cur(p[1]) != null ? cur(p[1]) : def;
                String mode = p.length > 1 && cur(p[1]) == null ? p[1] : p.length > 2 ? p[2] : "";
                return fmt(c, data.treasury.getOrDefault(c, BigDecimal.ZERO), mode);
            }
            case "balance": {
                if (player == null) return "";
                String c = def;
                String mode = "";
                if (p.length > 1) {
                    if (cur(p[1]) != null) { c = cur(p[1]); if (p.length > 2) mode = p[2]; } else mode = p[1];
                }
                return fmt(c, Ledger.cached(player.getUniqueId(), c), mode);
            }
            case "rank": {
                if (player == null) return "";
                String c = p.length > 1 && cur(p[1]) != null ? cur(p[1]) : def;
                Integer r = data.ranks.get(c + ":" + player.getUniqueId());
                return r == null ? "-" : String.valueOf(r);
            }
            case "top": {
                // top_<n>_<field>[_formatted|_compact]  or  top_<cur>_<n>_<field>[...]
                int i = 1;
                String c = def;
                if (p.length > 1 && cur(p[1]) != null) { c = cur(p[1]); i = 2; }
                if (p.length <= i + 1) return null;
                int n;
                try { n = Integer.parseInt(p[i]); } catch (NumberFormatException e) { return null; }
                List<PlaceholderData.Top> l = data.tops.get(c);
                if (l == null || n < 1 || n > l.size()) return p[i + 1].equals("name") ? "-" : "0";
                PlaceholderData.Top t = l.get(n - 1);
                if (p[i + 1].equals("name")) return t.name();
                if (p[i + 1].equals("balance")) return fmt(c, t.amount(), p.length > i + 2 ? p[i + 2] : "");
                return null;
            }
            case "stock": {
                if (p.length < 3) return null;
                PlaceholderData.Stock s = data.stocks.get(p[1].toUpperCase());
                if (s == null) return "-";
                String c = def;
                return switch (p[2]) {
                    case "price" -> fmt(c, s.price(), p.length > 3 ? p[3] : "");
                    case "prev" -> fmt(c, s.prev() == null ? s.price() : s.prev(), p.length > 3 ? p[3] : "");
                    case "change" -> (s.changePct().signum() > 0 ? "+" : "") + s.changePct().toPlainString();
                    case "volume" -> String.valueOf(s.volume24());
                    default -> null;
                };
            }
            default: return null;
        }
    }
}
