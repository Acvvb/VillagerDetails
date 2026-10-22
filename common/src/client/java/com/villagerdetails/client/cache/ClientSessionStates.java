package com.villagerdetails.client.cache;

import com.villagerdetails.client.cache.base.ClientSessionState;
import com.villagerdetails.client.network.tool.SyncSubscriptionClient;
import com.villagerdetails.network.channel.ChannelScanner;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端状态的<b>统一生命周期管理器</b>。
 *
 * <p>扫 {@link #STATE_PACKAGE} 包，把所有 {@link ClientSessionState} 的实现收进一张
 * 「断线要清哪些东西」的清单；{@link #clearAll()} 就是那张清单的唯一执行入口
 * （断线回调在 {@code VillagerDetailsClient} 里，全客户端只有那一处）。
 *
 * <p><b>为什么扫包而不写清单</b>：新增一份会话状态时不用再回来改这个文件。
 * 代价是「漏登记」从编译期错误变成了运行期静默失效（包名写错、忘了实现接口、
 * 类被挪出 impl 包），所以这里做了两道兜底：
 * <ol>
 *     <li>扫到 0 个 → 记一条 ERROR（见 {@link ChannelScanner#checkNotEmpty}）；</li>
 *     <li>启动时把扫到的类名打一行 INFO，少了谁一眼就能看出来。</li>
 * </ol>
 */
public final class ClientSessionStates {

    private static final Logger LOGGER = LogManager.getLogger(ClientSessionStates.class);

    /**
     * 缓存所在的包（<b>含子包</b>）。
     *
     * <p>改包名一定要同步改这里：写错不会编译报错，只会静默扫到 0 个、
     * 于是断线时什么都不清（下一局带着上一局的数据继续跑）。
     */
    private static final String STATE_PACKAGE = "com.villagerdetails.client.cache.impl";

    private static final List<Runnable> CLEAR_ACTIONS = new ArrayList<>();

    private static boolean registered;

    private ClientSessionStates() {
    }

    /**
     * 扫包接管所有缓存的生命周期；客户端初始化时调一次（重复调用是空操作）。
     */
    public static void registerAll() {
        if (registered) {
            return;
        }
        registered = true;

        List<ChannelScanner.Failure> failures = new ArrayList<>();
        List<String> names = new ArrayList<>();

        int states = ChannelScanner.forEach(STATE_PACKAGE, ClientSessionStates.class, ClientSessionState.class,
                state -> {
                    String name = state.getClass().getSimpleName();
                    names.add(name);
                    CLEAR_ACTIONS.add(guard(name, state::clearSession));
                }, failures);

        // 不在 impl 包下的特例：它不是「缓存」，但同样只活在一次会话里（记着哪些订阅已经通知过服务端）。
        // 统一走 guard，和扫包得来的那批一样有名字、有异常隔离。
        names.add("SyncSubscriptionClient");
        CLEAR_ACTIONS.add(guard("SyncSubscriptionClient", SyncSubscriptionClient.INSTANCE::clearSession));

        ChannelScanner.checkNotEmpty(states, STATE_PACKAGE, "客户端状态扫包", failures);
        ChannelScanner.reportFailures(LOGGER, "客户端状态扫包", failures);

        LOGGER.info("客户端缓存扫包完成：{} 个状态接入断线清理 {}", CLEAR_ACTIONS.size(), names);
    }

    /**
     * 清空所有会话状态（断线时由唯一的那处 DISCONNECT 回调调用，也可以手动调）。
     *
     * <p>先确保扫过包：万一启动期漏了 {@link #registerAll()}，断线时至少还能补扫一次，
     * 而不是默默什么都不清。
     */
    public static void clearAll() {
        registerAll();
        for (Runnable action : CLEAR_ACTIONS) {
            action.run();
        }
    }

    // ==============================================================
    // 内部工具
    // ==============================================================

    /**
     * 给每个清理动作包一层 try-catch 并带上类名：一个状态清不掉不该连累其它状态，
     * 但日志里必须能一眼看出是哪个类炸的。
     */
    private static Runnable guard(String name, Runnable action) {
        return () -> {
            try {
                action.run();
            } catch (Throwable t) {
                LOGGER.error("清理客户端会话状态 [{}] 失败", name, t);
            }
        };
    }
}
