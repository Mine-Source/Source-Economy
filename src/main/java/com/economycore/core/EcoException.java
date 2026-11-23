package com.economycore.core;

/** Business-rule failure carrying a message key (resolved through messages.yml). */
public class EcoException extends RuntimeException {
    private final String key;
    private final Object[] args;

    public EcoException(String key, Object... args) {
        super(key, null, false, false);
        this.key = key;
        this.args = args;
    }

    public String key() { return key; }
    public Object[] args() { return args; }
}
