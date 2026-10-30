package com.villagerdetails.network.channel;

import com.villagerdetails.util.ModVersion;

import java.util.List;

/**
 * 网络版本兼容策略的<b>唯一事实来源</b>，全部是编译期常量。
 *
 * <p><b>为什么从外部配置改成硬编码</b>：这些值直接决定「协议能不能对得上」。
 * 交给服主 / 玩家改 {@code .json} 的风险是：一个手滑把下限写成乱码或缺省，
 * 要么全员被拒（服务器废掉），要么全员放行（旧客户端收到解不开的包而崩）。
 * 协议兼容属于开发者的发布决定，不属于运行时配置，所以固化在代码里 ——
 * 想改就改这里、重新打包。
 *
 * <p>策略只有两条，<b>没有最高版本上限</b>：
 * <ol>
 *     <li>{@link #BLOCKED_VERSIONS}：精确拉黑，优先级最高；</li>
 *     <li>{@link #MIN_COMPATIBLE_VERSION}：低于它拒。</li>
 * </ol>
 * 高版本客户端连低版本服务端<b>默认放行</b>：新版客户端自己能判断对面缺什么
 * （发包一侧有 {@code canSend} 能力探测），没必要在服务端这一侧提前拦。
 *
 * <p><b>升级版本时记得回来改 {@code MIN_COMPATIBLE_VERSION}</b>。
 * 忘了改的后果是偏「宽松」的（老客户端仍放行），不会把服务器锁死；
 * 但如果你确实想强制同版本，就必须手动抬这个常量 —— 它刻意<b>不</b>跟随
 * {@code gradle.properties} 的 {@code modVersion} 自动漂移，否则每发一版兼容性都会
 * 在没人注意的情况下变一次。
 */
public final class NetworkConstants {

    private NetworkConstants() {
    }

    /**
     * 最低兼容的模组版本。低于它的客户端会在握手时被断开。
     *
     * <p>比较用 semver（{@link ModVersion}），所以 {@code 1.1.0} 能匹配客户端的
     * {@code 1.1.0+26.2} —— 构建元数据不参与优先级比较。
     */
    public static final String MIN_COMPATIBLE_VERSION = "1.1.0";

    /**
     * 精确拉黑的版本列表，例如 {@code List.of("1.1.5")}。留空表示不拉黑任何版本。
     *
     * <p>元素按 semver 等值比较：拉黑 {@code "1.1.5"} 会同时命中客户端上报的
     * {@code 1.1.5+26.2}、{@code 1.1.5+26.3}（构建元数据忽略）。
     * 写了非法版本串的条目会被忽略，不影响其它条目。
     */
    public static final List<String> BLOCKED_VERSIONS = List.of();

    /**
     * 一次版本判定的结论。用枚举而不是布尔，是为了让「为什么被拒」能直接进日志和提示文案。
     */
    public enum Verdict {
        /** 放行 */
        OK,
        /** 低于下限 */
        TOO_OLD,
        /** 命中拉黑名单 */
        BLOCKED,
        /** 上报的版本串解析不出来（改过的客户端 / 未来格式） */
        UNPARSEABLE
    }

    /**
     * 判定一个客户端上报的版本能不能连。
     *
     * <p>顺序：拉黑（优先级最高）→ 版本串合法性 → 下限。
     * 没有最高版本检查 —— 高版本客户端一律放行。
     */
    public static Verdict evaluate(String reported) {
        for (String blocked : BLOCKED_VERSIONS) {
            if (ModVersion.sameVersion(reported, blocked)) {
                return Verdict.BLOCKED;
            }
        }
        if (ModVersion.parse(reported).isEmpty()) {
            return Verdict.UNPARSEABLE;
        }
        if (!ModVersion.isAtLeast(reported, MIN_COMPATIBLE_VERSION)) {
            return Verdict.TOO_OLD;
        }
        return Verdict.OK;
    }
}
