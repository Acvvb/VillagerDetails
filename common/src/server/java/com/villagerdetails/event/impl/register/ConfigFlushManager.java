package com.villagerdetails.event.impl.register;

import com.villagerdetails.concurrent.ServerThreadManager;
import com.villagerdetails.config.impl.UnifiedConfigFile;
import com.villagerdetails.event.server.ServerEventRegisterServer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/**
 * 统一配置的「合并落盘」接线：把一整 tick 内的所有配置改动收敛成一次写盘。
 *
 * <p>只负责接线，逻辑在 {@link UnifiedConfigFile#flush()}：
 * <ul>
 *     <li>{@code END_SERVER_TICK} → 本 tick 有改动才写，没改动是空操作；</li>
 *     <li>停服收尾不在这里 —— 由 {@link ServerThreadManager#addPreShutdownHook} 登记，
 *         保证跑在线程池关闭<b>之前</b>（见 {@code VillagerDetailsServer#init}）。</li>
 * </ul>
 *
 * <p><b>为什么用 {@code END_SERVER_TICK} 而不是 {@code START_SERVER_TICK}：</b>
 * 命令、方块交互、传送这些改动都发生在 tick 内部，放在 tick 末尾才能把「这一 tick 改了些什么」
 * 一次性收走；放在开头会白等一整 tick 才落盘，停服时更容易漏。
 *
 * <p>放在 {@code event.impl.register} 包下 = 由 {@code ServerEventManager} 扫包自动注册，
 * 不用改任何注册清单。
 */
public final class ConfigFlushManager implements ServerEventRegisterServer {

    private ConfigFlushManager() {
    }

    @Override
    public void register() {
        ServerTickEvents.END_SERVER_TICK.register(_ -> UnifiedConfigFile.flush());
    }
}
