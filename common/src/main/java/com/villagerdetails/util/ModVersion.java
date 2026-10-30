package com.villagerdetails.util;

import com.villagerdetails.VillagerDetails;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;

import java.util.Optional;

/**
 * 模组自身的版本号读取与比较（<b>两端共用</b>）。
 *
 * <p>版本号来源固定是 {@code FabricLoader} 的模组元数据 —— 也就是 {@code fabric.mod.json} 里那个
 * {@code version}（例如 {@code 1.1.0+26.2}）。开发环境里它同样有效，所以不写死常量，
 * 免得改了 {@code gradle.properties} 之后忘记同步。
 *
 * <p>比较用 Fabric 自己的 {@link Version}：它认 semver，会正确处理 {@code +26.2} 这类构建元数据
 * （按 semver 规则，构建元数据<b>不参与</b>优先级比较）。自己手写字符串比较会在
 * {@code 1.10.0} vs {@code 1.9.0} 这种地方出错。
 */
public final class ModVersion {

    private ModVersion() {
    }

    /**
     * 本机（当前 JVM）安装的模组版本原文，例如 {@code 1.1.0+26.2}；拿不到返回空串
     */
    public static String current() {
        return FabricLoader.getInstance().getModContainer(VillagerDetails.MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("");
    }

    /**
     * 解析一个版本字符串；<b>不是合法 semver 一律返回 {@link Optional#empty()}</b>。
     *
     * <p>这里有个必须踩住的坑：{@link Version#parse} 对 {@code "not-a-version"} 这种垃圾串
     * <b>不会抛异常</b>，而是解析成「字符串版本」（{@link Version} 的实现类不是
     * {@link SemanticVersion}），而这种字符串版本在比较时<b>排在所有 semver 之后</b> ——
     * 也就是说，只判断「解析有没有抛异常」，一个改过的客户端发个乱码就能被判定成「版本更高、兼容」，
     * 直接绕过握手下限。所以必须再核一次「解析结果是不是真 semver」。
     *
     * <p>模组自己的 {@code fabric.mod.json} 版本（{@code 1.1.0+26.2}）永远是 semver，
     * 所以这条限制不会误伤正常客户端。
     */
    public static Optional<Version> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            Version parsed = Version.parse(raw.trim());
            if (!(parsed instanceof SemanticVersion)) {
                // 字符串版本（垃圾输入）——当作不可解析，交由调用方按「不兼容」处理
                return Optional.empty();
            }
            return Optional.of(parsed);
        } catch (VersionParsingException e) {
            return Optional.empty();
        }
    }

    /**
     * {@code reported} 是否<b>不低于</b> {@code minimum}。
     *
     * <p>任一版本串解析失败一律返回 {@code false}（= 判定为不兼容）。
     * 这个方向是刻意选的：握手是「服务端主导更新」的把关，宁可拒掉一个说不清版本的客户端，
     * 也不能让改过包的客户端靠发个乱码就绕过检查。
     */
    public static boolean isAtLeast(String reported, String minimum) {
        Optional<Version> actual = parse(reported);
        Optional<Version> floor = parse(minimum);
        if (actual.isEmpty() || floor.isEmpty()) {
            return false;
        }
        return actual.get().compareTo(floor.get()) >= 0;
    }

    /**
     * {@code reported} 是否<b>不高于</b> {@code maximum}。任一解析失败返回 {@code false}。
     */
    public static boolean isAtMost(String reported, String maximum) {
        Optional<Version> actual = parse(reported);
        Optional<Version> ceiling = parse(maximum);
        if (actual.isEmpty() || ceiling.isEmpty()) {
            return false;
        }
        return actual.get().compareTo(ceiling.get()) <= 0;
    }

    /**
     * 两个版本是否<b>同一优先级</b>（构建元数据不参与比较）。
     *
     * <p>所以拉黑 {@code "1.1.5"} 会同时拉黑 {@code 1.1.5+26.2} 与 {@code 1.1.5+26.3} ——
     * 这正是「精确拉黑某个版本」想要的效果；用字符串 {@code equals} 的话，
     * 服务端里写 {@code "1.1.5"} 永远匹配不上客户端上报的 {@code "1.1.5+26.2"}。
     *
     * <p>任一解析失败返回 {@code false}（拼错的条目不会误伤正常版本）。
     */
    public static boolean sameVersion(String a, String b) {
        Optional<Version> left = parse(a);
        Optional<Version> right = parse(b);
        if (left.isEmpty() || right.isEmpty()) {
            return false;
        }
        return left.get().compareTo(right.get()) == 0;
    }
}
