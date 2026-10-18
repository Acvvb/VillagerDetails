package com.villagerdetails.client.setting.network;

import com.villagerdetails.client.network.sync.ClientRequests;
import com.villagerdetails.client.setting.util.McIds;
import com.villagerdetails.rule.descriptor.RuleDescriptor;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
public final class RuleAuxCache {

    /**
     * 一条规则的候选清单。
     *
     * @param tooMany  服务端没发清单（id 空间太大 / 没有注册表）
     * @param defaults id → 原版值（预填用）
     */
    public record Options(boolean tooMany, Map<String, String> defaults) {

        /** 这个 id 在清单里吗；没有清单时一律放行（交给服务端判定） */
        public boolean knows(String id) {
            return tooMany || defaults.containsKey(canonicalId(id));
        }
    }

    private static final Map<String, Options> CACHE = new ConcurrentHashMap<>();

    /**
     * 已经请求过全量清单的规则（同一条规则只问服务端要一次）
     */
    private static final Set<String> REQUESTED = ConcurrentHashMap.newKeySet();

    private RuleAuxCache() {
    }

    public static Options get(String registerName) {
        return registerName == null ? null : CACHE.get(registerName);
    }

    public static void put(String registerName, Options options) {
        if (registerName != null && options != null) {
            CACHE.put(registerName, options);
        }
    }

    /**
     * 打开详情页时调一次：这条规则还没要过清单就发一次请求。
     *
     * <p>要的是<b>全量</b>清单（补全 / 校验 / 预填都靠它）。进服时那份
     * {@code rule_data_defaults} 只含配置过的 id，不能顶替它，所以这里照旧按规则请求一次。
     */
    public static void requestOnce(String registerName) {
        if (registerName == null || !REQUESTED.add(registerName)) {
            return;
        }
        ClientRequests.requestRuleDataOptions(registerName);
    }

    /**
     * 这个 id 合法吗。
     *
     * <p>清单还没到 / 服务端说太大了 → 返回 true（放行），真正算数的是服务端那次校验。
     */
    public static boolean knowsId(String registerName, String id) {
        Options options = get(registerName);
        if (options == null || options.tooMany()) {
            return true;
        }
        return options.knows(id);
    }

    /**
     * 新增一条时的原版值预填；没有就是 null
     */
    public static String defaultValue(String registerName, String id) {
        Options options = get(registerName);
        return options == null ? null : options.defaults().get(canonicalId(id));
    }

    /**
     * 补全候选：拿服务端给的 id 列表按匹配程度排序取前 {@code limit} 条。
     *
     * <p>匹配优先级和本机补全（{@code McIds}）保持一致：完全相同 → 路径相同 → 路径前缀 → 包含。
     */
    public static List<McIds.Entry> suggestions(String registerName, String query, int limit) {
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
     * 断开连接时清空（由 {@link com.villagerdetails.client.cache.ClientSessionStates} 统一调用）
     */
    public static void clear() {
        CACHE.clear();
        REQUESTED.clear();
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
    public static String canonicalId(String raw) {
        String text = normalize(raw);
        Identifier id = Identifier.tryParse(text);
        return id == null ? text : id.toString();
    }

    /**
     * 把服务端下发的 {@code id → 原版值} 列表收成一份有序清单
     */
    public static Options of(boolean tooMany, List<RuleDescriptor.DataEntry> entries) {
        Map<String, String> defaults = new LinkedHashMap<>();
        for (RuleDescriptor.DataEntry entry : entries) {
            defaults.put(entry.id(), entry.value());
        }
        return new Options(tooMany, Collections.unmodifiableMap(defaults));
    }
}
