package com.villagerdetails.config.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.command.SwitchComponentType;
import com.villagerdetails.config.ReloadableConfig;
import com.villagerdetails.config.impl.util.ConfigPaths;
import com.villagerdetails.config.impl.util.JsonIO;
import com.villagerdetails.lang.ServerTranslations;
import com.villagerdetails.rule.RuleChangeService;
import com.villagerdetails.rule.RuleStateValidator;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

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

    /**
     * <b>仅作为 {@code ConfigRegistry} 的注册门面，无法读取真实数据。</b>
     *
     * <p>它 {@code filePath == null}，{@code load() / save() / getBindingState()} 全是空操作：
     * 真正带数据的是 {@link #getOrCreate(MinecraftServer)} 按 server 缓存的那个实例。
     * 这个常量存在的唯一原因是注册表需要一个「名字 + reload 入口」，
     * 而 {@link #reload(MinecraftServer)} 会转手调 {@code RuleChangeService.reload}，
     * 由后者去拿真实实例。
     *
     * <p><b>要读 / 写规则的绑定状态，请一律走 {@link #getOrCreate(MinecraftServer)}</b>；
     * 直接 {@code RuleConfig.INSTANCE.getBindingState(...)} 拿到的永远是默认值。
     */
    public static final RuleConfig INSTANCE = new RuleConfig();

    private RuleConfig() {
        this.filePath = null;
    }

    @Override
    public String configName() {
        return "RuleConfig";
    }

    /**
     * 重载：把 {@code villager_details_binding.json} <b>重新从磁盘读一遍</b>，再回灌进规则缓存。
     *
     * <p>为什么先丢缓存实例：{@link #getOrCreate} 按 server 缓存实例，实例里的
     * {@code bindingStates} 是「启动时读到的内存副本」；不丢掉它，手改配置后执行
     * {@code /ec c reload} 只会把旧副本再应用一遍，等于没重载。丢掉前先 {@code save()}，
     * 避免把还没落盘的改动弄丢。
     */
    @Override
    public void reload(MinecraftServer server) {
        onServerStopping(server);
        // 走统一入口：改规则只认 RuleChangeService（它会 getOrCreate 一份新的 = 重新读盘）
        RuleChangeService.reload(server);
    }

    private static final Logger log = LogManager.getLogger(RuleConfig.class);

    /**
     * 原版格式化码（{@code §c} 等）：翻译出来的聊天文案带它，写进日志前去掉
     */
    private static final Pattern COLOR_CODE = Pattern.compile("§.");

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
        String oldState = bindingStates.get(key);
        boolean changed = false;
        if (type != null && type.getState().equals(state)) {
            // 如果新状态等于默认值，且 map 里确实存在这个 key，则移除
            if (bindingStates.containsKey(key)) {
                bindingStates.remove(key);
                changed = true;
            }
        } else {
            if (!state.equals(oldState)) {
                bindingStates.put(key, state);
                changed = true;
            }
        }
        if (changed) {
            markDirty();
            save();
        }
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
     *
     * <p><b>这里是「配置回灌加固」的唯一位置</b>：手改 {@code villager_details_binding.json} 写进来的
     * 非法值（{@code 1000} / {@code -1} / {@code abc} / {@code banana} …）必须在进内存之前就被拦下，
     * 不能指望运行时的 clamp 兜底。两条分支各自处理，<b>处理方式一致</b>：
     * <ul>
     *     <li>形态合法（自定义列表 / 命中预定义列表）→ {@link #sanitizeState}：带 validator 的规则
     *         再查一遍取值范围，非法就用默认值替换；</li>
     *     <li>形态就不合法（预定义列表里没有这一项）→ {@link #discardInvalidState}：同样用默认值替换。</li>
     * </ul>
     * 两种情况都会把<b>默认值强制写回配置文件</b>（覆盖那条非法记录），所以下次启动不会再报警。
     *
     * <p>两条都不碰 {@code /ec} 命令与网络包那条路径 —— 它们有自己的前置校验
     * （{@code RuleChangeService.change}），不会走到这里，所以不会重复拦截或重复报错。
     */
    public void syncToSwitch() {
        Map<RuleType, String> syncMap = new HashMap<>();
        // 被强制换成默认值的项（注册名 → 默认状态）：回灌之后还要再写一次，见下面 batch 里的注释
        Map<String, String> forcedDefaults = new LinkedHashMap<>();

        for (RuleType type : RuleType.values()) {
            String state = getBindingState(type.getRegisterName());
            if (state == null) continue;
            // 自定义列表（如实体 id、方块 id、方块 id=抗性）允许任意非预定义值，原样同步；
            // 预定义列表则必须命中 quickSwitches 才同步。
            boolean valid = SwitchComponentType.isCustomList(type.getQuickSwitches())
                    || type.getQuickSwitches().contains(state);
            if (valid) {
                syncMap.put(type, sanitizeState(type, state, forcedDefaults));
            } else {
                syncMap.put(type, discardInvalidState(type, state, forcedDefaults));
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
        batch(() -> {
            RuleCache.syncRules(syncMap);
            // ★ 必须在回灌之后再把默认值写一次：RuleCache.setState 成功时会 persist →
            // setBindingState，而它有一条「值等于默认值就把 key 删掉」的剪枝逻辑，
            // 会把上面强制写入的默认值又抹掉（内存里原本不是默认值时就会走到这条）。
            // 这里绕开它直接写底层 map，保证文件里留下的是字面量默认值
            // （"HappyGhastSpeed": "0.05" / "GlobalSpeedBypass": "false"），而不是该 key 消失。
            reapplyForcedDefaults(forcedDefaults);
        });
    }

    // ------------------------------------------------------------------
    //  回灌校验（配置被手改坏了时的兜底入口，只作用于「磁盘 → 内存」）
    // ------------------------------------------------------------------

    /**
     * 形态合法的状态值再查一遍规则自己的 validator；非法就强制换成默认状态并写回配置。
     *
     * <p>完全复用 {@link RuleStateValidator}：这里不写任何新的判断逻辑，只决定「替换」和「记日志」。
     *
     * <p><b>为什么要自己写 {@code bindingStates}，不能等 {@code RuleCache.syncRules} 回写</b>：
     * {@code RuleCache.setState} 在「值没变」时会提前 return（启动时内存里本来就是默认值），
     * {@code persist} 根本不会执行，文件里的非法值会一直留着、每次启动都再报一次。
     *
     * @param type           规则
     * @param state          从配置里读到的值
     * @param forcedDefaults 收集「被强制换成默认值」的项，交给 {@link #syncToSwitch} 在回灌后统一重写
     * @return 真正应该同步进 {@code RuleCache} 的值（合法值原样返回，非法值返回默认状态）
     */
    private String sanitizeState(RuleType type, String state, Map<String, String> forcedDefaults) {
        RuleStateValidator validator = type.getStateValidator();
        if (validator == null) {
            // 绝大多数规则：状态形态由 quickSwitches 兜着，不需要额外校验
            return state;
        }
        RuleStateValidator.ValidationError error = validator.validate(state);
        if (error == null) {
            return state;
        }
        String fallback = type.getState();
        log.warn("[RuleConfig] 规则 {} 的配置值 '{}' 非法（{}），已强制重置为默认值 '{}' 并写回配置",
                type.getRegisterName(), state, describeReason(error), fallback);
        forceWriteDefault(type, fallback, forcedDefaults);
        return fallback;
    }

    /**
     * 预定义列表规则的非法值（手改文件写了个不在列表里的值）。
     *
     * <p>历史行为是<b>静默丢弃</b>：不同步进内存，也不动配置，于是文件里那条错误 key 永久留着、
     * 每次启动都白读一遍，排查问题时没有任何线索。现在和 validator 那条分支<b>完全一致</b>：
     * 打 WARN、内存回落到默认状态、默认值强制写回配置。
     *
     * @param type           规则
     * @param state          从配置里读到的值
     * @param forcedDefaults 收集「被强制换成默认值」的项，交给 {@link #syncToSwitch} 在回灌后统一重写
     * @return 真正应该同步进 {@code RuleCache} 的值（永远是默认状态）
     */
    private String discardInvalidState(RuleType type, String state, Map<String, String> forcedDefaults) {
        String fallback = type.getState();
        log.warn("[RuleConfig] 规则 {} 的配置值 '{}' 不在可选列表 {} 内，已强制重置为默认值 '{}' 并写回配置",
                type.getRegisterName(), state, type.getQuickSwitches(), fallback);
        forceWriteDefault(type, fallback, forcedDefaults);
        return fallback;
    }

    /**
     * 把默认值<b>强制写进</b>内存副本里的配置项（覆盖那条非法记录），并标记待落盘。
     *
     * <p>注意这里<b>故意绕开</b> {@link #setBindingState} 的「默认值不落盘」剪枝：修复过的项必须
     * 在文件里留下字面量默认值（{@code "HappyGhastSpeed": "0.05"} / {@code "GlobalSpeedBypass": "false"}），
     * 而不是把 key 删掉 —— 这样下次开服读到的是合法值，不会再刷一遍警告。
     *
     * @param forcedDefaults 记一份，回灌之后还要再写一次（{@code setBindingState} 会把这几个 key 删掉）
     */
    private void forceWriteDefault(RuleType type, String fallback, Map<String, String> forcedDefaults) {
        String key = type.getRegisterName();
        forcedDefaults.put(key, fallback);
        bindingStates.put(key, fallback);
        markDirty();
    }

    /**
     * 回灌之后再落一次默认值（必须在 {@code RuleCache.syncRules} 之后调用）。
     *
     * <p>原因：{@code RuleCache.setState} 成功时会 {@code persist → setBindingState}，而它带着
     * 「值等于默认值就把 key 删掉」的剪枝 —— 内存里原本是别的值（比如上次合法加载的 {@code 0.3}）
     * 时，刚被 {@link #forceWriteDefault} 写进去的默认值会被它抹掉。这里直接写底层 map，
     * 由 {@link #batch} 末尾那次 {@code save()} 一次性落盘。
     */
    private void reapplyForcedDefaults(Map<String, String> forcedDefaults) {
        if (forcedDefaults.isEmpty()) {
            return;
        }
        bindingStates.putAll(forcedDefaults);
        markDirty();
    }

    /**
     * 把校验器的拒绝原因翻成人话，给 WARN 日志用。
     *
     * <p>启动早期 {@code ServerTranslations} 的语言表还没加载（{@code RuleConfig} 的回灌发生在
     * {@code UnifiedConfigFile} 的子配置之前），此时 {@code resolve} 会回落到 key 本身<b>并丢掉参数</b>，
     * 所以这里检测到没翻译出来就退回「key + 参数」，别让日志丢信息。
     *
     * <p>翻译出来的文案带 {@code §c} 这类颜色码（那是给聊天栏用的），日志里不需要，去掉。
     */
    private static String describeReason(RuleStateValidator.ValidationError error) {
        String text = ServerTranslations.resolve(Component.translatable(error.messageKey(), error.args()));
        if (text.equals(error.messageKey())) {
            return error.messageKey() + " " + Arrays.toString(error.args());
        }
        return COLOR_CODE.matcher(text).replaceAll("");
    }

}