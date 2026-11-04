package com.villagerdetails.config.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.villagerdetails.config.impl.util.ConfigPaths;
import com.villagerdetails.pojo.dto.TradeItemInfo;
import com.villagerdetails.pojo.dto.VillagerTradeInfo;
import com.villagerdetails.util.io.AbstractAsyncConfig;
import com.villagerdetails.util.json.JsonIO;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 村民交易索引的落盘存储。
 *
 * <p>继承自 {@link AbstractAsyncConfig}，所有的脏标记、异步提交、合并落盘、
 * 甚至关服时的排干，全部由底层框架接管。本类只关心数据怎么读、怎么转 JSON。
 */
public final class VillagerTradeStorage extends AbstractAsyncConfig {

    public static final VillagerTradeStorage INSTANCE = new VillagerTradeStorage();

    private static final Logger log = LogManager.getLogger(VillagerTradeStorage.class);
    private static final String SAVE_RELATIVE_PATH = "villagerdetails/villager_trade_cache.json";
    private static final int FORMAT_VERSION = 2;

    private final ConcurrentHashMap<UUID, VillagerTradeInfo> cache = new ConcurrentHashMap<>();
    private volatile Path file;

    private VillagerTradeStorage() {
    }

    // ============================================================
    // 框架接口实现（只回答写哪里、写什么）
    // ============================================================

    @Override
    protected Path getFilePath() {
        return file;
    }

    @Override
    protected Object getDataToSave() {
        // 将内存数据转为最终要落盘的 JsonObject，框架会在主线程瞬间序列化它
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT_VERSION);
        root.addProperty("savedAt", System.currentTimeMillis());

        JsonArray villagers = new JsonArray();
        cache.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> villagers.add(villagerToJson(entry.getValue())));
        root.add("villagers", villagers);
        return root;
    }

    // ============================================================
    // 业务方法
    // ============================================================

    public ConcurrentHashMap<UUID, VillagerTradeInfo> cache() {
        return cache;
    }

    /**
     * 更新数据并标脏。
     */
    public void put(UUID uuid, VillagerTradeInfo info) {
        cache.put(uuid, info);
        markDirty(); // 调父类的方法，底层会自动合并写入
    }

    /**
     * 清空内存。
     */
    public void clearMemory() {
        cache.clear();
        clearDirty();
    }

    // ============================================================
    // 读盘逻辑（只读一次，不需要并发控制）
    // ============================================================

    public void load(MinecraftServer server) {
        if (server == null) return;
        Path target = ConfigPaths.world(server, SAVE_RELATIVE_PATH);
        this.file = target;

        if (!Files.exists(target)) {
            log.info("村民交易索引暂无落盘文件：{}", target);
            return;
        }

        ParsedIndex parsed;
        try {
            parsed = parse(JsonIO.readObject(target));
        } catch (Exception e) {
            log.warn("读取村民交易索引失败，本次按空缓存继续（文件：{}）", target, e);
            return;
        }

        cache.clear();
        cache.putAll(parsed.villagers());

        if (parsed.skipped() > 0) {
            markDirty(); // 如果有坏数据被剔除，标脏，下次 flush() 时重写文件
            log.info("村民交易索引里有 {} 条坏记录已被剔除，将在下一次落盘时重写文件", parsed.skipped());
        }
        log.info("已从 {} 加载村民交易索引：{} 只村民", target, cache.size());
    }

    // ============================================================
    // 解析 & 序列化（纯 IO 转换逻辑）
    // ============================================================

    private record ParsedIndex(Map<UUID, VillagerTradeInfo> villagers, int skipped) {
    }

    private static ParsedIndex parse(JsonObject root) {
        Map<UUID, VillagerTradeInfo> result = new HashMap<>();
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

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || !element.isJsonPrimitive() ? "" : element.getAsString();
    }

    private static int intValue(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || !element.isJsonPrimitive() ? 0 : element.getAsInt();
    }

    private static JsonObject villagerToJson(VillagerTradeInfo info) {
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

    private static JsonObject offerToJson(TradeItemInfo offer) {
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
}