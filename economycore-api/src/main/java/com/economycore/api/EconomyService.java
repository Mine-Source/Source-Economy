package com.economycore.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Servicio económico público. Cuenta = String (UUID de jugador o id de sistema).
 * Todas las operaciones de escritura requieren un opId único: reintentar con el mismo opId nunca aplica dos veces.
 */
public interface EconomyService {
    Collection<Currency> currencies();
    Optional<Currency> currency(String id);
    long balance(String account, String currency);
    Result deposit(String opId, String account, Money amount, String reason);
    Result withdraw(String opId, String account, Money amount, String reason);
    Result transfer(String opId, String from, String to, Money amount, String reason);
    List<LedgerEntry> history(String account, int limit);
}
