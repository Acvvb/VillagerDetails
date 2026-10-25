package com.villagerdetails.handler.player.headSitting;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;

/**
 * 「挥手」这件小事的<b>版本适配</b>（26.2 版）。
 *
 * <p>两个版本的 {@code LivingEntity#swing} 签名不一样：
 * <ul>
 *     <li>26.2：{@code swing(InteractionHand, boolean)}</li>
 *     <li>26.3：{@code swing(InteractionHand, SwingAnimation, boolean)}（挥手动作也变成数据组件了）</li>
 * </ul>
 * 所以这个类在 {@code v26.2/src/server/java} 和 {@code v26.3/src/server/java} 各放一份，
 * 公共代码只认 {@link #swing(ServerPlayer, InteractionHand)} 这一个方法。
 */
public final class PlayerHeadSittingSwing {

    private PlayerHeadSittingSwing() {
    }

    /**
     * 让玩家挥一下手，并同步给附近的人（自己也要看到）
     */
    public static void swing(ServerPlayer player, InteractionHand hand) {
        player.swing(hand, true);
    }
}
