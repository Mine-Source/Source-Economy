package com.economycore.core;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Versioned schema. Append new arrays to STEPS; never edit shipped ones. */
final class Migrations {
    private Migrations() {}

    private static final String ORDERS = "(id INTEGER PRIMARY KEY AUTOINCREMENT, product TEXT, owner TEXT, owner_name TEXT, side TEXT, price TEXT, pnum REAL, amount INTEGER, filled INTEGER DEFAULT 0, state TEXT, created INTEGER, updated INTEGER)";

    private static final String[][] STEPS = {
        {
            "CREATE TABLE IF NOT EXISTS players(uuid TEXT PRIMARY KEY, name TEXT NOT NULL, first_seen INTEGER, last_seen INTEGER)",
            "CREATE INDEX IF NOT EXISTS idx_players_name ON players(name COLLATE NOCASE)",
            "CREATE TABLE IF NOT EXISTS currencies(id TEXT PRIMARY KEY, name TEXT, symbol TEXT, decimals INTEGER, start_balance TEXT, transferable INTEGER, is_default INTEGER, color TEXT)",
            "CREATE TABLE IF NOT EXISTS balances(owner_type TEXT, owner_id TEXT, currency TEXT, amount TEXT NOT NULL, PRIMARY KEY(owner_type, owner_id, currency))",
            "CREATE INDEX IF NOT EXISTS idx_bal_cur ON balances(currency, owner_type)",
            "CREATE TABLE IF NOT EXISTS transactions(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, type TEXT, from_type TEXT, from_id TEXT, to_type TEXT, to_id TEXT, currency TEXT, amount TEXT, fee TEXT, tax TEXT, note TEXT)",
            "CREATE INDEX IF NOT EXISTS idx_tx_ts ON transactions(ts)",
            "CREATE INDEX IF NOT EXISTS idx_tx_from ON transactions(from_id, ts)",
            "CREATE INDEX IF NOT EXISTS idx_tx_to ON transactions(to_id, ts)",
            "CREATE INDEX IF NOT EXISTS idx_tx_type ON transactions(type, ts)",
            "CREATE TABLE IF NOT EXISTS audit(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, actor TEXT, action TEXT, target TEXT, detail TEXT)",
            "CREATE INDEX IF NOT EXISTS idx_audit_ts ON audit(ts)",
            "CREATE TABLE IF NOT EXISTS tax_brackets(id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT, currency TEXT, min_amount TEXT, rate TEXT)",
            "CREATE TABLE IF NOT EXISTS tax_exemptions(id INTEGER PRIMARY KEY AUTOINCREMENT, subject TEXT, kind TEXT, note TEXT, UNIQUE(subject, kind))",
            "CREATE TABLE IF NOT EXISTS pending_items(id INTEGER PRIMARY KEY AUTOINCREMENT, owner TEXT, item_data TEXT, amount INTEGER, source TEXT, ts INTEGER)",
            "CREATE INDEX IF NOT EXISTS idx_pending_owner ON pending_items(owner)",
            "CREATE TABLE IF NOT EXISTS alerts(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, level TEXT, kind TEXT, message TEXT, ack INTEGER DEFAULT 0)",
            "CREATE TABLE IF NOT EXISTS analytics(ts INTEGER, currency TEXT, supply TEXT, players TEXT, banks TEXT, companies TEXT, treasury TEXT, escrow TEXT, tx_count INTEGER, tx_volume TEXT, accounts INTEGER, PRIMARY KEY(ts, currency))",

            "CREATE TABLE IF NOT EXISTS treasury_budgets(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE, currency TEXT, allocated TEXT, spent TEXT, created INTEGER)",
            "CREATE TABLE IF NOT EXISTS treasury_projects(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, description TEXT, currency TEXT, cost TEXT, payee_type TEXT, payee_id TEXT, creator TEXT, created INTEGER, voting_ends INTEGER, status TEXT, yes_votes INTEGER DEFAULT 0, no_votes INTEGER DEFAULT 0)",
            "CREATE TABLE IF NOT EXISTS treasury_votes(project_id INTEGER, voter TEXT, vote INTEGER, ts INTEGER, PRIMARY KEY(project_id, voter))",
            "CREATE TABLE IF NOT EXISTS subsidies(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, target_type TEXT, target_id TEXT, currency TEXT, amount TEXT, interval_min INTEGER, last_paid INTEGER, active INTEGER DEFAULT 1, budget_id INTEGER DEFAULT 0)",
            "CREATE TABLE IF NOT EXISTS rewards(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, trigger_type TEXT, trigger_key TEXT, currency TEXT, amount TEXT, source TEXT, cooldown_sec INTEGER DEFAULT 0, active INTEGER DEFAULT 1)",
            "CREATE TABLE IF NOT EXISTS reward_cooldowns(reward_id INTEGER, player TEXT, last INTEGER, PRIMARY KEY(reward_id, player))",

            "CREATE TABLE IF NOT EXISTS markets(id TEXT PRIMARY KEY, name TEXT, icon TEXT, currency TEXT, tax_pct TEXT DEFAULT '0', commission_pct TEXT DEFAULT '0', permission TEXT, open_min INTEGER DEFAULT -1, close_min INTEGER DEFAULT -1, days TEXT DEFAULT '', rot_enabled INTEGER DEFAULT 0, rot_size INTEGER DEFAULT 10, rot_interval_min INTEGER DEFAULT 60, last_rot INTEGER DEFAULT 0, enabled INTEGER DEFAULT 1, discount_pct TEXT DEFAULT '0', is_shop INTEGER DEFAULT 0)",
            "CREATE TABLE IF NOT EXISTS market_categories(market TEXT, id TEXT, name TEXT, icon TEXT, sort INTEGER DEFAULT 0, PRIMARY KEY(market, id))",
            "CREATE TABLE IF NOT EXISTS market_products(id INTEGER PRIMARY KEY AUTOINCREMENT, market TEXT, category TEXT, material TEXT, item_data TEXT, name TEXT, buy_price TEXT, sell_price TEXT, stock INTEGER DEFAULT -1, max_stock INTEGER DEFAULT -1, restock_per_hour INTEGER DEFAULT 0, last_restock INTEGER DEFAULT 0, discount_pct TEXT DEFAULT '0', limit_per_player INTEGER DEFAULT 0, limit_hours INTEGER DEFAULT 24, permission TEXT, active INTEGER DEFAULT 1, in_rotation INTEGER DEFAULT 1)",
            "CREATE INDEX IF NOT EXISTS idx_mp_market ON market_products(market, category)",
            "CREATE TABLE IF NOT EXISTS market_limits(product_id INTEGER, player TEXT, window_start INTEGER, count INTEGER, PRIMARY KEY(product_id, player))",

            "CREATE TABLE IF NOT EXISTS auctions(id INTEGER PRIMARY KEY AUTOINCREMENT, seller TEXT, seller_name TEXT, item_data TEXT, material TEXT, item_name TEXT, amount INTEGER, category TEXT, currency TEXT, type TEXT, start_price TEXT, buy_now TEXT, current_bid TEXT, bidder TEXT, bidder_name TEXT, bid_count INTEGER DEFAULT 0, created INTEGER, ends INTEGER, extensions INTEGER DEFAULT 0, state TEXT)",
            "CREATE INDEX IF NOT EXISTS idx_auc_state ON auctions(state, ends)",
            "CREATE INDEX IF NOT EXISTS idx_auc_seller ON auctions(seller, state)",
            "CREATE INDEX IF NOT EXISTS idx_auc_cat ON auctions(state, category)",
            "CREATE TABLE IF NOT EXISTS auction_bids(id INTEGER PRIMARY KEY AUTOINCREMENT, auction_id INTEGER, bidder TEXT, bidder_name TEXT, amount TEXT, ts INTEGER)",
            "CREATE INDEX IF NOT EXISTS idx_bids_auc ON auction_bids(auction_id)",
            "CREATE TABLE IF NOT EXISTS auction_favorites(player TEXT, auction_id INTEGER, PRIMARY KEY(player, auction_id))",

            "CREATE TABLE IF NOT EXISTS bazaar_products(id TEXT PRIMARY KEY, name TEXT, material TEXT, category TEXT, currency TEXT, active INTEGER DEFAULT 1)",
            "CREATE TABLE IF NOT EXISTS bazaar_orders" + ORDERS,
            "CREATE INDEX IF NOT EXISTS idx_bzo_book ON bazaar_orders(product, state, side, pnum)",
            "CREATE INDEX IF NOT EXISTS idx_bzo_owner ON bazaar_orders(owner, state)",
            "CREATE TABLE IF NOT EXISTS bazaar_trades(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, product TEXT, buyer TEXT, seller TEXT, price TEXT, qty INTEGER)",
            "CREATE INDEX IF NOT EXISTS idx_bzt ON bazaar_trades(product, ts)",

            "CREATE TABLE IF NOT EXISTS traders(id TEXT PRIMARY KEY, name TEXT, permission TEXT, enabled INTEGER DEFAULT 1, icon TEXT)",
            "CREATE TABLE IF NOT EXISTS trader_bindings(npc_key TEXT PRIMARY KEY, trader TEXT)",
            "CREATE TABLE IF NOT EXISTS trader_offers(id INTEGER PRIMARY KEY AUTOINCREMENT, trader TEXT, cost_item TEXT, cost_item_amt INTEGER DEFAULT 0, cost_money TEXT, cost_currency TEXT, res_item TEXT, res_item_amt INTEGER DEFAULT 0, res_money TEXT, res_currency TEXT, max_uses INTEGER DEFAULT 0, global_stock INTEGER DEFAULT -1, enabled INTEGER DEFAULT 1, sort INTEGER DEFAULT 0)",
            "CREATE TABLE IF NOT EXISTS trader_uses(offer_id INTEGER, player TEXT, uses INTEGER, PRIMARY KEY(offer_id, player))",

            "CREATE TABLE IF NOT EXISTS trade_log(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, a TEXT, a_name TEXT, b TEXT, b_name TEXT, a_items TEXT, b_items TEXT, a_money TEXT, b_money TEXT, currency TEXT, status TEXT)",
            "CREATE INDEX IF NOT EXISTS idx_trade_a ON trade_log(a, ts)",
            "CREATE INDEX IF NOT EXISTS idx_trade_b ON trade_log(b, ts)",

            "CREATE TABLE IF NOT EXISTS pshops(id INTEGER PRIMARY KEY AUTOINCREMENT, owner TEXT, owner_name TEXT, name TEXT, description TEXT, icon TEXT, open INTEGER DEFAULT 1, currency TEXT, created INTEGER)",
            "CREATE TABLE IF NOT EXISTS pshop_items(id INTEGER PRIMARY KEY AUTOINCREMENT, shop_id INTEGER, item_data TEXT, material TEXT, item_name TEXT, price TEXT, stock INTEGER, sold INTEGER DEFAULT 0)",
            "CREATE INDEX IF NOT EXISTS idx_psi_shop ON pshop_items(shop_id)",
            "CREATE TABLE IF NOT EXISTS pshop_managers(shop_id INTEGER, uuid TEXT, name TEXT, PRIMARY KEY(shop_id, uuid))",

            "CREATE TABLE IF NOT EXISTS bank_accounts(id TEXT PRIMARY KEY, name TEXT, type TEXT, owner TEXT, currency TEXT, created INTEGER, interest_enabled INTEGER DEFAULT 1)",
            "CREATE TABLE IF NOT EXISTS bank_members(account TEXT, uuid TEXT, name TEXT, role TEXT, PRIMARY KEY(account, uuid))",
            "CREATE INDEX IF NOT EXISTS idx_bm_uuid ON bank_members(uuid)",

            "CREATE TABLE IF NOT EXISTS companies(id TEXT PRIMARY KEY, name TEXT, ticker TEXT UNIQUE, industry TEXT, description TEXT, kind TEXT, ceo TEXT, currency TEXT, total_shares INTEGER, price TEXT, prev_price TEXT, listed INTEGER DEFAULT 0, ipo_remaining INTEGER DEFAULT 0, ipo_price TEXT, auto_div_pct TEXT DEFAULT '0', created INTEGER, icon TEXT, active INTEGER DEFAULT 1)",
            "CREATE TABLE IF NOT EXISTS company_members(company TEXT, uuid TEXT, name TEXT, role TEXT, salary TEXT DEFAULT '0', flags TEXT DEFAULT '', hired INTEGER, PRIMARY KEY(company, uuid))",
            "CREATE INDEX IF NOT EXISTS idx_cm_uuid ON company_members(uuid)",
            "CREATE TABLE IF NOT EXISTS company_products(id INTEGER PRIMARY KEY AUTOINCREMENT, company TEXT, kind TEXT, name TEXT, item_data TEXT, material TEXT, price TEXT, stock INTEGER DEFAULT 0, description TEXT, command TEXT, produce_amount INTEGER DEFAULT 0, produce_interval_min INTEGER DEFAULT 0, produce_cap INTEGER DEFAULT 0, last_produce INTEGER DEFAULT 0, active INTEGER DEFAULT 1)",
            "CREATE TABLE IF NOT EXISTS company_ledger(id INTEGER PRIMARY KEY AUTOINCREMENT, company TEXT, ts INTEGER, kind TEXT, amount TEXT, note TEXT)",
            "CREATE INDEX IF NOT EXISTS idx_cl ON company_ledger(company, ts)",
            "CREATE TABLE IF NOT EXISTS holdings(owner TEXT, company TEXT, shares INTEGER, avg_cost TEXT, PRIMARY KEY(owner, company))",
            "CREATE TABLE IF NOT EXISTS stock_orders" + ORDERS,
            "CREATE INDEX IF NOT EXISTS idx_sto_book ON stock_orders(product, state, side, pnum)",
            "CREATE INDEX IF NOT EXISTS idx_sto_owner ON stock_orders(owner, state)",
            "CREATE TABLE IF NOT EXISTS stock_trades(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, company TEXT, buyer TEXT, seller TEXT, price TEXT, qty INTEGER)",
            "CREATE INDEX IF NOT EXISTS idx_stt ON stock_trades(company, ts)",
            "CREATE TABLE IF NOT EXISTS stock_history(company TEXT, ts INTEGER, price TEXT, volume INTEGER, PRIMARY KEY(company, ts))",
            "CREATE TABLE IF NOT EXISTS stock_index(ts INTEGER PRIMARY KEY, value TEXT, volume INTEGER)",
            "CREATE TABLE IF NOT EXISTS dividends(id INTEGER PRIMARY KEY AUTOINCREMENT, company TEXT, ts INTEGER, per_share TEXT, total TEXT, holders INTEGER)",
            "CREATE TABLE IF NOT EXISTS payroll_log(id INTEGER PRIMARY KEY AUTOINCREMENT, company TEXT, ts INTEGER, paid TEXT, unpaid TEXT, employees INTEGER)"
        },
        {
            "CREATE TABLE IF NOT EXISTS notifications(id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT, ts INTEGER, key TEXT, args TEXT)",
            "CREATE INDEX IF NOT EXISTS idx_notif_uuid ON notifications(uuid)"
        }
    };

    static void run(Db db, Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT)");
        }
        int cur = 0;
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT value FROM meta WHERE key='schema_version'")) {
            if (rs.next()) cur = Integer.parseInt(rs.getString(1));
        }
        if (cur >= STEPS.length) return;
        if (cur > 0) db.backupNow("pre-migration-v" + cur);
        for (int v = cur; v < STEPS.length; v++) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                for (String sql : STEPS[v]) s.execute(sql);
                s.execute("INSERT OR REPLACE INTO meta(key,value) VALUES('schema_version','" + (v + 1) + "')");
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }
}
