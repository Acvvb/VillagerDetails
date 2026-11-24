package com.villagerdetails.client.handler.block.pistonPush;

/**
 * 客户端侧的「这次活塞结构扫描是伸出还是缩回」。
 *
 * <p>和服务端 {@code com.villagerdetails.handler.block.pistonPush.PistonMoveScope} 对称：
 * 原版推动和黏回共用 {@code PistonBaseBlock#isPushable}，只能靠
 * {@code PistonStructureResolver#extending} 分流，由客户端那份
 * {@code ClientPistonStructureResolverMixin} 在扫描开头写进来。
 *
 * <p>客户端本身不跑活塞结构扫描（那在服务端），所以这个值平时不会被设置；
 * 默认 {@code true}（按推动规则处理）—— 真到需要时（单人 / 集成服同进程、或将来做客户端预测）
 * 结构扫描会先覆盖它，行为和真实方向一致。
 */
public final class PistonMoveScope {

    private static final ThreadLocal<Boolean> EXTENDING = ThreadLocal.withInitial(() -> Boolean.TRUE);

    private PistonMoveScope() {
    }

    public static void set(boolean extending) {
        EXTENDING.set(extending);
    }

    /**
     * 这次判定是不是在伸出（推动）
     */
    public static boolean isExtending() {
        return EXTENDING.get();
    }
}
