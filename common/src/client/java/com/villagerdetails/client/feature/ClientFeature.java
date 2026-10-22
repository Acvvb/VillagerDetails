package com.villagerdetails.client.feature;

/**
 * 客户端功能模块（渲染器、事件监听、tick 检查……）的统一契约。
 *
 * <p>实现类由 {@code ClientFeatureScanner} 扫 {@code com.villagerdetails.client.feature} 包
 * （含子包）自动发现并调用一次 {@link #register()}，所以<b>加一个渲染器不用改入口类</b>。
 *
 * <p>被扫到的类要能被实例化：给一个 {@code public static final Xxx INSTANCE}，
 * 或者一个无参构造（私有也行，扫包底座会 {@code setAccessible}）。
 *
 * <p>本接口的实现都是「注册完就没事了」的：状态不要存在实例字段里再到处传，
 * 真正要读的数据请放 {@code client.cache.impl} 下的缓存，由扫包框架统一接管断线清理。
 */
public interface ClientFeature {

    /**
     * 注册该功能（事件监听、渲染器等）；客户端初始化时会被调一次
     */
    void register();
}
