package com.villagerdetails.config.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.command.SwitchComponentType;
import com.villagerdetails.config.ReloadableConfig;
import com.villagerdetails.config.impl.util.ConfigPaths;
import com.villagerdetails.config.impl.util.JsonIO;
import com.villagerdetails.rule.RuleChangeService;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每个存档一份的规则绑定配置，以 JSON 文件形式保存在存档根目录下：
 * <world>/villager_details_binding.json
 * <p>
 * 与 SavedData 相比：
 * · 人类可读、可手动编辑
 * · 不依赖 NBT / Codec / DataFixTypes
 * · 需要自己管理加载、保存、服务器关闭时的刷盘
 */
public class RuleConfig implements ReloadableConfig {

    public static final RuleConfig INSTANCE = new RuleConfig();

    private RuleConfig() {
        this.filePath = null;
    }

    @Override
    public String configName() {
        return "RuleConfig";
    }

    @Override
    public void reload(MinecraftServer server) {
        // 走统一入口：改规则只认 RuleChangeService
        RuleChangeService.reload(server);
    }

    private static final Logger log = LogManager.getLogger(RuleConfig.class);

    /**
     * 文件名（放在存档根目录）
     */
    private static final String FILE_NAME = "villager_details_binding.json";

    /**
     * 按服务器实例缓存，避免每次调用都读盘
     */
    private static final Map<MinecraftServer, RuleConfig> INSTANCES = new ConcurrentHashMap<>();

    private final Path filePath;
    private final Map<String, String> bindingStates = new HashMap<>();
    private boolean dirty = false;

    /**
     * 批量改动期间为 true：只记脏标记，不落盘
     */
    private boolean batching = false;

    private RuleConfig(Path filePath) {
        this.filePath = filePath;
    }

    // ------------------------------------------------------------------
    //  生命周期
    // ------------------------------------------------------------------

    public static RuleConfig getOrCreate(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, s -> {
            Path path = ConfigPaths.world(s, FILE_NAME);
            RuleConfig config = new RuleConfig(path);
            config.load();
            return config;
        });
    }

    /**
     * 在服务器停止事件里调用，把未落盘的改动写回文件
     */
    public static void onServerStopping(MinecraftServer server) {
        RuleConfig config = INSTANCES.remove(server);
        if (config != null) {
            config.save();
        }
    }

    // ------------------------------------------------------------------
    //  读写文件
    // ------------------------------------------------------------------

    private void load() {
        if (filePath == null) return;
        try {
            JsonObject obj = JsonIO.readObject(filePath);
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                if (e.getValue().isJsonPrimitive()) {
                    bindingStates.put(e.getKey(), e.getValue().getAsString());
                }
            }
            if (!bindingStates.isEmpty()) {
                log.info("已从 {} 加载绑定配置（{} 项）", filePath, bindingStates.size());
            }
        } catch (Exception e) {
            log.error("读取绑定配置失败：{}", filePath, e);
        }
    }

    /**
     * 把一组改动合成一次落盘。
     *
     * <p>为什么需要它：{@link #setBindingState} 每次都整份重写 JSON，而配置回灌时每条规则都会走一遍 ——
     * 30 条规则就是 30 次整份写盘。这里期间只留脏标记（{@link #markDirty} 已经置上），结束后写一次。
     *
     * <p>嵌套调用由最外层收尾；{@code action} 抛异常也会在 {@code finally} 里把已经应用的改动落盘。
     */
    public void batch(Runnable action) {
        if (batching) {
            action.run();
            return;
        }
        batching = true;
        try {
            action.run();
        } finally {
            batching = false;
            if (dirty && filePath != null) {
                log.info("批量写入绑定配置：一次落盘（{} 项）", bindingStates.size());
            }
            save();
        }
    }

    public void save() {
        // 批量过程中不写：脏标记已经置上，batch 结束时统一落盘
        if (batching) return;
        if (!dirty || filePath == null) return;

        JsonObject obj = new JsonObject();
        bindingStates.forEach(obj::addProperty);
        JsonIO.safeWrite(filePath, obj);
        dirty = false;
    }

    private void markDirty() {
        this.dirty = true;
    }

    // ------------------------------------------------------------------
    //  业务方法（与原来签名完全一致）
    // ------------------------------------------------------------------

    /**
     * 写一条规则的绑定状态。
     *
     * <p><b>只给「改规则」那条落盘路径调</b>（{@link RuleCache#setState} 内部）；
     * 业务代码要改规则状态请用 {@link RuleChangeService#change}，不要再直接写这里。
     */
    public void setBindingState(String key, String state) {
        RuleType type = RuleType.getRuleTypeByRegisterName(key);
        if (type != null && type.getState().equals(state)) {
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
        return type != null ? type.getState() : SwitchComponentType.FALSE_STR;
    }

    /**
     * 磁盘 → 内存：把配置文件里的状态批量灌进 {@link RuleCache}。
     *
     * <p>只给 {@link RuleChangeService#reload} 调（服务器启动、{@code reload}、迁移）。
     */
    public void syncToSwitch() {
        Map<RuleType, String> syncMap = new HashMap<>();
        for (RuleType type : RuleType.values()) {
            String state = getBindingState(type.getRegisterName());
            if (state == null) continue;
            // 自定义列表（如实体 id、方块 id、方块 id=抗性）允许任意非预定义值，原样同步；
            // 预定义列表则必须命中 quickSwitches 才同步。
            boolean valid = SwitchComponentType.isCustomList(type.getQuickSwitches())
                    || type.getQuickSwitches().contains(state);
            if (valid) {
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

        // 一次落盘：下面每条规则的 setState 都会走到 setBindingState，逐条写就是 N 次整份重写
        batch(() -> RuleCache.syncRules(syncMap));
    }

}