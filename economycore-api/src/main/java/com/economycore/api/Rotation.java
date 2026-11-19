package com.economycore.api;

/** Rotación automática: cada intervalo se eligen 'slots' productos del catálogo (más los alwaysVisible), de forma determinista. */
public record Rotation(long intervalSeconds, int slots) {
    public Rotation {
        if (intervalSeconds < 1 || slots < 0) throw new IllegalArgumentException("Rotation inválida");
    }
    public long windowIndex(long nowMillis) { return nowMillis / (intervalSeconds * 1000L); }
}
