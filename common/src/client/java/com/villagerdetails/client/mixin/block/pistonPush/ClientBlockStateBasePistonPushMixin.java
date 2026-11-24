package com.villagerdetails.client.mixin.block.pistonPush;

import com.villagerdetails.client.handler.block.pistonPush.CustomPistonPushBlockHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 客户端侧的活塞推动判断，和服务端 {@code com.villagerdetails.mixin.block.pistonPush.PistonBaseBlockMixin}
 * <b>注入同一个目标方法、同一个注入点</b>，读的却是本地镜像
 * （{@link CustomPistonPushBlockHandler} → {@code PistonPushRuleCache}）。
 *
 * <p><b>为什么不共用服务端那份 Mixin</b>：服务端那份读配置文件和规则开关（{@code RuleCache} 在 server
 * 源集），客户端包里根本没有这些类。所以两份 Mixin 各自对着自己那端的 Handler，
 * 但 {@code @Mixin} 目标类、方法签名、注入点必须逐字一致 —— 目标方法一旦写错，
 * Mixin 只在运行期报错（{@code required: true} + {@code defaultRequire 1} 会直接把客户端启动打挂），
 * 编译期看不出来。
 *
 * <p>目标签名（26.2 / 26.3 均一致，已对着映射后的 MC 类核对）：
 * {@code public static boolean PistonBaseBlock.isPushable(BlockState, Level, BlockPos, Direction,
 * boolean, Direction)}。
 *
 * <p>注入点和语义与服务端一致：{@code @At("RETURN")} 拿原版的真实结果，名单里的方块取反。
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
        // null = 名单外 → 不覆盖，原版算出来是什么就是什么
        if (CustomPistonPushBlockHandler.isConfigured(state.getBlock()) != null) {
            cir.setReturnValue(!cir.getReturnValue());
        }
    }
}
