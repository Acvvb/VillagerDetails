package com.villagerdetails.client.cache.impl;

import com.villagerdetails.cache.base.SessionState;
import com.villagerdetails.client.cache.base.ClientMapState;
import com.villagerdetails.client.network.requests.ClientRequests;
import com.villagerdetails.client.setting.util.McIds;
import com.villagerdetails.rule.descriptor.RuleDescriptor;
import net.minecraft.resources.Identifier;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「服务器配置项」里每条规则的 id 候选缓存（服务端按需下发，见 {@code rule_data_options}）。
 *
 * <p>为什么要有它：客户端现在不认识 {@code minecraft:block} 这类语义，补全候选和「新增一条时
 * 预填的原版值」只能问服务端要。打开某条规则的详情页时请求一次，之后整个会话复用。
 *
 * <p>客户端在这里做的最多只是<b>子串过滤</b>：拿服务端给的 id 列表筛一遍、按匹配程度排个序，
 * 不判断「这个 id 是不是方块」。
 *
 * <p>清单没到 / 服务端说太大了（{@link Options#tooMany()}）时，校验一律放行、由服务端最终判定，
 * 预填则留空 —— 宁可让服务端拒一次，也不在客户端猜规则语义。
 */
public final class RuleAuxCache implements SessionState {

    /**
     * 单例：扫包框架靠它拿实例，调用方统一写 {@code RuleAuxCache.INSTANCE.xxx()}
     */
    public static final RuleAuxCache INSTANCE = new RuleAuxCache();

    /**
     * 一条规则的候选清单。
     *
     * @param tooMany  服务端没发清单（id 空间太大 / 没有注册表）
     * @param defaults id → 原版值（预填用）
     */
    public record Options(boolean tooMany, Map<String, String> defaults) {

        /**
         * 这个 id 在清单里吗；没有清单时一律放行（交给服务端判定）
         */
        public boolean knows(String id) {
            return tooMany || defaults.containsKey(canonicalize(id));
        }
    }

    /**
     * 规则注册名 → 这份清单。{@link ClientMapState} 负责不可变快照的读写。
     */
    private final ClientMapState<String, Options> cache = new ClientMapState<>();

    /**
     * 已经请求过全量清单的规则（同一条规则只问服务端要一次）
     */
    private final Set<String> requested = ConcurrentHashMap.newKeySet();

    private RuleAuxCache() {
    }

    public Options get(String registerName) {
        return registerName == null ? null : cache.get(registerName);
    }

    public void put(String registerName, Options options) {
        if (registerName != null && options != null) {
            cache.put(registerName, options);
        }
    }

    /**
     * 打开详情页时调一次：这条规则还没要过清单就发一次请求。
     *
     * <p>要的是<b>全量</b>清单（补全 / 校验 / 预填都靠它）。进服时那份
     * {@code rule_data_defaults} 只含配置过的 id，不能顶替它，所以这里照旧按规则请求一次。
     */
    public void requestOnce(String registerName) {
        if (registerName == null || !requested.add(registerName)) {
            return;
        }
        ClientRequests.INSTANCE.requestRuleDataOptions(registerName);
    }

    /**
     * 这个 id 合法吗。
     *
     * <p>清单还没到 / 服务端说太大了 → 返回 true（放行），真正算数的是服务端那次校验。
     */
    public boolean knowsId(String registerName, String id) {
        Options options = get(registerName);
        if (options == null || options.tooMany()) {
            return true;
        }
        return options.knows(id);
    }

    /**
     * 新增一条时的原版值预填；没有就是 null
     */
    public String defaultValue(String registerName, String id) {
        Options options = get(registerName);
        return options == null ? null : options.defaults().get(canonicalize(id));
    }

    /**
     * 补全候选：拿服务端给的 id 列表按匹配程度排序取前 {@code limit} 条。
     *
     * <p>匹配优先级和本机补全（{@code McIds}）保持一致：完全相同 → 路径相同 → 路径前缀 → 包含。
     */
    public List<McIds.Entry> suggestions(String registerName, String query, int limit) {
        Options options = get(registerName);
        if (options == null || options.tooMany() || limit <= 0) {
            return List.of();
        }
        String text = normalize(query);
        if (text.isEmpty()) {
            return List.of();
        }

        List<String> ids = new ArrayList<>(options.defaults().keySet());
        Collections.sort(ids);

        List<McIds.Entry> hits = new ArrayList<>(limit);
        for (int rank = 0; rank <= LAST_RANK && hits.size() < limit; rank++) {
            for (String id : ids) {
                if (hits.size() >= limit) {
                    break;
                }
                if (rankOf(id, text) == rank) {
                    // 来源留空：这是服务端给的候选，界面上不需要再标「来自哪个注册表」
                    hits.add(new McIds.Entry(id, "", false));
                }
            }
        }
        return List.copyOf(hits);
    }

    /**
     * 断线时清空所有缓存和请求标记（由 {@link com.villagerdetails.client.cache.ClientSessionStates} 统一调用）。
     *
     * <p>{@code requested} 必须一起清：否则换服之后同一个注册名会被当成「已经要过了」，再也请求不出去。
     */
    @Override
    public void clearSession() {
        cache.clear();
        requested.clear();
    }

    // ==============================================================
    // 内部
    // ==============================================================

    private static final int LAST_RANK = 3;

    private static int rankOf(String id, String query) {
        if (id.equals(query)) {
            return 0;
        }
        String path = pathOf(id);
        if (path.equals(query)) {
            return 1;
        }
        if (path.startsWith(query)) {
            return 2;
        }
        return id.contains(query) ? 3 : -1;
    }

    private static String pathOf(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }

    private static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * id 的规范写法，用来和清单里的完整 id 对齐。
     *
     * <p>玩家在输入框里只写路径（{@code stone}）也要能对上清单里的 {@code minecraft:stone} ——
     * {@link Identifier#tryParse} 会补上默认命名空间，和服务端那边校验用的是同一套规则
     * （纯字符串比较会把 {@code stone} 判成不合法，玩家会莫名其妙加不进去）。
     */
    public String canonicalId(String raw) {
        return canonicalize(raw);
    }

    /**
     * {@link #canonicalId} 的实现。
     *
     * <p>单独留一份静态副本：{@link Options} 是嵌套 record（没有外部实例），
     * 它自己校验时只能调静态方法。两处逻辑必须一致，所以这里只写一份。
     */
    private static String canonicalize(String raw) {
        String text = normalize(raw);
        Identifier id = Identifier.tryParse(text);
        return id == null ? text : id.toString();
    }

    /**
     * 把服务端下发的 {@code id → 原版值} 列表收成一份有序清单
     */
    public Options of(boolean tooMany, List<RuleDescriptor.DataEntry> entries) {
        Map<String, String> defaults = new LinkedHashMap<>();
        for (RuleDescriptor.DataEntry entry : entries) {
            defaults.put(entry.id(), entry.value());
        }
        return new Options(tooMany, Collections.unmodifiableMap(defaults));
    }
}
