package com.villagerdetails.client.mixin.block.pistonPush;

import com.villagerdetails.client.handler.block.pistonPush.CustomPistonPushBlockHandler;
import com.villagerdetails.util.piston.PistonReactionCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 客户端侧的「活塞搬运控制」，和服务端
 * {@code com.villagerdetails.mixin.block.pistonPush.PistonBaseBlockMixin}
 * <b>注入同样的方法、同样的注入点</b>，读的却是本地镜像 {@code PistonPushRuleCache}。
 *
 * <p><b>为什么不共用服务端那份 Mixin</b>：服务端那份读配置文件和规则开关（{@code RuleCache} 在 server
 * 源集），客户端包里根本没有这些类。所以两份 Mixin 各自对着自己那端的 Handler，
 * 但 {@code @Mixin} 目标类、方法签名、注入点必须逐字一致 —— 目标方法一旦写错，
 * Mixin 只在运行期报错（{@code required: true} + {@code defaultRequire 1} 会直接把客户端启动打挂），
 * 编译期看不出来。
 *
 * <p><b>单人游戏里生效的就是这一份</b>：{@code fabric.mod.json} 把服务端那份 mixin 配成
 * {@code "environment": "server"}，物理客户端上不加载它，集成服跑的还是同一个
 * {@code PistonBaseBlock} 类 —— 活塞逻辑在单人里只经过这里。所以这里的判定必须和
 * 服务端那份<b>逐条一致</b>，否则单人里规则会「看着配了但没反应」。
 *
 * <p>目标签名（26.2 / 26.3 均一致，已对着映射后的 MC 类核对）：
 * {@code public static boolean PistonBaseBlock.isPushable(BlockState, Level, BlockPos, Direction,
 * boolean, Direction)}；{@code protected boolean PistonBaseBlock.triggerEvent(BlockState, Level,
 * BlockPos, int, int)}。
 */
@Mixin(PistonBaseBlock.class)
public abstract class ClientBlockStateBasePistonPushMixin {

    @Inject(
            method = "isPushable(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;ZLnet/minecraft/core/Direction;)Z",
            at = @At("RETURN"),
            cancellable = true
    )
    private static void villagerdetails$invertIsPushable(
            BlockState state,
            Level level,
            BlockPos pos,
            Direction direction,
            boolean allowDestroyable,
            Direction connectionDirection,
            CallbackInfoReturnable<Boolean> cir
    ) {
        // null = 名单里没有这个方块 → 不覆盖，原版算出来是什么就是什么
        Boolean override = CustomPistonPushBlockHandler.isConfigured(state.getBlock());
        if (override != null) {
            cir.setReturnValue(!cir.getReturnValue());
        }
    }

    /**
     * 缩回分支的第二道闸（{@code PushReaction} 必须是「能推能拉」），服务端那份同名注入的镜像。
     */
    @Redirect(
            method = "triggerEvent",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;getPistonPushReaction()Lnet/minecraft/world/level/material/PushReaction;"
            )
    )
    private PushReaction villagerdetails$movableReaction(BlockState state) {
        PushReaction vanilla = state.getPistonPushReaction();
        if (CustomPistonPushBlockHandler.isConfigured(state.getBlock()) == null) {
            return vanilla;
        }
        return PistonReactionCompat.movable();
    }
}
