package com.villagerdetails.handler.entity.villager.trade;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.villagerdetails.config.impl.IdTranslationConfig;
import com.villagerdetails.lang.ServerTranslations;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 主线程侧的名字解析器：把 {@link ItemStack} / {@link Component} 变成<b>纯字符串</b>，
 * 供异步管线使用。
 *
 * <p>名字的来源优先级：
 * <ol>
 *     <li>{@code trade_index.json} 里给这个 id 配的中文叫法（例如 {@code mending → 经验修补}）；</li>
 *     <li>模组自己的语言表（{@link ServerTranslations}）；</li>
 *     <li>原版 {@code en_us} 语言表（服务端 jar 里自带，按需从 classpath 读一次）；</li>
 *     <li>实在没有就按注册 id 拼一个可读名字（{@code enchanted_book → Enchanted Book}）。</li>
 * </ol>
 *
 * <p><b>线程约定</b>：这里的所有方法都只允许在服务端主线程调用 —— 入参是原版对象，
 * 只有主线程能安全读。返回值是纯字符串，之后随便丢给哪个线程都行。
 */
public final class TradeItemNameResolver {

    private TradeItemNameResolver() {
    }

    private static final Logger log = LogManager.getLogger(TradeItemNameResolver.class);

    /**
     * 原版英文语言表在服务端 jar 里的固定路径（专用服与开发环境都有）。
     */
    private static final String VANILLA_EN_US = "/assets/minecraft/lang/en_us.json";

    private static final Object LANG_LOCK = new Object();

    /**
     * 原版 en_us 表；加载失败时留一份空表，避免每次解析都去读一次资源。
     */
    private static volatile Map<String, String> vanillaEnglish;

    /**
     * 启动期预热（在事件注册时调一次）：把原版 en_us 表读进内存。
     *
     * <p>不预热也不会错，只是第一次区块卸载会多几毫秒读盘时间。
     */
    public static void preload() {
        vanillaEnglish();
    }

    // ============================================================
    // 对外：把一个结果物品拆成搜索 / 展示需要的字符串
    // ============================================================

    /**
     * 结果物品的注册 id，例如 {@code minecraft:enchanted_book}。
     */
    public static String itemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? "" : id.toString();
    }

    /**
     * 物品展示名，例如 {@code 附魔书} / {@code Enchanted Book} / {@code 白色陶瓦}。
     */
    public static String itemName(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        String itemId = itemId(stack);

        // trade_index.json 里配了中文叫法就优先用它（附魔 / 陶瓦这类常用交易物品都有）
        String alias = IdTranslationConfig.displayNameOf(itemId);
        if (alias != null && !alias.isBlank()) {
            return clean(alias);
        }

        String resolved = resolve(stack.getHoverName());
        if (resolved.isBlank() || looksLikeTranslationKey(resolved)) {
            resolved = prettify(itemId);
        }
        return clean(resolved.isBlank() ? prettify(itemId) : resolved);
    }

    /**
     * 物品上的全部附魔（普通附魔 + 附魔书的 {@code STORED_ENCHANTMENTS}）。
     *
     * <p>每项同时带 id 和展示名：展示名用于聊天栏，id 用于搜索（{@code mending} 这种英文 id
     * 也要能命中中文展示名「经验修补」）。优先级：{@code trade_index.json} 的中文叫法 →
     * 模组 / 原版语言表 → id。等级大于 1 时展示名追加数字（{@code 耐久 3}）。
     */
    public static List<EnchantmentText> enchantmentTexts(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return List.of();
        }

        // LinkedHashMap 去重 + 保持「先普通附魔、后附魔书存储附魔」的稳定顺序
        Map<String, EnchantmentText> texts = new LinkedHashMap<>();
        collectEnchantments(stack.getEnchantments(), texts);

        ItemEnchantments stored = stack.get(DataComponents.STORED_ENCHANTMENTS);
        if (stored != null) {
            collectEnchantments(stored, texts);
        }
        // Object2IntOpenHashMap 的迭代顺序只是「当前哈希顺序」；排一下保证展示 / 搜索文本稳定
        List<EnchantmentText> sorted = new ArrayList<>(texts.values());
        sorted.sort(Comparator.comparing(EnchantmentText::id).thenComparing(EnchantmentText::displayName));
        return List.copyOf(sorted);
    }

    /**
     * 一个附魔的展示信息。
     *
     * @param id          附魔注册 id，例如 {@code minecraft:mending}；解析不出来时为空串
     * @param displayName 展示名，例如 {@code 经验修补} / {@code Mending 3}
     */
    public record EnchantmentText(String id, String displayName) {
    }

    private static void collectEnchantments(ItemEnchantments enchantments, Map<String, EnchantmentText> out) {
        if (enchantments == null || enchantments.isEmpty()) {
            return;
        }
        for (Holder<Enchantment> holder : enchantments.keySet()) {
            int level = enchantments.getLevel(holder);
            String id = holder.unwrapKey()
                    .map(key -> key.identifier().toString())
                    .orElse("");
            String display = enchantmentName(holder, level, id);
            if (display.isBlank()) {
                continue;
            }
            // 同一附魔在两张表里都出现时只保留第一次（正常不会发生）
            out.putIfAbsent(id.isEmpty() ? display : id, new EnchantmentText(id, display));
        }
    }

    private static String enchantmentName(Holder<Enchantment> holder, int level, String id) {
        String alias = IdTranslationConfig.displayNameOf(id);
        String display = alias;
        if (display == null || display.isBlank()) {
            display = resolve(holder.value().description());
            if (display.isBlank() || looksLikeTranslationKey(display)) {
                display = prettify(id);
            }
        }
        display = clean(display);
        if (display.isBlank()) {
            return "";
        }
        return level > 1 ? display + " " + level : display;
    }

    // ============================================================
    // Component → String
    // ============================================================

    /**
     * 把单个 {@link Component} 解析成纯文本（支持 literal / translatable / keybind 与 siblings）。
     *
     * <p>为什么不直接 {@code component.getString()}：服务端没有加载原版的 {@code Language}，
     * 它只会把 {@code item.minecraft.enchanted_book} 这种键名原样吐出来。
     */
    static String resolve(Component component) {
        if (component == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(32);
        append(sb, component, 0);
        return sb.toString();
    }

    private static void append(StringBuilder sb, Component component, int depth) {
        if (component == null) {
            return;
        }
        // 防止极端组件树无限递归；超过深度直接退回原版 toString 级别的输出
        if (depth > 8) {
            sb.append(component.getString());
            return;
        }

        switch (component.getContents()) {
            case PlainTextContents plain -> sb.append(plain.text());
            case TranslatableContents trans -> appendTranslatable(sb, trans, depth);
            default -> sb.append(component.getString());
        }

        for (Component sibling : component.getSiblings()) {
            append(sb, sibling, depth + 1);
        }
    }

    private static void appendTranslatable(StringBuilder sb, TranslatableContents trans, int depth) {
        String template = translate(trans.getKey());
        Object[] args = trans.getArgs();

        int argIndex = 0;
        int from = 0;
        while (argIndex < args.length) {
            int pos = template.indexOf("%s", from);
            if (pos < 0) {
                break;
            }
            sb.append(template, from, pos);

            Object arg = args[argIndex++];
            if (arg instanceof Component argComponent) {
                append(sb, argComponent, depth + 1);
            } else {
                sb.append(arg);
            }
            from = pos + 2;
        }
        if (from < template.length()) {
            sb.append(template, from, template.length());
        }
    }

    private static String translate(String key) {
        // 先查模组自己的服务端语言表（服务端「模组语言」规则决定当前语言）
        String mod = ServerTranslations.resolve(Component.translatable(key));
        if (mod != null && !mod.isBlank() && !mod.equals(key)) {
            return mod;
        }
        String vanilla = vanillaEnglish().get(key);
        return vanilla != null ? vanilla : key;
    }

    // ============================================================
    // 原版 en_us
    // ============================================================

    private static Map<String, String> vanillaEnglish() {
        Map<String, String> cached = vanillaEnglish;
        if (cached != null) {
            return cached;
        }
        synchronized (LANG_LOCK) {
            if (vanillaEnglish != null) {
                return vanillaEnglish;
            }

            Map<String, String> loaded = new HashMap<>(16_384);
            try (InputStream in = TradeItemNameResolver.class.getResourceAsStream(VANILLA_EN_US)) {
                if (in == null) {
                    log.warn("未找到原版语言表 {}，村民交易查询的物品名将退回注册 id", VANILLA_EN_US);
                } else {
                    try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                            JsonElement value = entry.getValue();
                            if (value != null && value.isJsonPrimitive()) {
                                loaded.put(entry.getKey(), value.getAsString());
                            }
                        }
                    }
                    log.info("村民交易查询已加载原版语言表：{} 条", loaded.size());
                }
            } catch (Exception e) {
                log.warn("加载原版语言表失败，村民交易查询的物品名将退回注册 id：{}", VANILLA_EN_US, e);
            }

            vanillaEnglish = Collections.unmodifiableMap(loaded);
            return vanillaEnglish;
        }
    }

    // ============================================================
    // 字符串工具
    // ============================================================

    /**
     * 翻译键的特征：带 {@code .} 且没有空格（例如 {@code item.minecraft.enchanted_book}）。
     * 只用于判断「语言表没命中，拿到的是键名」这一种情况。
     */
    private static boolean looksLikeTranslationKey(String text) {
        return text.indexOf('.') >= 0 && text.indexOf(' ') < 0;
    }

    /**
     * 按注册 id 拼一个可读名：{@code minecraft:enchanted_book → Enchanted Book}。
     */
    private static String prettify(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        String path = id;
        int colon = path.indexOf(':');
        if (colon >= 0) {
            path = path.substring(colon + 1);
        }
        int dot = path.lastIndexOf('.');
        if (dot >= 0) {
            path = path.substring(dot + 1);
        }

        StringBuilder sb = new StringBuilder(path.length());
        for (String part : path.split("_")) {
            if (part.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    /**
     * 去掉玩家自定义名字里可能夹带的 {@code §} 颜色码，避免把整行聊天栏染色；
     * 顺手截断异常长的名字（模组物品 / 自定义名），别把一条结果撑成几屏。
     */
    private static String clean(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = text.replace('§', ' ').trim();
        return cleaned.length() > 128 ? cleaned.substring(0, 128) + "…" : cleaned;
    }
}
