package com.villagerdetails.mixin.entity.blockSitting;

import com.villagerdetails.handler.player.blockSitting.PlayerBlockSittingHandler;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 吞掉「坐在座位上时被误发的建筑高度提示」。
 *
 * <p><b>为什么会有这条误发</b>（这不是我们算错坐标，是原版的一个分支）：
 * <ol>
 *     <li>{@code ServerPlayer#startRiding} 内部会调 {@code connection.teleport(...)}，
 *         把 {@code ServerGamePacketListenerImpl.awaitingPositionFromClient} 置成非空（等客户端确认传送）；</li>
 *     <li>客户端一次右键方块会发<b>两个</b> {@code ServerboundUseItemOnPacket}（主手 + 副手）；</li>
 *     <li>主手那一包让人坐上去了；副手那一包紧接着到达，此时 {@code awaitingPositionFromClient != null}，
 *         于是 {@code handleUseItemOn} 走「不处理交互」的兜底分支 ——
 *         而原版在这个兜底分支里发的是 {@code sendBuildLimitMessage(true, level.getMaxY())}，
 *         也就是玩家看到的那条红色「建筑高度上限是 319」；</li>
 *     <li>于是：坐上去的瞬间必然弹一次红字，而且只有坐的人能看到（消息只发给发包的人）。</li>
 * </ol>
 *
 * <p>坐在座位上时（以及刚坐下 / 刚起身的半秒内）根本不可能真的碰到世界高度，
 * 所以这里把这条提示直接吞掉；其它任何情况都原样转发，不影响原版行为。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class SeatBuildLimitMessageMixin {

    @Redirect(
            method = "handleUseItemOn(Lnet/minecraft/network/protocol/game/ServerboundUseItemOnPacket;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;sendBuildLimitMessage(ZI)V")
    )
    private void villagerdetails$skipSeatFalseAlarm(ServerPlayer player, boolean tooHigh, int limit) {
        if (PlayerBlockSittingHandler.shouldIgnoreBuildLimitMessage(player)) {
            return;
        }
        player.sendBuildLimitMessage(tooHigh, limit);
    }
}
