package com.villagerdetails.config.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.command.SwitchComponentType;
import com.villagerdetails.config.impl.base.AbstractWorldConfig;
import com.villagerdetails.lang.ServerTranslations;
import com.villagerdetails.rule.RuleChangeService;
import com.villagerdetails.rule.RuleStateValidator;
import com.villagerdetails.rule.type.RuleType;
import com.villagerdetails.util.json.JsonIO;
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
import java.util.regex.Pattern;

/**
 * 每个存档一份的规则绑定配置（存档根目录 {@code villager_details_binding.json}）。
 *
 * <p>生命周期与 IO 全部来自 {@link AbstractWorldConfig}（和 {@code RuleConfig} 同一套），
 * 本类只回答「写哪个文件、怎么把 JSON 读成绑定表、清空成什么样」，外加一个它特有的动作：
 * <b>每次回灌完都要把状态应用进 {@link RuleCache}</b>（见 {@link #onReload} / {@link #onDiskLoad}）。
 *
 * <p>落盘内容就是 {@link #bindingStates} 本身：{@code 规则注册名 → 状态字符串}，
 * Gson 直接序列化成 {@code {"规则名": "状态"}}。
 *
 * <p>与 SavedData 相比：人类可读、可手动编辑、不依赖 NBT / Codec / DataFixTypes。
 */
public final class RuleSwitchConfig extends AbstractWorldConfig {

    // 全局唯一实例
    public static final RuleSwitchConfig INSTANCE = new RuleSwitchConfig();

    private static final Logger log = LogManager.getLogger(RuleSwitchConfig.class);

    /**
     * 原版格式化码（{@code §c} 等）：翻译出来的聊天文案带它，写进日志前去掉。
     */
    private static final Pattern COLOR_CODE = Pattern.compile("§.");

    /**
     * 文件名（放在存档根目录，和 {@code villagerdetails/} 平级）。
     */
    private static final String SAVE_PATH = "villager_details_binding.json";

    /**
     * 规则注册名 → 状态字符串，同时也是<b>落盘内容</b>。
     *
     * <p>只在主线程读写（和 {@code config.json} 一样，{@code flush()} 就是串行化点）。
     * 约定：值等于规则默认状态的项<b>不保存</b>，所以「没改过的规则」不会在文件里占一行。
     */
    private Map<String, String> bindingStates = new LinkedHashMap<>();

    /**
     * 私有构造：全局只允许 {@link #INSTANCE} 存在一个实例。
     *
     * <p>构造时父类会把它登记进静态表，{@link AbstractWorldConfig#tickAll()} 因此自动覆盖到它。
     */
    private RuleSwitchConfig() {
    }

    // ==============================================================
    // 父类要求的三件事 + 数据
    // ==============================================================

    /**
     * {@inheritDoc}
     *
     * <p>本配置固定写在存档根目录的 {@code villager_details_binding.json}。
     */
    @Override
    protected String savePath() {
        return SAVE_PATH;
    }

    /**
     * {@inheritDoc}
     *
     * <p>直接交 {@link #bindingStates} 本身：Gson 会把它序列化成 {@code {"规则名": "状态"}}。
     */
    @Override
    protected Object getDataToSave() {
        return bindingStates;
    }

    /**
     * {@inheritDoc}
     *
     * <p>注册名 / {@code /ec c reload <name>} 里用的名字。
     */
    @Override
    public String configName() {
        return "RuleSwitchConfig";
    }

    /**
     * 磁盘 → 内存：读成 {@code 规则注册名 → 状态字符串}。
     *
     * <p>合法性校验<b>不在这里</b>：这里只做「能读成字符串的都收下」，
     * 真正拦截并纠正取值的是 {@link #syncToSwitch}。读盘异常只记 ERROR，
     * 内存保持空表（等于所有规则都用默认值），不会拦住开服。
     */
    @Override
    protected void load() {
        bindingStates = new LinkedHashMap<>();
        Path file = currentFile();
        if (file == null) return;

        try {
            JsonObject obj = JsonIO.readObject(file);
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                if (e.getValue().isJsonPrimitive()) {
                    bindingStates.put(e.getKey(), e.getValue().getAsString());
                }
            }
            if (!bindingStates.isEmpty()) {
                log.info("已从 {} 加载绑定配置（{} 项）", file, bindingStates.size());
            }
        } catch (Exception e) {
            log.error("读取绑定配置失败：{}", file, e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>换成空表：停服后这些状态没有意义，留着只会被下一次落盘写进别的存档的配置文件。
     */
    @Override
    protected void clearData() {
        bindingStates = new LinkedHashMap<>();
    }

    // ==============================================================
    // 钩子：回灌完把状态应用进 RuleCache
    // ==============================================================

    /**
     * {@inheritDoc}
     *
     * <p>开服回灌：把刚读到的绑定表灌进 {@link RuleCache}（{@link RuleChangeService#reload} 内部
     * 会调 {@link #syncToSwitch}），非法值在这一步被拦下并改写成默认值。
     */
    @Override
    protected void onReload(MinecraftServer server) {
        RuleChangeService.reload(server);
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code /ec c reload} 的收尾：同样回灌进 {@link RuleCache}，并打一条「重读了多少项」的日志。
     */
    @Override
    protected void onDiskLoad(MinecraftServer server) {
        RuleChangeService.reload(server);
        log.info("已从磁盘重读绑定配置：{} 项", bindingStates.size());
    }

    // ==============================================================
    // 兼容入口
    // ==============================================================

    /**
     * 兼容入口：等价于「挂上 server + 需要时读盘」，再返回唯一实例。
     *
     * <p>老代码按 server 缓存实例（{@code ConcurrentHashMap<MinecraftServer, RuleSwitchConfig>}），
     * 现在只有一份实例，语义由父类的 {@code ensureLoaded} 承担；{@link RuleCache} 与
     * {@code NoSqueezeConfig} 仍通过这个入口取用。
     *
     * @param server 目标服务器；{@code null} 时只会得到一张空表
     */
    public static RuleSwitchConfig getOrCreate(MinecraftServer server) {
        INSTANCE.ensureLoaded(server);
        return INSTANCE;
    }

    // ==============================================================
    // 业务方法（与原来签名完全一致）
    // ==============================================================

    /**
     * 写一条规则的绑定状态（<b>只改内存 + 标脏</b>，写盘交给 tick 末尾的统一提交）。
     *
     * <p>两条剪枝规则，决定了文件里能看到什么：
     * <ul>
     *     <li>新值<b>等于规则默认状态</b> → 把这条 key 从表里删掉（默认值不落盘）；</li>
     *     <li>新值和旧值相同 → 什么都不做（不标脏）。</li>
     * </ul>
     *
     * <p><b>只给「改规则」那条落盘路径调</b>（{@link RuleCache#setState} 内部）；
     * 业务代码要改规则状态请用 {@link RuleChangeService#change}，不要再直接写这里。
     *
     * @param key   规则注册名
     * @param state 新的状态字符串
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
        }
    }

    /**
     * 读一条规则在配置文件里的状态；没有配置项时回落到该规则的默认状态。
     *
     * @param key 规则注册名
     * @return 配置文件里的值；没有则返回 {@link RuleType#getState()}，
     *         连规则都找不到时返回 {@link SwitchComponentType#FALSE_STR}
     */
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
     * 另外会顺手删掉文件里已经不存在的规则名（{@link RuleType} 里查不到的那些 key）。
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
            reapplyForcedDefaults(forcedDefaults);
        });
    }

    // ------------------------------------------------------------------
    // 回灌校验（配置被手改坏了时的兜底入口，只作用于「磁盘 → 内存」）
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
        log.warn("[RuleSwitchConfig] 规则 {} 的配置值 '{}' 非法（{}），已强制重置为默认值 '{}' 并写回配置",
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
        log.warn("[RuleSwitchConfig] 规则 {} 的配置值 '{}' 不在可选列表 {} 内，已强制重置为默认值 '{}' 并写回配置",
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
     * @param type           规则
     * @param fallback       要写回的默认状态
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
     * 由 {@link #batch} 末尾那次 flush 一次性提交。
     *
     * @param forcedDefaults 被强制换成默认值的项（可能为空，空则什么都不做）
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
     * <p>启动早期 {@code ServerTranslations} 的语言表还没加载（{@code RuleSwitchConfig} 的回灌发生在
     * {@code RuleConfig} 的子配置之前），此时 {@code resolve} 会回落到 key 本身<b>并丢掉参数</b>，
     * 所以这里检测到没翻译出来就退回「key + 参数」，别让日志丢信息。
     *
     * <p>翻译出来的文案带 {@code §c} 这类颜色码（那是给聊天栏用的），日志里不需要，去掉。
     *
     * @param error 校验器给出的拒绝原因
     * @return 可直接拼进日志的人类可读文本
     */
    private static String describeReason(RuleStateValidator.ValidationError error) {
        String text = ServerTranslations.resolve(Component.translatable(error.messageKey(), error.args()));
        if (text.equals(error.messageKey())) {
            return error.messageKey() + " " + Arrays.toString(error.args());
        }
        return COLOR_CODE.matcher(text).replaceAll("");
    }
}
