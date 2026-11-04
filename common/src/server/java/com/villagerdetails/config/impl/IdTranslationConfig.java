package com.villagerdetails.config.impl;

import com.villagerdetails.config.impl.base.AbstractWorldConfig;
import com.villagerdetails.config.impl.data.VillagerTradeItemRegistry;
import com.villagerdetails.config.impl.dto.Cleaned;
import com.villagerdetails.util.json.JsonIO;
import net.minecraft.resources.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static com.villagerdetails.config.impl.data.IdTranslationData.collectSearchableNames;
import static com.villagerdetails.config.impl.data.IdTranslationData.dropAmbiguousNames;
import static com.villagerdetails.config.impl.data.IdTranslationData.firstNames;
import static com.villagerdetails.config.impl.data.IdTranslationData.parse;
import static com.villagerdetails.config.impl.data.IdTranslationData.parseId;
import static com.villagerdetails.config.impl.data.IdTranslationData.reverse;

/**
 * 名字 ↔ 原始 id 的映射表（存档内 {@code villagerdetails/trade_index.json}）。
 *
 * <p>生命周期与 IO 全部来自 {@link AbstractWorldConfig}（和 {@code UnifiedConfigFile} 同一套），
 * 本类只回答「写哪个文件、怎么把 JSON 读成索引、清空成什么样」。
 *
 * <p>落盘内容就是 {@link #index} 本身：{@code 原始 id → 名字列表}，Gson 直接序列化成
 * {@code {"minecraft:book": ["书"], ...}}。内存里的三张查询表（{@code nameToId} / {@code idToName} /
 * {@code searchableNames}）由 {@link #applyIndex} 从它一次性重建 —— 读盘时重建一次，
 * 停服清空时重建回默认表，运行期间不再变动，所以可以放心被任意线程读。
 *
 * <p>所有默认物品、方块、附魔的映射，统一来源于 {@link VillagerTradeItemRegistry}。
 */
public final class IdTranslationConfig extends AbstractWorldConfig {

    // 全局唯一实例
    public static final IdTranslationConfig INSTANCE = new IdTranslationConfig();

    private static final Logger log = LogManager.getLogger(IdTranslationConfig.class);

    /** 相对存档根目录的文件位置：{@code <world>/villagerdetails/trade_index.json}。 */
    private static final String SAVE_PATH = "villagerdetails/trade_index.json";

    // ==============================================================
    // 数据源（由 VillagerTradeItemRegistry 提供）
    // ==============================================================

    /** 内置默认表：文件不存在 / 解析失败时的兜底内容。 */
    private static final Map<String, List<String>> DEFAULT_INDEX = VillagerTradeItemRegistry.getDefaultIndex();

    /** 名字 → 原始 id（含所有别名）。每次 {@link #applyIndex} 整体替换成不可变 Map。 */
    private static volatile Map<String, String> nameToId = reverse(DEFAULT_INDEX);

    /** 原始 id → 首个名字（展示用）。每次 {@link #applyIndex} 整体替换成不可变 Map。 */
    private static volatile Map<String, String> idToName = firstNames(DEFAULT_INDEX);

    /** Tab 补全用的全部可搜索名字（id 与名字的并集）。每次 {@link #applyIndex} 整体替换。 */
    private static volatile Set<String> searchableNames = collectSearchableNames(idToName, nameToId);

    /**
     * 当前内存里的索引，同时也是<b>落盘内容</b>。
     */
    private Map<String, List<String>> index = DEFAULT_INDEX;

    /**
     * 私有构造：全局只允许 {@link #INSTANCE} 存在一个实例。
     *
     * <p>构造时父类会把它登记进静态表，{@link AbstractWorldConfig#tickAll()} 因此自动覆盖到它。
     */
    private IdTranslationConfig() {
    }

    // ==============================================================
    // 父类要求的三件事 + 数据
    // ==============================================================

    /**
     * {@inheritDoc}
     *
     * <p>本配置固定写在存档的 {@code villagerdetails/trade_index.json}。
     */
    @Override
    protected String savePath() {
        return SAVE_PATH;
    }

    /**
     * {@inheritDoc}
     *
     * <p>直接交 {@link #index} 本身：它是一个 {@code 原始 id → 名字列表} 的 Map，
     * Gson 会当场序列化成和手写格式完全一致的 JSON。
     */
    @Override
    protected Object getDataToSave() {
        return index;
    }

    /**
     * {@inheritDoc}
     *
     * <p>注意注册名是 {@code TradeIndexConfig}（历史名字，`/ec c reload` 的点名清单里有它），
     * 和类名不一致是刻意的，不要「顺手改一致」。
     */
    @Override
    public String configName() {
        return "TradeIndexConfig";
    }

    /**
     * 磁盘 → 内存：把 {@code trade_index.json} 读成索引，并重建三张查询表。
     *
     * <p>几条兜底路径，<b>都不会让服务器起不来</b>：
     * <ul>
     *     <li>没有 server（停服后）→ 用默认表，不写盘；</li>
     *     <li>文件不存在（首次运行）→ 用默认表 + {@link #markDirty()}，
     *         由 tick 末尾异步把文件建出来；</li>
     *     <li>读盘 / 解析异常 → 记 ERROR，用默认表继续；</li>
     *     <li>同一个名字挂在多个 id 上 → 由 {@link # dropAmbiguousNames(Map) } 整体作废这些名字，
     *         内容确实变了才 {@link #markDirty()} 把文件改写成干净版本。</li>
     * </ul>
     */
    @Override
    protected void load() {
        index = DEFAULT_INDEX;
        Path file = currentFile();
        if (file == null) {
            applyIndex(DEFAULT_INDEX);
            return;
        }

        if (!Files.exists(file)) {
            // 首次运行：内存用默认表，标脏让 tick 末尾把它异步写出去（含建目录 / 建文件）
            applyIndex(DEFAULT_INDEX);
            markDirty();
            log.info("交易索引文件不存在，已使用内置默认表（异步落盘中）：{}", file);
            return;
        }

        Map<String, List<String>> parsed;
        try {
            parsed = parse(JsonIO.readObject(file));
        } catch (Exception e) {
            log.error("读取交易索引失败，回落默认表：{}", file, e);
            applyIndex(DEFAULT_INDEX);
            return;
        }

        Cleaned cleaned = dropAmbiguousNames(parsed);
        index = cleaned.index();
        if (!cleaned.index().equals(parsed)) {
            log.warn("交易索引里有 {} 个名字同时挂在多个 id 上，已整体作废并从文件里抹除：{}",
                    cleaned.dropped().size(), cleaned.dropped());
            markDirty();
        }

        applyIndex(index);
        log.info("已加载交易索引：{} 个交易条目（{}）", nameToId.size(), file);
    }

    /**
     * {@inheritDoc}
     *
     * <p>停服复位时把索引和三张查询表一起退回<b>内置默认表</b>：
     * 之后就算还有谁在查名字（例如延迟到达的网络包 / 日志），拿到的也是合理结果，
     * 而不是上一个存档的内容。
     */
    @Override
    protected void clearData() {
        index = DEFAULT_INDEX;
        applyIndex(DEFAULT_INDEX);
    }

    // ==============================================================
    // 内存视图
    // ==============================================================

    /**
     * 用一份索引重建三张查询表（整体替换，不原地改）。
     *
     * <p>「整体替换 + {@code volatile}」是这里唯一需要的线程安全手段：重建期间读方看到的
     * 要么是旧的那一组 Map，要么是新的那一组，不会看到改到一半的状态。
     *
     * @param index {@code 原始 id → 名字列表}
     */
    private static void applyIndex(Map<String, List<String>> index) {
        Map<String, String> ids = reverse(index);
        Map<String, String> names = firstNames(index);
        nameToId = ids;
        idToName = names;
        searchableNames = collectSearchableNames(names, ids);
    }

    /**
     * Tab 补全用的全部可搜索名字（id 与别名的并集）。
     *
     * <p>返回的是不可变快照，调用方直接遍历即可。
     */
    public static Set<String> getAllSearchableNames() {
        return searchableNames;
    }

    // ==============================================================
    // 解析入口
    // ==============================================================

    /**
     * 把玩家输入（中文名 / 纯路径 / 带命名空间的 id）解析成 {@link Identifier}。
     *
     * <p>顺序：先按别名表查（支持中文名），查不到再当 id 直接解析；
     * 输入里的全角空格会被归一成半角，避免中文输入法带进来的不可见字符。
     *
     * @param raw 原始输入；{@code null} / 空白返回 {@code null}
     * @return 解析出的 id；解析不出来返回 {@code null}
     */
    public static Identifier resolveId(String raw) {
        if (raw == null) return null;

        String trimmed = raw.replace('\u3000', ' ').trim();
        if (trimmed.isEmpty()) return null;

        // 直接从 nameToId 查（中文名或纯路径都能查到）
        String mapped = nameToId.get(trimmed);
        if (mapped != null) {
            Identifier id = parseId(mapped);
            if (id != null) return id;
        }

        // 直接当 id 用
        return parseId(trimmed);
    }

    /**
     * id → 展示名（当前语言下的中文别名）。
     *
     * <p>查找顺序：完整 id（{@code minecraft:book}）→ 纯路径（{@code mending} /
     * {@code enchanted_book}，附魔与附魔书就是这么登记的）。
     * 非 {@code minecraft} 命名空间的 id 不做纯路径兜底，避免把模组 id 错配成原版名字。
     *
     * @param id 目标 id；{@code null} 返回 {@code null}
     * @return 展示名；别名表里没有则 {@code null}（调用方自行回落翻译键）
     */
    public static String displayNameOf(Identifier id) {
        if (id == null) {
            return null;
        }

        // 1. 尝试直接查完整 ID（如 minecraft:book）
        String direct = idToName.get(id.toString());
        if (direct != null) {
            return direct;
        }

        // 2. 如果不是原版命名空间，且上面没查到，直接返回 null
        if (!"minecraft".equals(id.getNamespace())) {
            return null;
        }

        // 3. 尝试查纯路径（如附魔、附魔书的 mending / enchanted_book）
        return idToName.get(id.getPath());
    }

    /**
     * {@link #displayNameOf(Identifier)} 的字符串版本，内部按同一套规则把字符串解析成 id。
     *
     * @param idText 形如 {@code minecraft:book} / {@code book} 的文本；解析不出来返回 {@code null}
     */
    public static String displayNameOf(String idText) {
        return displayNameOf(parseId(idText));
    }
}