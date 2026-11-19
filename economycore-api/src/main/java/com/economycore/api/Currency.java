package com.economycore.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Moneda. Las cantidades se guardan siempre como enteros long en la unidad mínima (decimals). */
public record Currency(String id, String name, String symbol, int decimals) {
    public Currency {
        Objects.requireNonNull(name); Objects.requireNonNull(symbol);
        if (id == null || !id.matches("[a-z0-9_]{1,32}")) throw new IllegalArgumentException("id de moneda inválido: " + id);
        if (decimals < 0 || decimals > 8) throw new IllegalArgumentException("decimals fuera de 0..8");
    }
    /** Parsea "12.50". Falla si hay más decimales de los permitidos (nunca redondea). */
    public Money parse(String text) {
        BigDecimal bd = new BigDecimal(text.trim()).setScale(decimals, RoundingMode.UNNECESSARY);
        return new Money(id, bd.movePointRight(decimals).longValueExact());
    }
    public String format(long minor) {
        return symbol + BigDecimal.valueOf(minor, decimals).toPlainString();
    }
}
