package com.villagerdetails.mixin.block.portalBinding;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.handler.binding.block.portalbinding.PortalBindingService;
import com.villagerdetails.pojo.PortalDestination;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.BlockUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.PortalForcer;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Optional;

@Mixin(Entity.class)
public class EntityPortalMixin {

    @Unique
    private static final Logger log = LogManager.getLogger(EntityPortalMixin.class);

    // 26.3 把 handlePortal 里的 canTeleport + teleport 抽到了新方法 teleportToPortalDestination 里，
    // 所以重定向的靶子要跟着搬——两处都在 canTeleport 之后，语义一致。
    @Redirect(



            method = "handlePortal",

            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/world/entity/Entity;"
            )
    )
    private Entity villagerdetails$redirectPortalTeleport(Entity self, TeleportTransition transition) {
        if (!(self instanceof ServerPlayer player)) {
            return self.teleport(transition);
        }
        if (!RuleCache.isEnabled(player, RuleType.PORTAL_BINDING)) {
            return self.teleport(transition);
        }

        BlockPos startPos = null;
        if (player.portalProcess != null) {
            startPos = player.portalProcess.getEntryPosition();
        }
        if (startPos == null) {
            startPos = player.blockPosition();
        }

        PortalDestination dest = PortalBindingService.resolve(player, startPos);
        if (dest == null) {
            dest = PortalBindingService.resolve(player, player.blockPosition());
        }
        if (dest == null) {
            return self.teleport(transition);
        }

        ServerLevel targetLevel = player.level().getServer().getLevel(dest.dimension());
        if (targetLevel == null) {
            return self.teleport(transition);
        }

        BlockPos portalPos;

        if (RuleCache.isEnabled(player, RuleType.PORTAL_BINDING_ENHANCED)) {
            // 增强模式：直接传送到绑定坐标，不做任何门检查
            portalPos = dest.pos();
            log.debug("[PortalBinding] enhanced mode, teleport directly to {}", portalPos);
        } else {
            // 普通模式：精确判断绑定坐标是否是门 → 扫描 → 生成
            BlockState atDest = targetLevel.getBlockState(dest.pos());
            if (atDest.getBlock() instanceof NetherPortalBlock) {
                portalPos = bottomOfPortal(targetLevel, dest.pos());
                log.debug("[PortalBinding] binding position itself is portal at {}", portalPos);
            } else {
                portalPos = findPortalNear(targetLevel, dest.pos(), 16);
                if (portalPos != null) {
                    log.debug("[PortalBinding] found nearby portal at {}", portalPos);
                } else {
                    log.debug("[PortalBinding] no portal near {}, creating one", dest.pos());
                    portalPos = createPortalAt(targetLevel, dest.pos());
                    if (portalPos == null) {
                        log.debug("[PortalBinding] create failed, using simple portal");
                        portalPos = placeSimplePortal(targetLevel, dest.pos());
                    }
                }
            }
        }

        if (portalPos == null) {
            log.warn("[PortalBinding] all failed, fallback to vanilla, player={}, dest={}",
                    player.getName().getString(), dest);
            return self.teleport(transition);
        }

        TeleportTransition newTransition = new TeleportTransition(
                targetLevel,
                Vec3.atBottomCenterOf(portalPos),
                transition.deltaMovement(),
                transition.yRot(),
                transition.xRot(),
                transition.missingRespawnBlock(),
                transition.asPassenger(),
                transition.relatives(),
                transition.postTeleportTransition()
        );

        // 执行传送
        Entity result = self.teleport(newTransition);

        // 传送失败 → 错误日志
        if (result == null) {
            log.warn("[PortalBinding] teleport returned null, player={}, target={} {}",
                    player.getName().getString(), dest.dimension().identifier(), portalPos);
            return null;
        }

        // 重置 portalProcess，防止落点在门里被原版立即二次传送
        result.portalProcess = null;
        log.debug("[PortalBinding] cleared portalProcess after teleport");

        return result;
    }

    @Unique
    private static BlockPos findPortalNear(ServerLevel level, BlockPos center, int radius) {
        if (level.getBlockState(center).getBlock() instanceof NetherPortalBlock) {
            return bottomOfPortal(level, center);
        }

        BlockPos closest = null;
        double closestDist = Double.MAX_VALUE;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos p = center.offset(dx, dy, dz);
                    if (level.getBlockState(p).getBlock() instanceof NetherPortalBlock) {
                        double dist = p.distSqr(center);
                        if (dist < closestDist) {
                            closestDist = dist;
                            closest = p;
                        }
                    }
                }
            }
        }

        return closest == null ? null : bottomOfPortal(level, closest);
    }

    @Unique
    private static BlockPos bottomOfPortal(ServerLevel level, BlockPos p) {
        BlockPos bottom = p;
        while (level.getBlockState(bottom.below()).getBlock() instanceof NetherPortalBlock) {
            bottom = bottom.below();
        }
        return bottom;
    }

    @Unique
    private static BlockPos createPortalAt(ServerLevel level, BlockPos center) {
        try {
            PortalForcer portalForcer = new PortalForcer(level);
            Optional<BlockUtil.FoundRectangle> result =
                    portalForcer.createPortal(center, Direction.Axis.X);
            if (result.isPresent()) {
                return result.get().minCorner;
            }
        } catch (Throwable t) {
            log.warn("[PortalBinding] PortalForcer.createPortal failed, center={}", center, t);
        }
        return null;
    }

    @Unique
    private static BlockPos placeSimplePortal(ServerLevel level, BlockPos center) {
        BlockPos.MutableBlockPos mutableBlockPos = center.mutable();
        for (int y = center.getY(); y > level.getMinY(); y--) {
            mutableBlockPos.setY(y);
            BlockState state = level.getBlockState(mutableBlockPos);
            if (!state.getCollisionShape(level, mutableBlockPos).isEmpty()) {
                BlockPos portalPos = mutableBlockPos.above().immutable();
                level.setBlockAndUpdate(portalPos, Blocks.NETHER_PORTAL.defaultBlockState());
                return portalPos;
            }
        }
        log.warn("[PortalBinding] placeSimplePortal failed, no solid block below {}", center);
        return null;
    }
}