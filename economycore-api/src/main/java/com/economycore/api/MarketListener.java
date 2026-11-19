package com.economycore.api;

/** Se invoca tras cada cambio de estado persistido de una compra. */
public interface MarketListener { void onPurchaseState(PurchaseRecord record); }
