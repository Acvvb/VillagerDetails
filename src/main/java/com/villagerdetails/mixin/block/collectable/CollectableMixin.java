package com.villagerdetails.mixin.block.collectable;

import com.villagerdetails.handler.block.BlockRuleContext;
import com.villagerdetails.handler.block.collectable.CollectableHandler;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * 让规则里配置的方块「可采集」：当原版掉落为空时，补一个该方块自身的物品掉落。
 * <p>
 * 覆盖无战利品表（如基岩、刷怪笼）以及因缺少正确工具导致掉落为空的场景。
 * 未安装本 mod 的客户端玩家（上下文来自 {@link BlockRuleContext}）走原版掉落。
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class CollectableMixin {

    @Inject(method = "getDrops", at = @At("RETURN"), cancellable = true)
    private void villagerdetails$makeCollectable(LootParams.Builder builder,
                                                 CallbackInfoReturnable<List<ItemStack>> cir) {
        // 未安装本 mod 的客户端玩家 → 原版掉落
        if (BlockRuleContext.isVanillaClient()) return;

        BlockState state = (BlockState) (Object) this;
        if (!CollectableHandler.isConfigured(state.getBlock())) return;

        List<ItemStack> drops = cir.getReturnValue();
        // 原版已有掉落 → 不干预
        if (drops != null && !drops.isEmpty()) return;

        Item item = state.getBlock().asItem();
        if (item == Items.AIR) return;

        List<ItemStack> result = new ArrayList<>();
        if (drops != null) {
            result.addAll(drops);
        }
        result.add(new ItemStack(item));
        cir.setReturnValue(result);
    }
}
