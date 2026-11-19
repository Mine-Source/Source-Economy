package com.economycore.api;

import java.util.Map;

/** Qué se entrega. type selecciona el RewardHandler (item, command, permission, currency, exp, pet...). */
public record RewardSpec(String type, Map<String, String> data) {
    public RewardSpec { data = data == null ? Map.of() : Map.copyOf(data); }
    public static RewardSpec of(String type, Map<String, String> data) { return new RewardSpec(type, data); }
}
