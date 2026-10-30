package com.villagerdetails.event;

import com.villagerdetails.event.server.ServerEventRegisterServer;
import com.villagerdetails.network.channel.ChannelScanner;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 服务端事件注册的<b>唯一注册入口</b>（服务端侧对应物是客户端的 {@code ClientChannels}）。
 *
 * <p>不手写事件清单，而是扫 {@link #EVENT_PACKAGE} 包（<b>含子包</b>，所以
 * {@code .impl.register} / {@code .impl.listener} 都会扫到）下的
 * {@link ServerEventRegisterServer} 实现，逐个调 {@link ServerEventRegisterServer#register()}。
 * 加一组新事件<b>不用改这个文件</b>：类放进那个包、实现接口就行。
 *
 * <p>扫包 / 实例化 / 失败汇总复用 main 的 {@link ChannelScanner}，和 {@code ServerChannels}、
 * {@code ClientChannels}、{@code ClientSessionStates} 全部是同一套底座：
 * <ul>
 *     <li>只认<b>具体类</b>（接口 / 抽象类会被跳过）；</li>
 *     <li>实例化优先读 {@code public static final INSTANCE}，没有就退回无参构造（私有也会 setAccessible）；</li>
 *     <li>按<b>类名排序</b>后注册，保证每次启动顺序一致；</li>
 *     <li>单个类抛异常只记一条失败，不连累其它类；</li>
 *     <li>一个都没扫到会记 ERROR（包名写错 / 子包没打进产物时不会静默失效）。</li>
 * </ul>
 *
 * <p>幂等：{@code VillagerDetailsServer} 的 {@code main} 与 {@code server} 两个入口点都会调
 * {@link #registerAll()}，所以这里只真正跑一次 —— 否则事件会被注册两遍、回调触发两次。
 */
public final class ServerEventManager {

    private static final Logger log = LogManager.getLogger(ServerEventManager.class);

    /**
     * 事件注册类所在的包（含子包）；改包名一定要同步改这里
     */
    private static final String EVENT_PACKAGE = "com.villagerdetails.event.impl";

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private ServerEventManager() {
    }

    /**
     * 注册全部服务端事件；初始化时调一次（重复调用是空操作）。
     *
     * <p>先抢标志再干活（{@code compareAndSet}），保留原来「不会被重入注册两遍」的语义。
     */
    public static void registerAll() {
        if (!REGISTERED.compareAndSet(false, true)) {
            // 防呆：全局只该有一个直接触发源（VillagerDetailsServer.init）
            log.warn("检测到 ServerEventManager.registerAll() 被重复调用，本次已忽略。"
                    + "请检查接入口点是否重复注册！");
            return;
        }

        List<ChannelScanner.Failure> failures = new ArrayList<>();
        List<String> names = new ArrayList<>();

        int count = ChannelScanner.forEach(EVENT_PACKAGE, ServerEventManager.class, ServerEventRegisterServer.class,
                group -> {
                    group.register();
                    // 半注册的类不该出现在「已注册」清单里
                    names.add(group.getClass().getSimpleName());
                }, failures);

        ChannelScanner.checkNotEmpty(count, EVENT_PACKAGE, "服务端事件发现", failures);

        log.info("服务端事件扫包完成：{} 个事件组已注册 {}", count, names);

        ChannelScanner.reportFailures(log, "服务端事件注册", failures);
    }
}
