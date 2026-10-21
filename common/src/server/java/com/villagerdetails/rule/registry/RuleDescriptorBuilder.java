package com.villagerdetails.rule.registry;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.lang.ServerTranslations;
import com.villagerdetails.network.channel.SyncProtocol;
import com.villagerdetails.network.sync.payload.clientbound.RuleDataOptionsPayload;
import com.villagerdetails.network.sync.payload.clientbound.RuleDescriptorSyncPayload;
import com.villagerdetails.rule.RuleSwitches;
import com.villagerdetails.rule.descriptor.RuleDescriptor;
import com.villagerdetails.rule.type.RuleCategoryType;
import com.villagerdetails.rule.type.RuleDataKind;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

/**
 * 把服务端这边的 {@link RuleType} / {@link RuleDataRegistry} 翻译成客户端能直接渲染的
 * {@link RuleDescriptor}。这是<b>唯一</b>一处「规则语义 → 下发数据」的转换点，
 * 命令、界面、同步各自不再各抄一份。
 *
 * <p>两个对外方法：
 * <ul>
 *     <li>{@link #build(ServerPlayer, String)}：整份描述符同步（配置界面两个分类的全部内容）</li>
 *     <li>{@link #buildOptions(String)}：某条逐条配置规则的 id → 原版值清单（补全 + 预填用）</li>
 * </ul>
 */
public final class RuleDescriptorBuilder {

    /**
     * id 空间超过这个数就不下发清单了（客户端退化成手填 + 服务端校验）。
     *
     * <p>方块注册表装了模组之后上千条很常见，这份清单只在打开某条规则的详情页时按需发一次，
     * 但总得有个上限，免得某个注册表爆炸时把包撑成一个巨型 payload。
     */
    private static final int MAX_OPTION_IDS = 20_000;

    private RuleDescriptorBuilder() {
    }

    // ==============================================================
    // 整份描述符
    // ==============================================================

    /**
     * 生成这个玩家该看到的全部描述符。
     *
     * <p>文字按<b>客户端界面语言</b>解析（{@link ServerTranslations#resolve(Component, String)}）：
     * 配置界面里「服务器 / 服务端配置」这两个分类跟客户端语言走，
     * <b>不受服务端「模组语言」规则影响</b>；客户端没报语言 / 那份语言没有，才退回服务端当前语言。
     *
     * @param language 客户端界面语言（来自 hello），例如 {@code zh_cn}；空 = 用服务端当前语言
     */
    public static RuleDescriptorSyncPayload build(ServerPlayer player, String language) {
        if (RuleCache.lacksRuleCommandPermission(player)) {
            // 没权限：一条都不下发（空表 = 客户端那两个分类整个不显示）
            return new RuleDescriptorSyncPayload(SyncProtocol.VERSION, List.of(), List.of());
        }

        List<RuleDescriptor> rules = new ArrayList<>(RuleType.values().length);
        Set<String> usedCategories = new LinkedHashSet<>();
        for (RuleType type : RuleType.values()) {
            RuleDescriptor descriptor = describe(type, language);
            rules.add(descriptor);
            usedCategories.addAll(descriptor.categories());
        }

        // 一条规则都没有的分类不下发：客户端不用自己判断「这个页签该不该显示」
        List<RuleDescriptor.Category> categories = new ArrayList<>();
        for (RuleCategoryType category : RuleCategoryType.values()) {
            if (usedCategories.contains(category.getRegisterName())) {
                categories.add(new RuleDescriptor.Category(
                        category.getRegisterName(), resolve(category.getDisplayNameKey(), language)));
            }
        }

        return new RuleDescriptorSyncPayload(SyncProtocol.VERSION, List.copyOf(categories), List.copyOf(rules));
    }

    /**
     * 一条规则的完整描述符
     */
    public static RuleDescriptor describe(RuleType type, String language) {
        RuleDataRegistry.Entry entry = RuleDataRegistry.of(type);
        RuleDescriptor.ListTemplate template = entry == null ? null : template(entry);

        return new RuleDescriptor(
                type.getRegisterName(),
                resolve(type.getDisplayNameKey(), language),
                resolve(type.getDisplayInfoKey(), language),
                type.getCategory().stream().map(RuleCategoryType::getRegisterName).toList(),
                RuleCache.getState(type),
                type.getState(),
                RuleCache.isEnabled(type),
                switches(type),
                template,
                // 「服务端配置」里要不要显示由 inConfigTab 决定（见客户端），数据本身照发：
                // 规则开着的时候客户端立刻就能进详情页，不用再等一次同步
                entry == null ? List.of() : parseData(entry));
    }

    /**
     * 快捷开关按钮组：值原样下发，按钮文字用服务端这边同一套显示逻辑
     * （和 {@code /ec} 命令里那一排完全一致）
     */
    private static List<RuleDescriptor.Switch> switches(RuleType type) {
        List<String> options = type.getQuickSwitches();
        if (options == null || options.isEmpty()) {
            return List.of();
        }
        List<RuleDescriptor.Switch> switches = new ArrayList<>(options.size());
        for (String option : options) {
            switches.add(new RuleDescriptor.Switch(option, RuleSwitches.displayNameOf(option)));
        }
        return List.copyOf(switches);
    }

    /**
     * 逐条配置的模板：客户端只认这几个原语（纯名单 / 数字 / 文本 + id 来源），
     * 不写死「哪条规则是哪种列表」。
     */
    private static RuleDescriptor.ListTemplate template(RuleDataRegistry.Entry entry) {
        RuleDataKind kind = entry.kind();
        RuleDescriptor.ValueKind valueKind = kind.isPlainList()
                ? RuleDescriptor.ValueKind.NONE
                : (kind.isNumber() ? RuleDescriptor.ValueKind.NUMBER : RuleDescriptor.ValueKind.TEXT);
        return new RuleDescriptor.ListTemplate(kind.isPlainList(), valueKind, registryId(kind));
    }

    /**
     * id 来源的注册表 id（例如 {@code minecraft:block}），补全 / 校验用
     */
    private static String registryId(RuleDataKind kind) {
        Registry<?> registry = kind.registry();
        return registry == null ? "" : registry.key().identifier().toString();
    }

    /**
     * 把同步字符串拆成「id → 值」。
     *
     * <p>纯名单是 {@code id,id}（值一律 {@code true}），键值是 {@code id=值,id=值}。
     * 统一按 id 排序：服务端每次生成的字节稳定，「内容没变就不发」的去重才不会被 map 的迭代顺序搞成反复发。
     */
    private static List<RuleDescriptor.DataEntry> parseData(RuleDataRegistry.Entry entry) {
        String data = entry.store().serializer().get();
        if (data == null || data.isEmpty()) {
            return List.of();
        }
        boolean plain = entry.kind().isPlainList();
        List<RuleDescriptor.DataEntry> parsed = new ArrayList<>();
        for (String part : data.split(",")) {
            String text = part.trim();
            if (text.isEmpty()) {
                continue;
            }
            if (plain) {
                parsed.add(new RuleDescriptor.DataEntry(text, "true"));
                continue;
            }
            int eq = text.indexOf('=');
            if (eq <= 0 || eq == text.length() - 1) {
                continue;
            }
            parsed.add(new RuleDescriptor.DataEntry(
                    text.substring(0, eq).trim(), text.substring(eq + 1).trim()));
        }
        parsed.sort(Comparator.comparing(RuleDescriptor.DataEntry::id));
        return List.copyOf(parsed);
    }

    // ==============================================================
    // 逐条配置的 id 清单
    // ==============================================================

    /**
     * 某条规则的「可选 id + 原版值」清单；这条规则不支持逐条配置时返回 {@code null}。
     *
     * <p>原版值就是 {@code /ec c} 里新增一条时预填的那个值（{@link RuleDataRegistry.Entry#vanillaValue}），
     * 客户端拿它预填值输入框 —— 客户端不认识方块，也没法自己算这个数。
     */
    public static RuleDataOptionsPayload buildOptions(String registerName) {
        RuleType type = RuleType.getRuleTypeByRegisterName(registerName);
        RuleDataRegistry.Entry entry = type == null ? null : RuleDataRegistry.of(type);
        if (entry == null) {
            return null;
        }

        Registry<?> registry = entry.kind().registry();
        if (registry == null) {
            return new RuleDataOptionsPayload(registerName, true, List.of());
        }
        if (registry.keySet().size() > MAX_OPTION_IDS) {
            return new RuleDataOptionsPayload(registerName, true, List.of());
        }

        List<RuleDescriptor.DataEntry> options = new ArrayList<>(registry.keySet().size());
        for (Identifier id : registry.keySet()) {
            String text = id.toString();
            options.add(new RuleDescriptor.DataEntry(text, entry.vanillaValue(text)));
        }
        options.sort(Comparator.comparing(RuleDescriptor.DataEntry::id));
        return new RuleDataOptionsPayload(registerName, false, List.copyOf(options));
    }

    /**
     * 取翻译键在指定语言下的文本（描述符专供：按客户端界面语言翻译）
     */
    private static String resolve(String translationKey, String language) {
        return ServerTranslations.resolve(Component.translatable(translationKey), language);
    }
}
