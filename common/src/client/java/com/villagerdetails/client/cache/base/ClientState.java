package com.villagerdetails.client.cache.base;

import java.util.function.UnaryOperator;

/**
 * 客户端状态缓存的基础容器，提供线程安全的单值读写。
 */
public final class ClientState<T> {
    private volatile T value;

    public ClientState(T initial) {
        this.value = initial;
    }

    public T get() {
        return value;
    }

    public void set(T newValue) {
        this.value = newValue;
    }

    /**
     * 原子更新：等价于读取当前值 -> 计算新值 -> 写回。
     * 替代了原来手写 synchronized(lock) { value = operator.apply(value); }
     */
    public void update(UnaryOperator<T> operator) {
        synchronized (this) {
            this.value = operator.apply(this.value);
        }
    }

    public void clear(T emptyValue) {
        this.value = emptyValue;
    }
}
