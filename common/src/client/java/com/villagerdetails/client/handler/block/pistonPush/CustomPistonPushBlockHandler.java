package com.villagerdetails.client.handler.block.pistonPush;

import com.villagerdetails.client.cache.impl.PistonPushRuleCache;
import net.minecraft.world.level.block.Block;

/**
 * 「活塞搬运控制」的客户端镜像读取器。
 *
 * <p>对应服务端 {@code com.villagerdetails.handler.block.pistonPush.CustomPistonPushBlockHandler}
 * （同名、同语义、<b>不共享代码</b>）：按项目约定，「即使两端的逻辑长得一样，也各写一份」，
 * 免得单边改动时另一端的编译期约束消失。两端的差异只在数据来源 ——
 * 服务端读本地配置文件与规则开关，客户端读 {@link PistonPushRuleCache}（服务端下发的镜像）。
 *
 * <p>语义是「<b>取反</b>」：名单里的方块把原版「能不能被活塞搬动 / 被粘性方块带走」的结果翻转，
 * 名单外交给原版。取反本身发生在注入点（拿到原版对当前上下文的真实结果），这里只回答「要不要取反」。
 *
 * <p>调用方是 {@code PistonBaseBlock} 的热路径（{@code isPushable} 与
 * {@code triggerEvent} 的 {@code PushReaction} 闸）：这里只做一次缓存快照读 + 一次哈希查找，
 * 不碰注册表。
 */
public final class CustomPistonPushBlockHandler {

    private CustomPistonPushBlockHandler() {
    }

    /**
     * 这个方块要不要把原版搬运判定取反。
     *
     * @return {@code null} = 不覆盖（名单外，原版怎么判就怎么判）；{@code true} = 取反
     */
    public static Boolean isConfigured(Block block) {
        return PistonPushRuleCache.INSTANCE.flips(block) ? Boolean.TRUE : null;
    }
}
