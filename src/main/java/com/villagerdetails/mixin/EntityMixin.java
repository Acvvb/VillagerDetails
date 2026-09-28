package com.villagerdetails.mixin;


import com.villagerdetails.handler.noSqueeze.SqueezeHandler;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityMixin {

    /** 名单里的实体不能被其它实体推 */
    @Inject(method = "isPushable", at = @At("HEAD"), cancellable = true)
    private void villagerdetails$noPushable(CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (SqueezeHandler.isProtected(self)) {
            cir.setReturnValue(false);
        }
    }

    /** 名单里的实体不会去推别人，别人也不会推它 */
    @Inject(method = "push*", at = @At("HEAD"), cancellable = true)
    private void villagerdetails$noPush(Entity entity, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (SqueezeHandler.isProtected(self) || SqueezeHandler.isProtected(entity)) {
            ci.cancel();
        }
    }
}