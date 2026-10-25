package com.villagerdetails.mixin.player.playerHeadSitting;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.entity.ModEntities;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 让「玩家骑不该被存盘的载具」这件事在服务端能过掉原版的那道检查。
 *
 * <p>原版 {@code Entity#startRiding(Entity, boolean force, boolean sendGameEvent)} 里有一句：
 * <pre>
 * // 只在服务端生效（客户端 isClientSide 时直接跳过）
 * if (!vehicle.getType().canSerialize()) return false;
 * </pre>
 * 它本来是为了「别让会被丢掉的实体成为载具」，但我们要用的两种载具都正好是 {@code noSave()} 的：
 * <ul>
 *     <li>{@code EntityTypes.PLAYER}——「玩家头顶乘坐」的载具（原版 {@code /ride A mount B} 报
 *         {@code ERROR_MOUNTING_PLAYER} 也是这个原因）；</li>
 *     <li>{@code ModEntities.BLOCK_SEAT}——「坐在楼梯 / 半砖上」的隐形座位。</li>
 * </ul>
 * 这条规则必须绕过它。
 *
 * <p>放行范围极小：<b>乘客是玩家</b>，且「载具是玩家 + 对应规则开着」或
 * 「载具是我们自己的座位 + 方块就坐规则开着」时，把这次 {@code canSerialize()} 的结果改成
 * {@code true}；其它任何情况原样返回。之后 {@code couldAcceptPassenger} / 叠罗汉环检测 /
 * {@code canRide} / {@code addPassenger} 全都还是原版逻辑，{@code ServerPlayer#startRiding}
 * 那套发包（乘客包 + 传送）也照常执行。
 */
@Mixin(Entity.class)
public abstract class EntityStartRidingMixin {

    @Redirect(
            method = "startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/EntityType;canSerialize()Z")
    )
    private boolean villagerdetails$allowPlayerVehicle(EntityType<?> vehicleType, Entity entityToRide) {
        boolean original = vehicleType.canSerialize();
        if (original) {
            return true;
        }
        // this = 发起骑乘的乘客（mixin 类不继承 Entity，所以走 (Object) 这层强转）
        Entity passenger = (Entity) (Object) this;
        if (!(passenger instanceof Player)) {
            return false;
        }
        if (entityToRide instanceof Player) {
            return RuleCache.isEnabled(RuleType.PLAYER_HEAD_SITTING);
        }
        if (vehicleType == ModEntities.BLOCK_SEAT) {
            return RuleCache.isEnabled(RuleType.PLAYER_BLOCK_SITTING);
        }
        return false;
    }
}
