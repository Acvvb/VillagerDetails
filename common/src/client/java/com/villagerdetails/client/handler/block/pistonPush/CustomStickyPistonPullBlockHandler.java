package com.villagerdetails.client.handler.block.pistonPush;

import com.villagerdetails.client.cache.impl.StickyPistonPullRuleCache;
import net.minecraft.world.level.block.Block;

/**
 * 粘性活塞黏回规则的客户端镜像读取器。
 *
 * <p>对应服务端 {@code com.villagerdetails.handler.block.pistonPush.CustomStickyPistonPullBlockHandler}
 * （同名、同语义、<b>不共享代码</b>）：服务端读配置文件与规则开关，客户端读
 * {@link StickyPistonPullRuleCache}（描述符同步过来的镜像）。
 *
 * <p>语义是「<b>取反</b>」：名单里的方块把原版「能不能被黏回来」的结果翻转，名单外交给原版。
 *
 * <p>调用方是 {@code PistonBaseBlock#isPushable} 的注入，属于热路径：
 * 这里只做一次缓存快照读 + 一次哈希查找，不碰注册表。
 */
public final class CustomStickyPistonPullBlockHandler {

    private CustomStickyPistonPullBlockHandler() {
    }

    /**
     * 这个方块要不要把原版「黏回」判定取反。
     *
     * @return {@code null} = 不覆盖（名单外，原版怎么判就怎么判）；{@code true} = 取反
     */
    public static Boolean isConfigured(Block block) {
        return StickyPistonPullRuleCache.INSTANCE.flips(block) ? Boolean.TRUE : null;
    }
}
