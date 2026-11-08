package com.villagerdetails.mixin.fix.log;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 「重复 UUID」警告的<b>日志抑制</b>（只影响日志，不改变实体是否被登记）。
 *
 * <p>原版 {@code PersistentEntitySectionManager#addEntityUuid} 在 UUID 已经存在时会打一条
 * WARN 并返回 {@code false}。这里做的是同一件事，只是把那行 WARN 省掉 —— 返回值仍然是
 * {@code false}，调用方拿到的结论与原版完全一致。
 *
 * <p><b>它和"移动"没有关系</b>：开关复用的是 {@link RuleType#GLOBAL_SPEED_BYPASS}，
 * 因为这类警告通常正是高速位移 / 反复重进世界时刷出来的。早期它被放在
 * {@code mixin/fix/move} 包里，容易让人误以为是位移逻辑的一部分，现移到 {@code fix/log}。
 */
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
            if (self.isLoaded(entity.getUUID())) {
                cir.setReturnValue(false);
            }
        }
    }
}
