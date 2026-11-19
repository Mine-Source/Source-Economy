package com.economycore.api;

import java.util.List;
import java.util.Optional;

/** API pública de mercados. Los plugins externos solo necesitan este contrato. */
public interface MarketService {
    void registerMarket(Market market, List<Product> products);
    boolean unregisterMarket(String marketId);
    boolean setMarketEnabled(String marketId, boolean enabled);
    List<String> marketIds();
    Optional<Market> market(String marketId);

    void upsertProducts(String marketId, List<Product> products);
    void removeProduct(String marketId, String productId);
    /** Catálogo público (sin productos secretos). */
    List<Product> visibleProducts(String marketId);
    /** Catálogo para un jugador: incluye secretos cuyos requisitos cumple. */
    List<Product> visibleProducts(String marketId, String buyer);
    List<Product> search(String marketId, String query, int page, int size);

    void registerRewardHandler(RewardHandler handler);
    void registerConditionProvider(ConditionProvider provider);
    /** Adjunta un proveedor a un mercado y publica sus productos. */
    void attachProvider(String marketId, ItemProvider provider);
    void detachProvider(String marketId, String providerId);
    void refreshProvider(String providerId);

    PurchaseRecord purchase(PurchaseRequest request);
    Optional<PurchaseRecord> purchase(String purchaseId);
    List<PurchaseRecord> purchasesOf(String buyer, int limit);
    /** Reintenta entregas pendientes y limpia compras abandonadas. Devuelve cuántas se tocaron. */
    int recover(long staleMillis);
    PurchaseRecord retryDelivery(String purchaseId);
    PurchaseRecord refund(String purchaseId);
    void restock(String marketId, String productId, long newRemaining);
    void addListener(MarketListener listener);
}
