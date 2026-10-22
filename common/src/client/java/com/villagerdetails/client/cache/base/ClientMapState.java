package com.villagerdetails.client.cache.base;

import java.util.HashMap;
import java.util.Map;

/**
 * 客户端状态缓存的基础容器（Map 版），提供线程安全的局部增删改查。
 *
 * <p>底层依然是「不可变 Map 引用整体替换」的思路，
 * 但对外隐藏了每次修改都要 new HashMap 并 copyOf 的繁琐细节。
 */
public final class ClientMapState<K, V> {

    private volatile Map<K, V> value;

    public ClientMapState() {
        this.value = Map.of();
    }

    public ClientMapState(Map<K, V> initial) {
        this.value = Map.copyOf(initial);
    }

    /**
     * 获取单个值
     */
    public V get(K key) {
        return value.get(key);
    }

    /**
     * 线程安全地放入/替换一个键值对
     */
    public void put(K key, V val) {
        synchronized (this) {
            Map<K, V> next = new HashMap<>(this.value);
            next.put(key, val);
            this.value = Map.copyOf(next);
        }
    }

    /**
     * 线程安全地移除一个键
     */
    public void remove(K key) {
        synchronized (this) {
            if (!this.value.containsKey(key)) return;
            Map<K, V> next = new HashMap<>(this.value);
            next.remove(key);
            this.value = Map.copyOf(next);
        }
    }

    /**
     * 清空所有数据
     */
    public void clear() {
        synchronized (this) {
            this.value = Map.of();
        }
    }
}
