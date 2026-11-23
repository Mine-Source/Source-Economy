package com.economycore.core;

import java.math.BigDecimal;

public record Currency(String id, String name, String symbol, int decimals, BigDecimal startBalance,
                       boolean transferable, boolean isDefault, String color) {
}
