package com.villagerdetails.client.cache.base;

import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * 客户端状态缓存的基础容器，提供线程安全的单值读写。
 *
 * <p>值<b>永远不为 null</b>：所有读写路径都挡掉 null。
 * 这不是洁癖，是踩过的坑 —— 缓存类都是「{@code INSTANCE} 单例 + 一个空值常量」的写法，
 * 如果那个常量声明在 {@code INSTANCE} 之后，实例字段初始化时读到的就是 null
 * （静态字段按声明顺序初始化），于是渲染线程拿到 null 直接 NPE。
 * 现在这种写法会在<b>启动扫包</b>时报错（{@code ChannelScanner} 会把类名和堆栈打出来），
 * 而不是等玩家进服、跑到渲染帧才崩。
 */
public final class ClientState<T> {

    private volatile T value;

    public ClientState(T initial) {
        this.value = Objects.requireNonNull(initial,
                "ClientState 的初值不能为 null：空值常量要声明在 INSTANCE 之前，"
                        + "或者放进嵌套类型（如 Snapshot.EMPTY）里躲开静态字段的初始化顺序");
    }

    public T get() {
        return value;
    }

    public void set(T newValue) {
        this.value = Objects.requireNonNull(newValue, "ClientState.set 不接受 null");
    }

    /**
     * 原子更新：等价于读取当前值 -> 计算新值 -> 写回。
     * 替代了原来手写 synchronized(lock) { value = operator.apply(value); }
     */
    public void update(UnaryOperator<T> operator) {
        synchronized (this) {
            this.value = Objects.requireNonNull(operator.apply(this.value), "ClientState.update 算出了 null");
        }
    }

    public void clear(T emptyValue) {
        this.value = Objects.requireNonNull(emptyValue, "ClientState.clear 的空值不能为 null");
    }
}
