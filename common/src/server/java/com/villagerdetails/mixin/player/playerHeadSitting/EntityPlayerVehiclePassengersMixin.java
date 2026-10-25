package com.villagerdetails.mixin.player.playerHeadSitting;

import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把「我头上现在坐着谁」补发给<b>玩家载具自己</b>的客户端。
 *
 * <p>原版乘客列表的同步有两条路，<b>都不会到达载具本人</b>：
 * <ul>
 *     <li>{@code ServerPlayer#startRiding}：把 {@code ClientboundSetPassengersPacket(载具)} 发给
 *         <b>骑上去的那个人</b>（所以乘客自己能看到）；</li>
 *     <li>{@code ServerEntity#sendChanges}：乘客列表变了就广播给<b>追踪这个载具的玩家</b>
 *         （所以旁观者也能看到）。</li>
 * </ul>
 * 而「自己追踪自己」是不存在的 —— 载具如果是个<b>玩家</b>，他自己的客户端永远收不到这个包，
 * 于是画面上乘客被钉在最后收到的坐标不动。原版之所以没这个问题，是因为原版不允许玩家当载具
 * （{@code EntityTypes.PLAYER} 是 {@code noSave()}，{@code startRiding} 里
 * {@code canSerialize()} 那道检查会直接拒绝）。
 *
 * <p>补发时机就是乘客关系变化的那一刻：{@code addPassenger} / {@code removePassenger} 的 TAIL。
 * 在这里发而不是在 {@code startRiding}/脚本地收尾，是因为「下坐」在服务端有好几条路
 * （乘客自己按 Shift 走原版 {@code Player#rideTick}、被踢、死亡、换维度、规则关闭），
 * 而它们最终都会经过这两个方法，一处覆盖完。
 */
@Mixin(Entity.class)
public abstract class EntityPlayerVehiclePassengersMixin {

    @Inject(method = "addPassenger(Lnet/minecraft/world/entity/Entity;)V", at = @At("TAIL"))
    private void villagerdetails$syncPassengersToPlayerVehicle(Entity passenger, CallbackInfo ci) {
        syncToVehicleClient((Entity) (Object) this);
    }

    @Inject(method = "removePassenger(Lnet/minecraft/world/entity/Entity;)V", at = @At("TAIL"))
    private void villagerdetails$syncPassengersToPlayerVehicleAfterRemove(Entity passenger, CallbackInfo ci) {
        syncToVehicleClient((Entity) (Object) this);
    }

    /**
     * 载具是服务端玩家时，把当前的乘客列表发给他自己的连接；其它情况什么都不做。
     *
     * <p>不加规则开关判断：规则关掉时会把乘客全部踢下来，那一下同样需要这条包，
     * 否则载具的客户端会一直把乘客粘在头上。
     */
    @Unique
    private static void syncToVehicleClient(Entity vehicle) {
        if (!(vehicle instanceof ServerPlayer playerVehicle)) {
            return;
        }
        // 客户端也会跑 addPassenger/removePassenger（处理 SetPassengers 包时），那边不是 ServerPlayer，上面已经挡掉了
        if (playerVehicle.level().isClientSide()) {
            return;
        }
        playerVehicle.connection.send(new ClientboundSetPassengersPacket(playerVehicle));
    }
}
