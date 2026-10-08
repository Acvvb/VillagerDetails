package com.villagerdetails.config.impl;

import com.google.gson.*;
import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.command.SwitchComponentType;
import com.villagerdetails.config.ReloadableConfig;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.storage.LevelResource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 无挤压配置：{@code 实体注册 id → 受保护}。
 *
 * <p>和 {@link BlockCollectableConfig} 一个套路：<b>只存 true 的那些</b>，不在集合里就是 false
 * （对应 {@code /ec c NoSqueeze <实体id> false} = 把他从名单里去掉）。
 *
 * <p>保存在存档目录下 {@code villagerdetails/no_squeeze.json}。
 * 以前这份名单是塞在规则状态里的（无挤压规则的状态 = 逗号分隔的实体 id 列表），
 * 现在规则状态回归普通的「是 / 否」，名单挪到这里，和另外三条逐条配置的规则一致。
 * 老存档第一次加载时会自动搬过来，见 {@link #migrateLegacyRuleState(MinecraftServer)}。
 */
public final class NoSqueezeConfig implements ReloadableConfig {

    private NoSqueezeConfig() {
    }

    public static final NoSqueezeConfig INSTANCE = new NoSqueezeConfig();

    private static final Logger LOGGER = LogManager.getLogger(NoSqueezeConfig.class);
    private static final String SAVE_PATH = "villagerdetails/no_squeeze.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static volatile MinecraftServer server;
    /**
     * 受保护（不会被挤压）的实体类型
     */
    private static final Set<Identifier> PROTECTED = ConcurrentHashMap.newKeySet();

    @Override
    public String configName() {
        return "NoSqueezeConfig";
    }

    @Override
    public void reload(MinecraftServer server) {
        NoSqueezeConfig.server = server;
        loadFromWorld(server);
        // 老存档里名单还塞在规则状态里，这里搬过来（已经迁移过就什么都不做）
        migrateLegacyRuleState(server);
    }

    // ==============================================================
    // 业务方法
    // ==============================================================

    /**
     * 这种实体是不是受保护（不会被挤压）
     */
    public static boolean contains(EntityType<?> type) {
        if (type == null) return false;
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        return PROTECTED.contains(id);
    }

    /**
     * 当前配置快照（只有 true 的那些，按 id 排序）
     */
    public static List<Identifier> snapshot() {
        return PROTECTED.stream()
                .sorted(Comparator.comparing(Identifier::toString))
                .toList();
    }

    public static void add(Identifier id) {
        PROTECTED.add(id);
        save();
    }

    public static void remove(Identifier id) {
        PROTECTED.remove(id);
        save();
    }

    public static void reset() {
        PROTECTED.clear();
        save();
    }

    /**
     * 序列化为同步字符串：{@code id,id,...}
     */
    public static String toSyncString() {
        if (PROTECTED.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Identifier id : PROTECTED) {
            if (!sb.isEmpty()) sb.append(',');
            sb.append(id);
        }
        return sb.toString();
    }

    /**
     * 客户端：用同步字符串覆盖内存（不落盘）
     */
    public static void applyFromSyncString(String data) {
        PROTECTED.clear();
        PROTECTED.addAll(parseIds(data));
    }

    /**
     * 解析 {@code id,id,...}（空 / 非法项直接跳过）
     */
    private static Set<Identifier> parseIds(String data) {
        if (data == null || data.isEmpty()) {
            return Set.of();
        }
        Set<Identifier> ids = new LinkedHashSet<>();
        for (String part : data.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            Identifier id = Identifier.tryParse(p);
            if (id != null) ids.add(id);
        }
        return ids;
    }

    // ==============================================================
    // 老数据迁移
    // ==============================================================

    /**
     * 把老版本的「名单塞在规则状态里」搬进本配置。
     *
     * <p>老模型：{@code villager_details_binding.json} 里 {@code "NoSqueeze"} 的值<b>就是</b>逗号分隔的实体 id
     * （没有名单时是 {@code none}），规则开没开看这个值是不是 {@code false}。
     * 新模型：规则状态只管 {@code true / false}，名单在本配置里。
     *
     * <p>所以这里把老值解析进名单，再把这个键改写成 {@code true}（老模型下「值不是 false」就等于开着）。
     * 认得出是规则状态的值（{@code true / false / ops / …}）直接跳过 —— 说明没配过名单或者已经迁移过，
     * 因此<b>重复执行是安全的</b>：已经删掉的实体不会在下次启动时被加回来。
     */
    private static void migrateLegacyRuleState(MinecraftServer server) {
        RuleConfig config = RuleConfig.getOrCreate(server);
        String key = RuleType.VILLAGER_NO_SQUEEZE.getRegisterName();
        String legacy = config.getBindingState(key);
        if (!isLegacyState(legacy)) {
            return;
        }

        Set<Identifier> ids = parseIds(legacy);
        if (!ids.isEmpty()) {
            PROTECTED.addAll(ids);
            save();
        }

        // 先把文件里的老值改写成 true，再让内存里的规则状态跟上。
        // 两步都要：RuleCache 已经等于 true 时 setState 会直接 return，文件就白改了。
        config.setBindingState(key, SwitchComponentType.TRUE_STR);
        RuleCache.setState(RuleType.VILLAGER_NO_SQUEEZE, SwitchComponentType.TRUE_STR);
        LOGGER.info("已把「无挤压」规则状态里的老名单迁移进配置（{} 项）", ids.size());
    }

    /**
     * 是不是老模型的值：非空，且不是 {@code true / false / ops / …} 这类规则状态
     */
    private static boolean isLegacyState(String state) {
        return state != null && !state.isBlank()
                && SwitchComponentType.getByCommandStr(state.trim()) == null;
    }

    // ==============================================================
    // 文件读写
    // ==============================================================

    private static void loadFromWorld(MinecraftServer server) {
        loadFrom(getPath(server));
    }

    private static Path getPath(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(SAVE_PATH);
    }

    private static void loadFrom(Path file) {
        PROTECTED.clear();
        if (!Files.exists(file)) {
            save();
            LOGGER.info("已生成默认配置：{}", file);
            return;
        }
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                Identifier id = Identifier.tryParse(e.getKey());
                if (id != null) PROTECTED.add(id);
            }
            LOGGER.info("已加载 {} 项无挤压保护", PROTECTED.size());
        } catch (Exception e) {
            LOGGER.error("加载无挤压配置失败：{}", file, e);
        }
    }

    private static void save() {
        MinecraftServer s = server;
        if (s == null) return;
        Path file = getPath(s);
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            PROTECTED.forEach(id -> root.addProperty(id.toString(), true));
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(root, w);
            }
        } catch (Exception e) {
            LOGGER.error("保存无挤压配置失败：{}", file, e);
        }
    }
}
