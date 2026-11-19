package com.economycore.api;

/** Resuelve requisitos "type:arg" (permission, world, level, quest...). Un tipo sin proveedor o que lanza excepción deniega el acceso. */
public interface ConditionProvider {
    String type();
    boolean test(String buyer, String arg);
}
