package com.villagerdetails.config;

import com.google.gson.*;
import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.command.SwitchComponentType;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每个存档一份的规则绑定配置，以 JSON 文件形式保存在存档根目录下：
 *   <world>/villager_details_binding.json
 * <p>
 * 与 SavedData 相比：
 *   · 人类可读、可手动编辑
 *   · 不依赖 NBT / Codec / DataFixTypes
 *   · 需要自己管理加载、保存、服务器关闭时的刷盘
 */
public class WorldBindingConfig {

    private static final Logger log = LogManager.getLogger(WorldBindingConfig.class);

    /** 文件名（放在存档根目录） */
    private static final String FILE_NAME = "villager_details_binding.json";

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    /** 按服务器实例缓存，避免每次调用都读盘 */
    private static final Map<MinecraftServer, WorldBindingConfig> INSTANCES = new ConcurrentHashMap<>();

    private final Path filePath;
    private final Map<String, String> bindingStates = new HashMap<>();
    private boolean dirty = false;

    private WorldBindingConfig(Path filePath) {
        this.filePath = filePath;
    }

    // ------------------------------------------------------------------
    //  生命周期
    // ------------------------------------------------------------------

    public static WorldBindingConfig getOrCreate(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, s -> {
            Path worldRoot = s.getWorldPath(LevelResource.ROOT);
            Path path = worldRoot.resolve(FILE_NAME);
            WorldBindingConfig config = new WorldBindingConfig(path);
            config.load();
            return config;
        });
    }

    /** 在服务器停止事件里调用，把未落盘的改动写回文件 */
    public static void onServerStopping(MinecraftServer server) {
        WorldBindingConfig config = INSTANCES.remove(server);
        if (config != null) {
            config.save();
        }
    }

    // ------------------------------------------------------------------
    //  读写文件
    // ------------------------------------------------------------------

    private void load() {
        if (!Files.exists(filePath)) {
            return;
        }
        try {
            String json = Files.readString(filePath);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    bindingStates.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
            log.info("[VillagerDetails] 已从 {} 加载绑定配置（{} 项）",
                    filePath, bindingStates.size());
        } catch (IOException | JsonParseException e) {
            log.error("[VillagerDetails] 读取绑定配置失败：{}", filePath, e);
        }
    }

    public void save() {
        if (!dirty) {
            return;
        }
        try {
            Files.createDirectories(filePath.getParent());
            JsonObject obj = new JsonObject();
            for (Map.Entry<String, String> entry : bindingStates.entrySet()) {
                obj.addProperty(entry.getKey(), entry.getValue());
            }
            Files.writeString(filePath, GSON.toJson(obj));
            dirty = false;
        } catch (IOException e) {
            log.error("[VillagerDetails] 写入绑定配置失败：{}", filePath, e);
        }
    }

    private void markDirty() {
        this.dirty = true;
    }

    // ------------------------------------------------------------------
    //  业务方法（与原来签名完全一致）
    // ------------------------------------------------------------------

    public void setBindingState(String key, String state) {
        RuleType type = RuleType.getRuleTypeByRegisterName(key);
        if (type != null && type.getState().getCommandStr().equals(state)) {
            bindingStates.remove(key);
        } else {
            bindingStates.put(key, state);
        }
        markDirty();
        save();
    }

    public String getBindingState(String key) {
        RuleType type = RuleType.getRuleTypeByRegisterName(key);
        if (bindingStates.containsKey(key)) {
            return bindingStates.get(key);
        }
        return type != null ? type.getState().getCommandStr() : SwitchComponentType.FALSE.getCommandStr();
    }

    public void resetAll() {
        bindingStates.clear();
        markDirty();
        save();
    }

    public void syncToSwitch() {
        Map<RuleType, SwitchComponentType> syncMap = new HashMap<>();
        for (RuleType type : RuleType.values()) {
            SwitchComponentType state = SwitchComponentType.getByCommandStr(getBindingState(type.getRegisterName()));
            if (state != null) {
                syncMap.put(type, state);
            }
        }

        Iterator<String> it = bindingStates.keySet().iterator();
        boolean removed = false;
        while (it.hasNext()) {
            String key = it.next();
            if (RuleType.getRuleTypeByRegisterName(key) == null) {
                it.remove();
                removed = true;
            }
        }
        if (removed) {
            markDirty();
        }

        RuleCache.syncRules(syncMap);
    }

}