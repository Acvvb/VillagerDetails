package com.villagerdetails.mixin.block.portalBinding;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.handler.binding.block.portalbinding.PortalBindingService;
import com.villagerdetails.handler.binding.block.portalbinding.PortalLocator;
import com.villagerdetails.pojo.dto.PortalDestination;
import com.villagerdetails.rule.type.RuleType;
import com.villagerdetails.util.SendMessengerUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 传送门绑定注入：所有实体在全局规则开启时按绑定目的地传送。
 *
 * <p>26.3 把 handlePortal 里的 canTeleport + teleport 抽到了新方法
 * {@code teleportToPortalDestination}，所以重定向靶子与 26.2 不同，逻辑两边保持一致。
 *
 * <ul>
 *   <li>规则开关：全局 {@code RuleCache.isEnabled(RuleType.PORTAL_BINDING)}；</li>
 *   <li>落点：玩家用门底中心；非玩家优先门外一格（避免卡门 15 秒被弹回）；</li>
 *   <li>增强模式（{@code PORTAL_BINDING_ENHANCED}）仅对玩家开放，且走异步落脚点搜索。</li>
 * </ul>
 */
@Mixin(Entity.class)
public class EntityPortalMixin {

    @Unique
    private static final Logger log = LogManager.getLogger(EntityPortalMixin.class);

    @Redirect(
            method = "teleportToPortalDestination",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/world/entity/Entity;"
            )
    )
    private Entity villagerdetails$redirectPortalTeleport(Entity self, TeleportTransition transition) {
        if (!(self.level() instanceof ServerLevel sourceLevel)) {
            return self.teleport(transition);
        }

        // 全局规则：规则状态非 false 即对所有实体生效
        // 用无参重载，不再按玩家判断——怪物/物品没有权限概念
        if (!RuleCache.isEnabled(RuleType.PORTAL_BINDING)) {
            return self.teleport(transition);
        }

        BlockPos currentPos = self.blockPosition();
        BlockPos startPos = (self.portalProcess != null)
                ? self.portalProcess.getEntryPosition()
                : null;
        if (startPos == null) startPos = currentPos;

        PortalDestination dest = PortalBindingService.resolve(self, startPos);
        if (dest == null && !startPos.equals(currentPos)) {
            dest = PortalBindingService.resolve(self, currentPos);
        }
        if (dest == null) {
            return self.teleport(transition);
        }

        ServerLevel targetLevel = sourceLevel.getServer().getLevel(dest.dimension());
        if (targetLevel == null) {
            return self.teleport(transition);
        }

        // 增强模式（仅玩家）：落脚点由原版异步搜索给出，传送推迟到 future 完成的回调里，
        // 这里必须提前返回，不能再走下面的同步传送（否则会把人先丢到未验证的坐标上）。
        if (self instanceof ServerPlayer player
                && RuleCache.isEnabled(player, RuleType.PORTAL_BINDING_ENHANCED)) {
            return teleportEnhanced(player, targetLevel, dest, transition);
        }

        BlockPos portalPos = resolvePortalPos(targetLevel, dest);
        if (portalPos == null) {
            log.warn("[PortalBinding] all failed, fallback to vanilla, entity={}, dest={}",
                    describe(self), dest);
            return self.teleport(transition);
        }

        // 原版行为：所有实体（无论玩家、怪物还是物品）均落在门底正中心
        Vec3 spawnPos = Vec3.atBottomCenterOf(portalPos);

        return teleportToSpawn(self, targetLevel, spawnPos, transition, dest);
    }

    /**
     * 增强模式（仅玩家）：把落脚点交给 {@link PortalLocator#forceSpawnAt} 异步找，
     * 完成后再回服务端主线程真正传送。
     *
     * <p>同步返回 {@code self}：原版把这个 {@code teleport(...)} 的返回值直接丢弃，
     * 所以提前返回不会卡住它。
     *
     * <p>排队前先清 {@code portalProcess}：搜索可能跳好几个 tick（findSpawn 自己加 ticket
     * 加载 chunk），不清的话过完门动画会重新计时，同一个玩家又触发一次传送入口，
     * 于是排上两次异步传送。回调里再用 {@code isRemoved()} 兜底：
     * 传送成功时旧 {@code ServerPlayer} 会被标成 removed，重复的回调就此作废。
     *
     * <p>回写缓存：异步 {@code findSpawn} 给的是普通地面，{@code portalBottom} 为 {@code null}，
     * <b>绝不能</b>把那个坐标当成门缓存；只有 {@code forceSpawnAt} 的同步快路径（区域内已有门）
     * 才会带出门底，此时顺手 {@link PortalBindingService#cachePortal}，
     * 让下次普通模式和增强模式都直接 O(1) 命中。
     */
    @Unique
    private Entity teleportEnhanced(ServerPlayer player, ServerLevel targetLevel,
                                    PortalDestination dest, TeleportTransition origin) {
        MinecraftServer server = targetLevel.getServer();
        player.portalProcess = null;

        PortalLocator.forceSpawnAt(targetLevel, dest.pos(), dest.regionMin(), dest.regionMax())
                .thenAccept(result -> server.execute(() -> {
                    if (result == null || result.spawnPos() == null) {
                        // 找不到安全落脚点 → 不传送，别让玩家掉虚空/卡进方块
                        SendMessengerUtils.sendOverlayOrBroadcast(player,
                                Component.translatable("command.portal_binding.enhanced.no_ground"));
                        player.portalProcess = null;
                        log.warn("[PortalBinding] enhanced mode found no safe spot, teleport cancelled, "
                                + "player={}, target={} {}", player.getName().getString(),
                                dest.dimension().identifier(), dest.pos());
                        return;
                    }
                    if (player.isRemoved()) {
                        // 等待期间已被另一次传送替换掉，这个重复回调直接丢弃
                        return;
                    }
                    // 只有「区域内已有门」才会带出门底；异步 findSpawn 给的是普通地面，不能写缓存
                    if (result.portalBottom() != null) {
                        PortalBindingService.cachePortal(dest, result.portalBottom());
                        log.debug("[PortalBinding] enhanced mode hit portal, cached at {}",
                                result.portalBottom());
                    }
                    teleportToSpawn(player, targetLevel, result.spawnPos(), origin, dest);
                }));

        return player;
    }

    /**
     * 按新落点重建 {@link TeleportTransition} 并传送；成功后清掉 portalProcess
     * （落点本身可能在门里，不清会被原版立刻二次弹回）。
     *
     * <p>同步路径与增强模式的异步回调共用这一份构造，免得两边参数对不齐。
     */
    @Unique
    private @Nullable Entity teleportToSpawn(Entity self, ServerLevel targetLevel, Vec3 spawnPos,
                                             TeleportTransition origin, PortalDestination dest) {
        TeleportTransition newTransition = new TeleportTransition(
                targetLevel,
                spawnPos,
                origin.deltaMovement(),
                origin.yRot(),
                origin.xRot(),
                origin.missingRespawnBlock(),
                origin.asPassenger(),
                origin.relatives(),
                origin.postTeleportTransition()
        );

        Entity result = self.teleport(newTransition);

        if (result == null) {
            log.warn("[PortalBinding] teleport returned null, entity={}, target={} {}",
                    describe(self), dest.dimension().identifier(), spawnPos);
            return null;
        }

        result.portalProcess = null;
        if (result != self) {
            self.portalProcess = null;
        }
        log.debug("[PortalBinding] cleared portalProcess after teleport, entity={}", describe(self));

        return result;
    }

    /**
     * 在目标维度确定最终门底坐标。
     *
     * <p>只服务非增强路径（增强模式已由 {@link #teleportEnhanced} 另走异步通道），
     * 因为直传绑定坐标不做检查，对怪物/物品可能掉虚空或窒息。
     *
     * <p>有区域信息（绑定 {@code resolve} 出来的目的地）只在区域内选门/建门；
     * 旧构造的目的地无区域，退回以目标点为中心的通用定位。
     */
    @Unique
    private @Nullable BlockPos resolvePortalPos(ServerLevel targetLevel, PortalDestination dest) {
        BlockPos cached = dest.cachedPortalPos();
        if (cached != null && targetLevel.getBlockState(cached).getBlock() instanceof NetherPortalBlock) {
            log.debug("[PortalBinding] cache hit at {}", cached);
            return PortalLocator.bottomOfPortal(targetLevel, cached);
        }

        BlockPos located = dest.hasRegion()
                ? PortalLocator.locateInRegion(targetLevel, dest.pos(), dest.regionMin(), dest.regionMax())
                : PortalLocator.locateOrCreate(targetLevel, dest.pos());

        if (located != null) {
            PortalBindingService.cachePortal(dest, located);
        }
        return located;
    }

    /** 日志用实体描述：玩家用名字，非玩家用类型 + UUID 短串。 */
    @Unique
    private static String describe(Entity entity) {
        if (entity instanceof ServerPlayer player) {
            return player.getName().getString();
        }
        return entity.getType() + "#" + entity.getStringUUID().substring(0, 8);
    }
}
