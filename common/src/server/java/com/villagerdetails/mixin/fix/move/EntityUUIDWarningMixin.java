package com.villagerdetails.mixin.fix.move;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PersistentEntitySectionManager.class)
public class EntityUUIDWarningMixin {

    /**
     * 拦截 addEntityUuid 方法，这是原版真正打印重复 UUID 警告的地方。
     * 原版参数 T 在编译后会被擦除为 EntityAccess。
     */
    @Inject(
            method = "addEntityUuid",
            at = @At("HEAD"),
            cancellable = true
    )
    private void villagerdetails$suppressUUIDWarning(EntityAccess entity, CallbackInfoReturnable<Boolean> cir) {
        // 如果开启了规则
        if (RuleCache.isEnabled(RuleType.GLOBAL_SPEED_BYPASS)) {
            // 强制将 this 转换为泛型管理器以调用 isLoaded()
            PersistentEntitySectionManager<?> self = (PersistentEntitySectionManager<?>) (Object) this;

            // 如果该 UUID 已经存在，直接返回 false，完美跳过 LOGGER.warn 打印
            if (self.isLoaded(entity.getUUID())) {
                cir.setReturnValue(false);
            }
        }
    }
}