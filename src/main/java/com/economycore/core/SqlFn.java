package com.economycore.core;

@FunctionalInterface
public interface SqlFn<T> {
    T apply(Tx tx) throws Exception;
}
