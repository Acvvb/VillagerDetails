package com.villagerdetails.mixin.animal;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;

import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Sheep.class)
public class SheepMixin {

    @Inject(method = "shear", at = @At("TAIL"))
    private void villagerdetails$restoreWool(ServerLevel level, SoundSource soundSource, ItemStack tool, CallbackInfo ci) {
        if (!RuleCache.isEnabled(RuleType.INFINITE_SHEAR)) return;

        Sheep self = (Sheep) (Object) this;
        self.setSheared(false);
    }
}