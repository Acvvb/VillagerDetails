package com.villagerdetails.mixin.entity.piglinTrade;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.util.ParseUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

import static com.villagerdetails.rule.type.RuleType.PIGLIN_BARTER_DELAY;
import static com.villagerdetails.rule.type.RuleType.PIGLIN_GOLD_BLOCK_MULTIPLY;

/**
 * 猪灵金块交易 Mixin。
 *
 * <p>包含三个职责：
 * <ol>
 *   <li><b>拾取转换</b>：{@code pickUpItem} 时把金块替换成金锭 + 打上标记</li>
 *   <li><b>产出 ×9</b>：{@code getBarterResponseItems} 时用标记识别，跑 9 次独立随机</li>
 *   <li><b>等待时间</b>：{@code admireGoldItem} 时按配置调整"欣赏金锭"时长，
 *       金块自动 ×9</li>
 * </ol>
 *
 * <p>三者通过命令标签 {@link #MULTIPLY_TAG} 串联：拾取时打上，产出时消费掉。
 */
@Mixin(PiglinAi.class)
public abstract class PiglinAiMixin {

    // ============================================================
    // 常量
    // ============================================================

    /** 金块标记：拾取时打上，产出时消费 */
    @Unique
    private static final String MULTIPLY_TAG = "VillagerDetailsGoldBlockMultiply";

    /** 原版基准等待时间：6 秒（会被转换成 tick） */
    @Unique
    private static final int VANILLA_DELAY_SECONDS = 6;

    /** 金块交易的时间倍数（固定 9，与金块 = 9 金锭对应） */
    @Unique
    private static final int GOLD_BLOCK_MULTIPLIER = 9;

    /** 每秒 tick 数 */
    @Unique
    private static final int TICKS_PER_SECOND = 20;

    /** 重入保护：避免在产出注入里调原方法时无限递归 */
    @Unique
    private static final ThreadLocal<Boolean> MULTIPLYING = ThreadLocal.withInitial(() -> false);

    // ============================================================
    // Shadow：声明原版方法，供产出阶段递归调用
    // ============================================================

    @Shadow
    private static List<ItemStack> getBarterResponseItems(Piglin body) {
        throw new AssertionError("shadow");
    }

    // ============================================================
    // ① 拾取前：金块 → 1 个金锭 + 打标签
    // ============================================================

    @Inject(method = "pickUpItem", at = @At("HEAD"))
    private static void villagerdetails$convertGoldBlock(ServerLevel level, Piglin body,
                                                         ItemEntity itemEntity,
                                                         CallbackInfo ci) {
        if (!RuleCache.isEnabled(PIGLIN_GOLD_BLOCK_MULTIPLY)) return;

        ItemStack stack = itemEntity.getItem();
        if (!stack.is(Items.GOLD_BLOCK)) return;

        int blockCount = stack.getCount();

        // 地面替换成金锭（数量与金块数相同，通常为 1）
        itemEntity.setItem(new ItemStack(Items.GOLD_INGOT, blockCount));

        // 打标签：本次交易产出会跑 9 次独立随机、等待时间会 ×9
        body.addTag(MULTIPLY_TAG);
    }

    // ============================================================
    // ② 产出阶段：跑 9 次独立随机，合并结果
    // ============================================================

    @Inject(method = "getBarterResponseItems", at = @At("RETURN"), cancellable = true)
    private static void villagerdetails$multiplyOutput(Piglin body, CallbackInfoReturnable<List<ItemStack>> cir) {
        // 递归保护：内部调原方法时不进入此分支
        if (MULTIPLYING.get()) return;

        if (!RuleCache.isEnabled(PIGLIN_GOLD_BLOCK_MULTIPLY)) return;
        if (!body.entityTags().contains(MULTIPLY_TAG)) return;   // ★ 按 IDE 实际 API 替换

        body.removeTag(MULTIPLY_TAG);

        MULTIPLYING.set(true);
        try {
            // 第 1 次：原方法已经跑出的结果
            List<ItemStack> merged = new ArrayList<>(cir.getReturnValue());

            // 第 2~9 次：独立再跑 8 次原版战利品表
            for (int i = 0; i < GOLD_BLOCK_MULTIPLIER - 1; i++) {
                List<ItemStack> one = getBarterResponseItems(body);
                if (one != null && !one.isEmpty()) {
                    merged.addAll(one);
                }
            }

            cir.setReturnValue(merged);
        } finally {
            MULTIPLYING.set(false);
        }
    }

    // ============================================================
    // ③ 调整等待时间：覆盖 admireGoldItem 里的 119 tick
    // ============================================================

    @Inject(method = "admireGoldItem", at = @At("HEAD"), cancellable = true)
    private static void villagerdetails$modifyAdmireDuration(LivingEntity body, CallbackInfo ci) {
        // 读取用户配置的秒数，默认 6 秒；转成 tick 后减 1（对齐原版 119 语义）
        int baseDelay = ParseUtils.toInt(
                RuleCache.getState(PIGLIN_BARTER_DELAY), VANILLA_DELAY_SECONDS)
                * TICKS_PER_SECOND - 1;

        long duration = isGoldBlockTrade(body)
                ? (long) baseDelay * GOLD_BLOCK_MULTIPLIER
                : baseDelay;

        body.getBrain().setMemoryWithExpiry(MemoryModuleType.ADMIRING_ITEM, true, duration);
        ci.cancel();
    }

    /** 是否本次为"金块交易"：靠命令标签判断（拾取阶段已打上） */
    @Unique
    private static boolean isGoldBlockTrade(LivingEntity body) {
        return body instanceof Piglin piglin
                && piglin.entityTags().contains(MULTIPLY_TAG);
    }
}