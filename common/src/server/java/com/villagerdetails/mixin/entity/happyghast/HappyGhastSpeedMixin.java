package com.villagerdetails.mixin.entity.happyghast;

import com.villagerdetails.handler.entity.happyghast.HappyGhastSpeedHandler;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 乐魂飞行速度 —— <b>只保留上下坐两个注入点</b>，速度逻辑全部在
 * {@link HappyGhastSpeedHandler}（周期巡检、规则变更也要用同一套"期望值"计算，
 * 所以不能再藏在 Mixin 的 {@code @Unique} 方法里）。
 *
 * <p>注入位置与历史实现完全一致：{@code addPassenger} / {@code removePassenger} 的 TAIL。
 */
@Mixin(HappyGhast.class)
public abstract class HappyGhastSpeedMixin {

    @Inject(method = "addPassenger", at = @At("TAIL"))
    private void onAddPassenger(CallbackInfo ci) {
        HappyGhastSpeedHandler.apply((HappyGhast) (Object) this);
    }

    @Inject(method = "removePassenger", at = @At("TAIL"))
    private void onRemovePassenger(CallbackInfo ci) {
        HappyGhastSpeedHandler.apply((HappyGhast) (Object) this);
    }
}
