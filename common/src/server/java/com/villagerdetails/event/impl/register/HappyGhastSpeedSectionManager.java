package com.villagerdetails.event.impl.register;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.event.server.ServerEventRegisterServer;
import com.villagerdetails.handler.entity.happyghast.HappyGhastSpeedHandler;
import com.villagerdetails.rule.RuleChangeService;
import com.villagerdetails.rule.type.RuleType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;

/**
 * 「乐魂飞行速度」的事件入口。
 *
 * <p>只负责接线，业务在 {@link HappyGhastSpeedHandler}：
 * <ol>
 *     <li>{@code END_SERVER_TICK} → 每 20 tick 一次的残留加速回滚；</li>
 *     <li>{@code SERVER_STOPPING} → 清掉静态状态；</li>
 *     <li>{@link RuleCache#addListener} → 规则一改，骑乘中的乐魂立刻按新速度重算，
 *         不用下坐再上坐。</li>
 * </ol>
 *
 * <p>放在 {@code event.impl.register} 包下 = 由 {@code ServerEventManager} 扫包自动注册，
 * 不用改任何注册清单。
 *
 * <p><b>为什么用 {@code RuleCache.addListener} 而不是 {@code RuleCallbacks.register}：</b>
 * 后者是"每条规则一个槽"（{@code EnumMap.put}），而 {@code ExchangeModule} 已经给<b>每一条</b>
 * 规则登记了审计日志回调 —— 在这里注册同一个规则会把那条日志顶掉。{@code RuleCache} 的监听器
 * 是列表，可以并存。
 */
public final class HappyGhastSpeedSectionManager implements ServerEventRegisterServer {

    private HappyGhastSpeedSectionManager() {
    }

    @Override
    public void register() {
        ServerTickEvents.END_SERVER_TICK.register(HappyGhastSpeedHandler::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(_ -> HappyGhastSpeedHandler.resetState());
        RuleCache.addListener(HappyGhastSpeedSectionManager::onRuleChanged);
    }

    /**
     * 规则变更回调：只认这两条规则，其余直接返回。
     *
     * <p>写 {@code AttributeInstance} 必须在主线程。收包端（{@code ServerReceiver}）本来就已经
     * {@code context.server().execute(...)} 回主线程了，这里再兜一层是因为
     * {@link RuleCache#setState} 是公开入口，将来任何从异步回调里改规则的地方都不该踩线程问题。
     * 已经在主线程时 {@code MinecraftServer#execute} 是<b>立即执行</b>（
     * {@code BlockableEventLoop.scheduleExecutables()} 返回 {@code !isSameThread()}），
     * 所以不会平白多拖一 tick。
     */
    private static void onRuleChanged(RuleType type, String oldValue, String newValue) {
        if (type != RuleType.HAPPY_GHAST_SPEED && type != RuleType.GLOBAL_SPEED_BYPASS) {
            return;
        }
        // 启动 / 重载回灌阶段没人骑乐魂，交给周期巡检就行，不必在回灌里做全量遍历
        if (RuleChangeService.isReloading) {
            return;
        }
        MinecraftServer server = RuleCache.getServer();
        if (server == null) {
            return;
        }
        server.execute(() -> HappyGhastSpeedHandler.refreshAll(server));
    }
}
