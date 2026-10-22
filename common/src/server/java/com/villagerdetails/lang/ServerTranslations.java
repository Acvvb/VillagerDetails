package com.villagerdetails.lang;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.villagerdetails.VillagerDetails;
import com.villagerdetails.config.impl.section.item.ServerLangConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.KeybindContents;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端独立翻译系统——不碰原版 Language.instance，完全隔离。
 * <p>
 * 模拟原版 {@link net.minecraft.locale.Language} 的行为：
 * · 加载 lang json 到 map
 * · Component 递归解析（PlainText / Translatable / Keybind / Score / Nbt / Selector）
 * · 支持运行时切换语言
 */
public final class ServerTranslations {

    private ServerTranslations() {
    }

    private static final Logger log = LogManager.getLogger(ServerTranslations.class);

    private static final List<String> MODS = List.of(VillagerDetails.MOD_ID);

    /**
     * 每个 key 在 json 里查找的路径前缀
     */
    private static final String LANG_PATH_PREFIX = "/assets/";
    private static final String LANG_PATH_MIDDLE = "/lang/";
    private static final String LANG_PATH_SUFFIX = ".json";

    /**
     * 当前语言代码
     */
    private static volatile String currentLang = "en_us";

    /**
     * 已加载的所有语言的翻译表：lang → (key → text)
     */
    private static final Map<String, Map<String, String>> ALL_LANGUAGES = new ConcurrentHashMap<>();

    /**
     * 当前生效的翻译表
     */
    private static volatile Map<String, String> current = Map.of();

    // ==============================================================
    // 初始化 & 切换
    // ==============================================================

    /**
     * 预加载所有可用语言（服务器启动时调一次）
     */
    public static void preloadAll() {
        for (String lang : ServerLangConfig.getPreloadLanguages()) {
            preload(lang);
        }
    }

    /**
     * 清空所有已加载语言的缓存，重新从 lang json 加载并切回原语言。
     * <p>供 {@code ServerLangConfig.reload(server)} 调用。
     */
    public static void reload() {
        String previousLang = currentLang;

        ALL_LANGUAGES.clear();
        current = Map.of();

        preloadAll();

        switchTo(previousLang);

        log.info("[ServerTranslations] 已重载：{} 种语言，当前 = {}", ALL_LANGUAGES.size(), previousLang);
    }

    public static void preload(String lang) {
        if (lang == null || lang.isEmpty()) return;

        // ★ 优化：用 computeIfAbsent，避免 containsKey + put 两次 Map 操作
        ALL_LANGUAGES.computeIfAbsent(lang, l -> {
            Map<String, String> map = new HashMap<>(256);
            for (String mod : MODS) {
                loadJsonInto(mod, l, map);
            }
            log.debug("[ServerTranslations] 预加载 {}：{} 条", l, map.size());
            return Collections.unmodifiableMap(map);
        });
    }

    /**
     * 切换语言（如果没预加载会自动加载）
     */
    public static void switchTo(String lang) {
        if (lang == null || lang.isEmpty()) return;

        // ★ 优化：目标语言和当前一致时无需再做任何事
        if (lang.equals(currentLang) && current != Map.<String, String>of()) {
            return;
        }

        Map<String, String> map = ALL_LANGUAGES.get(lang);
        if (map == null) {
            preload(lang);
            map = ALL_LANGUAGES.get(lang);
        }
        if (map == null) {
            log.warn("[ServerTranslations] 无法加载语言：{}，保持 {}", lang, currentLang);
            return;
        }
        current = map;
        currentLang = lang;
        log.debug("[ServerTranslations] 已切换为 {}", lang);
    }

    // ==============================================================
    // 纯文本解析（原 resolve）
    // ==============================================================

    /**
     * 把 Component 递归翻译成 String。
     * 等价于原版 {@code Component.getString()}，但用我们自己的翻译表。
     */
    public static String resolve(Component component) {
        if (component == null) return "";
        // ★ 优化：预估容量，减少 StringBuilder 扩容
        StringBuilder sb = new StringBuilder(64);
        appendComponent(sb, component, current);
        return sb.toString();
    }

    /**
     * 按<b>指定语言</b>解析成纯文本。
     *
     * <p>给规则描述符同步用：客户端把自己的界面语言报上来，服务端就用那份语言把名称 / 描述 /
     * 按钮文字翻译好再下发 —— 于是配置界面里「服务器 / 服务端配置」这两个分类跟<b>客户端</b>语言走，
     * 不受服务端「模组语言」规则影响（命令输出那类仍然走当前语言，见 {@link #resolve(Component)}）。
     *
     * <p>语言没预加载过就按需加载一次（读的是模组自己 jar 里的 lang json）；
     * 这份语言没有 / 加载不出来（空表）就退回当前语言，免得整屏显示成翻译键。
     */
    public static String resolve(Component component, String lang) {
        if (component == null) return "";
        StringBuilder sb = new StringBuilder(64);
        appendComponent(sb, component, tableOf(lang));
        return sb.toString();
    }

    /**
     * 取某个语言对应的翻译表；不认识 / 空表退回当前表
     */
    private static Map<String, String> tableOf(String lang) {
        if (lang == null || lang.isBlank()) {
            return current;
        }
        Map<String, String> map = ALL_LANGUAGES.get(lang);
        if (map == null) {
            preload(lang);
            map = ALL_LANGUAGES.get(lang);
        }
        return map == null || map.isEmpty() ? current : map;
    }

    private static void appendComponent(StringBuilder sb, Component component, Map<String, String> table) {
        var contents = component.getContents();

        switch (contents) {
            case PlainTextContents plain -> sb.append(plain.text());
            case TranslatableContents trans -> appendTranslatable(sb, trans, table);
            case KeybindContents keybind -> sb.append(keybind.getName());
            default -> sb.append(component.getString());
        }

        // 拼接 siblings
        List<Component> siblings = component.getSiblings();
        for (Component sibling : siblings) {
            appendComponent(sb, sibling, table);
        }
    }

    private static void appendTranslatable(StringBuilder sb, TranslatableContents trans, Map<String, String> table) {
        String key = trans.getKey();
        Object[] args = trans.getArgs();

        String template = table.get(key);
        if (template == null) {
            template = key;
        }

        if (args.length == 0) {
            sb.append(template);
            return;
        }

        // 递归翻译参数
        int argCount = args.length;
        Object[] resolvedArgs = new Object[argCount];
        for (int i = 0; i < argCount; i++) {
            Object arg = args[i];
            resolvedArgs[i] = (arg instanceof Component c) ? resolve(c) : arg;
        }

        // ★ 优化：不用 String.format（内部走 Formatter/正则），手工拼接
        appendFormatted(sb, template, resolvedArgs);
    }

    /**
     * 手工替换模板里的 %s（不做 %d/%f 转换，跟你原逻辑保持一致）。
     * 找不到 %s 时直接把参数依次附在末尾，兼容 String.format 的容错行为。
     */
    private static void appendFormatted(StringBuilder sb, String template, Object[] args) {
        int argIdx = 0;
        int from = 0;
        int tLen = template.length();

        while (argIdx < args.length) {
            int pos = template.indexOf("%s", from);
            if (pos < 0) break;
            sb.append(template, from, pos);
            Object a = args[argIdx++];
            sb.append(a == null ? "null" : a.toString());
            from = pos + 2;
        }
        // 追加剩余模板
        if (from < tLen) {
            sb.append(template, from, tLen);
        }
    }

    // ==============================================================
    // 富文本解析（原 translateComponent）
    // ==============================================================

    /**
     * 把 Component 翻译成"可直接发送"的 MutableComponent。
     * <p>
     * 规则：
     * · 服务端表里有 key → 用模板渲染
     * - 模板里的 Component 参数：保留结构，客户端翻译
     * - 模板里的 String 参数：按 § 颜色继承，直接拼进字面量
     * · 服务端表里没有 key → 原样保留 translatable，交给客户端翻译
     * · 样式（ClickEvent / HoverEvent / 颜色 / 加粗）整套搬运
     */
    public static MutableComponent translateComponent(Component src) {
        if (src == null) return Component.empty();
        // ★ 优化：volatile 读一次，往下传
        return translateComponent(src, current);
    }

    private static MutableComponent translateComponent(Component src, Map<String, String> table) {
        MutableComponent out;
        var contents = src.getContents();

        switch (contents) {
            case TranslatableContents trans -> {
                String key = trans.getKey();
                Object[] args = trans.getArgs();
                String template = table.get(key);

                if (template == null) {
                    // ── 服务端表里没有这个 key → 保留 translatable，交给客户端 ──
                    int n = args.length;
                    Object[] copied = new Object[n];
                    for (int i = 0; i < n; i++) {
                        Object a = args[i];
                        copied[i] = (a instanceof Component c) ? translateComponent(c, table) : a;
                    }
                    out = Component.translatable(key, copied);

                } else if (args.length == 0) {
                    // ★ 优化：无参数直接 literal
                    out = Component.literal(template);

                } else {
                    // ── 服务端表里有 key → 手工按 %s 拆分 ──
                    out = Component.empty();

                    int argIdx = 0;
                    int from = 0;
                    int tLen = template.length();
                    String pendingColor = "";

                    while (argIdx < args.length) {
                        int pos = template.indexOf("%s", from);
                        if (pos < 0) break;

                        String seg = template.substring(from, pos);
                        from = pos + 2;

                        int lastIdx = -1;
                        for (int j = seg.length() - 2; j >= 0; j--) {
                            if (seg.charAt(j) == '§') {
                                lastIdx = j;
                                break;
                            }
                        }
                        if (lastIdx >= 0) {
                            pendingColor = seg.substring(lastIdx, lastIdx + 2);
                        }

                        if (!seg.isEmpty()) {
                            // 每段都自带颜色码：§ 的颜色只在它所在的那段字面量里生效
                            //（组件边界会断），所以不能指望「上一段结尾的 §x」把这一整段也染上
                            out.append(Component.literal(pendingColor + seg));
                        }

                        Object a = args[argIdx++];
                        if (a instanceof Component c) {
                            // 参数是组件时，颜色得直接贴到组件自己身上（见 colorize 的注释）
                            out.append(colorize(translateComponent(c, table), pendingColor));
                        } else {
                            out.append(Component.literal(pendingColor + a));
                        }
                    }

                    // 追加剩余模板（含尾部 %% 或普通文本）
                    if (from < tLen) {
                        String tail = template.substring(from);
                        if (tail.contains("%%")) tail = tail.replace("%%", "%");
                        out.append(Component.literal(pendingColor + tail));
                    }
                }
            }
            case PlainTextContents plain -> out = Component.literal(plain.text());
            case KeybindContents keybind -> out = Component.keybind(keybind.getName());
            default -> out = Component.literal(src.getString());
        }

        out.setStyle(src.getStyle());
        List<Component> siblings = src.getSiblings();
        for (Component sibling : siblings) {
            out.append(translateComponent(sibling, table));
        }

        return out;
    }

    /**
     * 把模板里的「旧式颜色码」（{@code §c}）贴成组件真正的 {@link Style} 颜色。
     *
     * <p>为什么不能像字符串参数那样「插一个 {@code §c} 字面量了事」：颜色码只对<b>同一段</b>
     * 字面量生效，跨组件边界就断了 —— 而参数是 {@code block.minecraft.xxx} 这种
     * 「服务端不翻译、交给客户端翻译」的 translatable 时，插进去的 {@code §c} 根本罩不住它，
     * 客户端渲染出来就是白的。所以这里把颜色真正设到组件上，让它在客户端翻译后依然有色。
     */
    private static MutableComponent colorize(MutableComponent component, String legacyColor) {
        if (legacyColor == null || legacyColor.length() < 2) {
            return component;
        }
        ChatFormatting format = ChatFormatting.getByCode(legacyColor.charAt(1));
        if (format == null) {
            return component;
        }
        // 颜色码 → 真正的 Style 颜色；§l / §o 这类修饰码在这里会退化成「不设颜色」，
        // 等于保持原样，不会出错（模板里目前只用颜色码）
        return component.withStyle(Style.EMPTY.withColor(format));
    }

    // ==============================================================
    // 文件加载
    // ==============================================================

    private static void loadJsonInto(String mod, String lang, Map<String, String> target) {
        // ★ 优化：String 拼接一次而不是四次
        String path = LANG_PATH_PREFIX + mod + LANG_PATH_MIDDLE + lang + LANG_PATH_SUFFIX;
        try (InputStream is = ServerTranslations.class.getResourceAsStream(path)) {
            if (is == null) return;
            try (InputStreamReader r = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
                int loaded = 0;
                for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                    JsonElement v = e.getValue();
                    if (v != null && v.isJsonPrimitive()) {
                        target.put(e.getKey(), v.getAsString());
                        loaded++;
                    }
                }
                log.debug("[ServerTranslations] {}@{} 加载 {} 条", mod, lang, loaded);
            }
        } catch (Exception e) {
            log.error("[ServerTranslations] 加载失败：{}", path, e);
        }
    }
}