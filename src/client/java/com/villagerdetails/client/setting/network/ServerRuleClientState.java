package com.villagerdetails.client.setting.network;

import com.villagerdetails.network.ServerRuleRequestPayload;
import com.villagerdetails.rule.type.RuleType;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 客户端侧的「服务器规则」状态：
 * <ul>
 *     <li>{@link #isAvailable()}：服务器是否装了本 mod（能收我们的 C2S payload）</li>
 *     <li>{@link #getVersion()}：每次收到服务端同步就 +1，界面靠它发现自己该刷新了</li>
 *     <li>{@link #isRuleVisible}：这条规则服务端有没有下发过 ——
 *         <b>没下发就是没权限看</b>，界面直接不渲染它</li>
 * </ul>
 *
 * <p>规则本身的状态存在 {@code RuleCache} 里（客户端一份镜像，和服务端同一套读取方式）。
 */
public final class ServerRuleClientState {

    /**
     * 每次收到服务端同步就 +1，界面靠它发现自己该刷新了。
     *
     * <p>用 {@link AtomicInteger} 而不是 {@code volatile int}：{@code version++} 是「读-改-写」，
     * 放在 volatile 字段上不是原子操作（IDEA 会报「volatile 字段上的非原子操作」）。
     */
    private static final AtomicInteger VERSION = new AtomicInteger();

    private static volatile boolean available;

    /**
     * 服务端最近一次同步过来的规则注册名。
     *
     * <p>每次同步都<b>整份替换</b>：这次没带上来的规则 = 服务端判定我们没权限，
     * 所以被下 op 之后，原来能看的规则会自己从界面里消失。
     */
    private static volatile Set<String> syncedRules = Set.of();

    private ServerRuleClientState() {
    }

    /** 服务器有没有装本 mod（未联机 / 单机集成服未加载时按 false 处理） */
    public static boolean isAvailable() {
        if (!ClientPlayNetworking.canSend(ServerRuleRequestPayload.TYPE)) {
            available = false;
            return false;
        }
        available = true;
        return true;
    }

    /** 上一次检测的结果（不重新检测，绘制时用） */
    public static boolean isAvailableCached() {
        return available;
    }

    public static void setAvailable(boolean value) {
        available = value;
    }

    /** 收到同步后整份替换（别合并：没同步过来的就是没权限） */
    public static void setSyncedRules(Collection<String> registerNames) {
        syncedRules = Set.copyOf(registerNames);
    }

    /** 断开连接时清掉，免得下次进别的服务器还留着上一份 */
    public static void clearSyncedRules() {
        syncedRules = Set.of();
    }

    /** 这条规则服务端下发过吗（= 我们有权限看它） */
    public static boolean isRuleVisible(RuleType type) {
        return type != null && syncedRules.contains(type.getRegisterName());
    }

    /**
     * 「服务器」这个一级分类要不要显示：服务器装了本 mod，而且至少还有一条规则是有权限看的。
     * 一条都没有就整个分类都藏掉。
     */
    public static boolean isServerCategoryVisible() {
        return isAvailable() && !syncedRules.isEmpty();
    }

    public static int getVersion() {
        return VERSION.get();
    }

    /** 收到服务端同步后调用 */
    public static void bumpVersion() {
        VERSION.incrementAndGet();
    }
}
