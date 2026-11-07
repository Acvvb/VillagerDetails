package com.villagerdetails.config.impl.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.villagerdetails.config.impl.dto.ParsedIndex;
import com.villagerdetails.pojo.dto.TradeItemInfo;
import com.villagerdetails.pojo.dto.VillagerTradeInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 村民交易索引（{@code villager_trade_cache.json}）的 <b>JSON 编解码</b>：
 * 只做「内存数据 ⇄ JSON 文本」的纯转换。
 *
 * <p>从 {@code VillagerTradeCacheConfig} 里拆出来的一层，目的是让那边只负责生命周期与落盘调度。
 * 两个入口：
 * <ul>
 *     <li>{@link #encode(Map)}：内存 → 要落盘的 {@link JsonObject}（写盘时由存储类调用）；</li>
 *     <li>{@link #parse(JsonObject)}：磁盘上的 {@link JsonObject} → 内存（读盘时由存储类调用）。</li>
 * </ul>
 *
 * <p>本类<b>不碰文件、不碰生命周期、不做调度</b>，任何容器都能直接调，因此可以单独测试。
 *
 * <h2>文件格式（{@code format: 2}）</h2>
 * <pre>{@code
 * {
 *   "format": 2,
 *   "savedAt": 1730000000000,
 *   "villagers": [
 *     {
 *       "uuid": "....",
 *       "dimension": "minecraft:overworld",
 *       "pos": [x, y, z],
 *       "offers": [
 *         { "result": "minecraft:emerald", "resultCount": 1,
 *           "price1": "minecraft:book", "price1Count": 3,
 *           "price2": "...", "price2Count": 0,        // 只有真的有两段价格时才写
 *           "maxUses": 12,                            // 只有 > 0 时才写
 *           "enchantments": [ { "id": "...", "level": 5 } ]  // 没有附魔时不写
 *         }
 *       ]
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <p>解析一律<b>尽量宽容</b>：单条记录坏掉只计数跳过（交给存储类决定要不要重写文件），
 * 不会因为一条脏数据把整份索引丢掉。
 */
public final class VillagerTradeIndexJson {

    private static final Logger log = LogManager.getLogger(VillagerTradeIndexJson.class);

    /**
     * 当前文件格式版本。旧版本（{@code format != 2}）整体作废，等新区块卸载时重建。
     */
    public static final int FORMAT_VERSION = 2;

    /**
     * 私有构造：本类只有静态编解码方法，不允许实例化。
     */
    private VillagerTradeIndexJson() {
    }

    // ==============================================================
    // 编码：内存 → JSON
    // ==============================================================

    /**
     * 把内存里的索引转成最终要落盘的 JSON 对象。
     *
     * <p>顺序固定为「按 UUID 排序」：同一份数据每次写出来的文本都一样，
     * 比对文件差异时不会因为遍历顺序变化而出现假改动。
     *
     * @param cache 村民 UUID → 交易快照；{@code null} 视为空
     * @return {@code format / savedAt / villagers} 三个字段的根对象
     */
    public static JsonObject encode(Map<UUID, VillagerTradeInfo> cache) {
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT_VERSION);
        root.addProperty("savedAt", System.currentTimeMillis());

        JsonArray villagers = new JsonArray();
        if (cache != null) {
            cache.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> villagers.add(villagerToJson(entry.getValue())));
        }
        root.add("villagers", villagers);
        return root;
    }

    /**
     * 单只村民 → JSON 对象（{@link #encode} 的组成部分）。
     *
     * <p>只写 id / 坐标 / 维度 / 交易表，不写任何展示文本与缓存命中信息
     * —— 那些都是运行期派生数据，写进文件只会随语言和实现变化。
     *
     * @param info 一只村民的交易快照
     * @return 该村民的 JSON 表示
     */
    public static JsonObject villagerToJson(VillagerTradeInfo info) {
        JsonObject object = new JsonObject();
        object.addProperty("uuid", info.uuid().toString());
        object.addProperty("dimension", info.dimension().identifier().toString());

        JsonArray pos = new JsonArray(3);
        pos.add(info.pos().getX());
        pos.add(info.pos().getY());
        pos.add(info.pos().getZ());
        object.add("pos", pos);

        JsonArray offers = new JsonArray();
        for (TradeItemInfo offer : info.offers()) {
            offers.add(offerToJson(offer));
        }
        object.add("offers", offers);
        return object;
    }

    /**
     * 单笔交易 → JSON 对象：先写必填字段，再按需补可选字段。
     *
     * <p>可选字段（第二段价格 / 可交易次数 / 附魔）只在真的有值时才写，
     * 这样文件里不会出现一堆 {@code "price2Count": 0} 这种噪音。
     *
     * @param offer 一笔交易快照
     * @return 该交易的 JSON 表示
     */
    private static JsonObject offerToJson(TradeItemInfo offer) {
        JsonObject item = createJsonObject(offer);
        if (!offer.enchantments().isEmpty()) {
            JsonArray enchantments = new JsonArray();
            for (TradeItemInfo.EnchantmentEntry enchantment : offer.enchantments()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("id", enchantment.id());
                entry.addProperty("level", enchantment.level());
                enchantments.add(entry);
            }
            item.add("enchantments", enchantments);
        }
        return item;
    }

    /**
     * 一笔交易的必填字段 + 结构性的可选字段（第二段价格、可交易次数）。
     *
     * <p>拆出来是为了让 {@link #offerToJson} 只管附魔那一段，可读性好一点。
     *
     * @param offer 一笔交易快照
     * @return 尚未写入附魔的 JSON 对象
     */
    private static @NonNull JsonObject createJsonObject(TradeItemInfo offer) {
        JsonObject item = new JsonObject();
        item.addProperty("result", offer.resultItemId());
        item.addProperty("resultCount", offer.resultCount());
        item.addProperty("price1", offer.price1Id());
        item.addProperty("price1Count", offer.price1Count());
        if (offer.hasSecondPrice()) {
            item.addProperty("price2", offer.price2Id());
            item.addProperty("price2Count", offer.price2Count());
        }
        if (offer.maxUses() > 0) {
            item.addProperty("maxUses", offer.maxUses());
        }
        return item;
    }

    // ==============================================================
    // 解码：JSON → 内存
    // ==============================================================

    /**
     * 把磁盘上的 JSON 对象解析成内存数据。
     *
     * <p>格式版本对不上时返回空结果（{@code skipped == 0}）：旧格式直接作废，
     * 等区块卸载时重新建索引，而不是把不认识的字段硬塞进来。
     *
     * <p>单条记录解析失败只计数跳过，并把条数带回去（{@code skipped}），
     * 由存储类决定要不要把文件重写干净。
     *
     * @param root 从文件读出来的 JSON 对象；{@code null} 视为空
     * @return 解析出的村民表 + 被跳过的条数
     */
    public static ParsedIndex parse(JsonObject root) {
        Map<UUID, VillagerTradeInfo> result = new HashMap<>();
        if (root == null) {
            return new ParsedIndex(result, 0);
        }

        int format = root.has("format") && root.get("format").isJsonPrimitive()
                ? root.get("format").getAsInt() : FORMAT_VERSION;

        if (format != FORMAT_VERSION) {
            log.info("村民交易索引是旧格式（文件 {}，当前 {}）：旧格式已作废，将随区块卸载重建", format, FORMAT_VERSION);
            return new ParsedIndex(result, 0);
        }

        JsonElement villagersElement = root.get("villagers");
        if (villagersElement == null || !villagersElement.isJsonArray()) {
            return new ParsedIndex(result, 0);
        }

        int skipped = 0;
        for (JsonElement element : villagersElement.getAsJsonArray()) {
            try {
                VillagerTradeInfo info = parseVillager(element.getAsJsonObject());
                if (info != null) result.put(info.uuid(), info);
                else skipped++;
            } catch (Exception e) {
                skipped++;
            }
        }
        if (skipped > 0) {
            log.warn("村民交易索引里有 {} 条记录解析失败，已跳过（其余 {} 条正常）", skipped, result.size());
        }
        return new ParsedIndex(result, skipped);
    }

    /**
     * 单只村民记录 → {@link VillagerTradeInfo}。
     *
     * <p>三种情况返回 {@code null}（交给 {@link #parse} 计为「跳过」）：
     * 坐标数组缺失 / 不足 3 个元素；交易表为空；交易表里全是坏记录。
     * uuid / dimension 解析失败会直接抛异常，由 {@link #parse} 的 try 兜住。
     *
     * @param object 村民记录对象
     * @return 解析结果；无效记录返回 {@code null}
     */
    private static VillagerTradeInfo parseVillager(JsonObject object) {
        UUID uuid = UUID.fromString(object.get("uuid").getAsString());
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
                Identifier.parse(object.get("dimension").getAsString()));

        JsonArray posArray = object.getAsJsonArray("pos");
        if (posArray == null || posArray.size() < 3) return null;

        BlockPos pos = new BlockPos(posArray.get(0).getAsInt(),
                posArray.get(1).getAsInt(), posArray.get(2).getAsInt());

        List<TradeItemInfo> offers = new ArrayList<>();
        JsonElement offersElement = object.get("offers");
        if (offersElement != null && offersElement.isJsonArray()) {
            for (JsonElement offerElement : offersElement.getAsJsonArray()) {
                TradeItemInfo offer = parseOffer(offerElement.getAsJsonObject());
                if (offer != null) offers.add(offer);
            }
        }
        if (offers.isEmpty()) return null;

        return new VillagerTradeInfo(uuid, pos, dimension, offers);
    }

    /**
     * 单笔交易记录 → {@link TradeItemInfo}。
     *
     * <p>卖出物 id 为空视为无效记录（返回 {@code null}）；其余字段缺失时按
     * {@link #string} / {@link #intValue} 的默认值（空串 / 0）填充，
     * 保证老文件里少几个字段也能读出来。
     *
     * @param object 交易记录对象
     * @return 解析结果；无效记录返回 {@code null}
     */
    private static TradeItemInfo parseOffer(JsonObject object) {
        String resultItemId = string(object, "result");
        if (resultItemId.isBlank()) return null;

        return new TradeItemInfo(
                resultItemId,
                intValue(object, "resultCount"),
                string(object, "price1"),
                intValue(object, "price1Count"),
                string(object, "price2"),
                intValue(object, "price2Count"),
                intValue(object, "maxUses"),
                parseEnchantments(object));
    }

    /**
     * 解析一笔交易的附魔列表。
     *
     * <p>没有 {@code enchantments} 字段（普通物品）→ 空列表；单个附魔条目坏掉
     * 只跳过那一条（{@code id} 为空或解析抛异常），不影响其余条目。
     *
     * @param object 交易记录对象
     * @return 附魔条目列表；永不为 {@code null}
     */
    private static List<TradeItemInfo.EnchantmentEntry> parseEnchantments(JsonObject object) {
        JsonElement element = object.get("enchantments");
        if (element == null || !element.isJsonArray()) return List.of();

        List<TradeItemInfo.EnchantmentEntry> entries = new ArrayList<>();
        for (JsonElement enchantmentElement : element.getAsJsonArray()) {
            try {
                JsonObject enchantment = enchantmentElement.getAsJsonObject();
                String id = string(enchantment, "id");
                if (!id.isBlank()) {
                    entries.add(new TradeItemInfo.EnchantmentEntry(id, intValue(enchantment, "level")));
                }
            } catch (Exception ignored) {}
        }
        return entries;
    }

    /**
     * 读一个字符串字段，缺失 / 不是标量时返回空串（不抛异常）。
     *
     * @param object 所在对象
     * @param key    字段名
     * @return 字段值；没有则 {@code ""}
     */
    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || !element.isJsonPrimitive() ? "" : element.getAsString();
    }

    /**
     * 读一个整数字段，缺失 / 不是标量时返回 {@code 0}（不抛异常）。
     *
     * @param object 所在对象
     * @param key    字段名
     * @return 字段值；没有则 {@code 0}
     */
    private static int intValue(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || !element.isJsonPrimitive() ? 0 : element.getAsInt();
    }
}
