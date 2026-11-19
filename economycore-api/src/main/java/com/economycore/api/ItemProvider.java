package com.economycore.api;

import java.util.List;
import java.util.Optional;

/** Un plugin externo implementa esto para publicar productos en un mercado. */
public interface ItemProvider {
    String id();
    List<Product> products();
    /** Devuelve un motivo de rechazo o vacío si la compra es válida. Excepciones se tratan como rechazo. */
    default Optional<String> validate(String buyer, Product product, int quantity) { return Optional.empty(); }
}
