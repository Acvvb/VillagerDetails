package com.villagerdetails.client.feature;

import com.villagerdetails.network.channel.ChannelScanner;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 客户端功能模块（渲染器、事件监听等）的扫包注册器。
 *
 * <p>复用 {@link ChannelScanner} 的公共取数与实例化机制，自动发现并注册
 * 所有实现了 {@link ClientFeature} 的具体类 —— 加一个渲染器不用再回来改入口类。
 *
 * <p>注册顺序按类名排序（{@link ChannelScanner#forEach} 里统一排过），
 * 所以同一个版本每次启动的注册顺序完全一致，不受文件系统 / jar 条目顺序影响。
 *
 * <p>幂等：由 {@link #REGISTERED} 抢位保证只真正注册一次 —— 重复注册会让渲染回调挂两遍。
 */
public final class ClientFeatureScanner {

    private static final Logger log = LogManager.getLogger(ClientFeatureScanner.class);

    private static final String BASE_PACKAGE = "com.villagerdetails.client.feature";

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private ClientFeatureScanner() {
    }

    /**
     * 取包并自动实例化、注册所有 ClientFeature；客户端初始化时调一次
     */
    public static void registerAll() {
        if (!REGISTERED.compareAndSet(false, true)) {
            // 防呆：全局只该有一个直接触发源（VillagerDetailsClient.onInitializeClient）
            log.warn("检测到 ClientFeatureScanner.registerAll() 被重复调用，本次已忽略。"
                    + "请检查客户端入口是否重复注册！");
            return;
        }

        List<ChannelScanner.Failure> failures = new ArrayList<>();
        List<String> names = new ArrayList<>();

        // 复用底座的 forEach：取包 -> 过滤接口 -> 实例化 -> 执行 action
        int count = ChannelScanner.forEach(
                BASE_PACKAGE,
                ClientFeatureScanner.class,
                ClientFeature.class,
                feature -> {
                    feature.register();
                    // 半注册的类不该出现在「已注册」清单里
                    names.add(feature.getClass().getSimpleName());
                },
                failures
        );

        ChannelScanner.checkNotEmpty(count, BASE_PACKAGE, "客户端功能发现", failures);
        ChannelScanner.reportFailures(log, "客户端功能注册", failures);

        log.info("客户端功能扫包完成：{} 个模块已注册 {}", count, names);
    }
}
