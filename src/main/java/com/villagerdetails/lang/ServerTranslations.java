package com.villagerdetails.lang;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.villagerdetails.VillagerDetails;
import com.villagerdetails.config.ServerLangConfig;
import net.minecraft.network.chat.Component;
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
 *   · 加载 lang json 到 map
 *   · Component 递归解析（PlainText / Translatable / Keybind / Score / Nbt / Selector）
 *   · 支持运行时切换语言
 */
public final class ServerTranslations {

    private ServerTranslations() {}

    private static final Logger log = LogManager.getLogger(ServerTranslations.class);

    private static final List<String> MODS = List.of(VillagerDetails.MOD_ID);

    /** 当前语言代码 */
    private static volatile String currentLang = "en_us";

    /** 已加载的所有语言的翻译表：lang → (key → text) */
    private static final Map<String, Map<String, String>> ALL_LANGUAGES = new ConcurrentHashMap<>();

    /** 当前生效的翻译表 */
    private static volatile Map<String, String> current = Map.of();

    /** 预加载所有可用语言（服务器启动时调一次） */
    public static void preloadAll() {
        for (String lang : ServerLangConfig.getPreloadLanguages()) {
            preload(lang);
        }
    }

    public static void preload(String lang) {
        if (ALL_LANGUAGES.containsKey(lang)) return;

        Map<String, String> map = new HashMap<>();
        for (String mod : MODS) {
            loadJsonInto(mod, lang, map);
        }
        ALL_LANGUAGES.put(lang, Collections.unmodifiableMap(map));
        log.debug("[ServerTranslations] 预加载 {}：{} 条", lang, map.size());
    }

    /** 切换语言（如果没预加载会自动加载） */
    public static void switchTo(String lang) {
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

    /**
     * 把 Component 递归翻译成 String。
     * 等价于原版 {@code Component.getString()}，但用我们自己的翻译表。
     */
    public static String resolve(Component component) {
        if (component == null) return "";
        StringBuilder sb = new StringBuilder();
        appendComponent(sb, component);
        return sb.toString();
    }

    private static void appendComponent(StringBuilder sb, Component component) {
        var contents = component.getContents();

        switch (contents) {
            case PlainTextContents plain -> sb.append(plain.text());
            case TranslatableContents trans -> appendTranslatable(sb, trans);
            case KeybindContents keybind -> sb.append(keybind.getName());
            default -> sb.append(component.getString());
        }

        // 拼接 siblings
        for (Component sibling : component.getSiblings()) {
            appendComponent(sb, sibling);
        }
    }

    private static void appendTranslatable(StringBuilder sb, TranslatableContents trans) {
        String key = trans.getKey();
        Object[] args = trans.getArgs();

        // 递归翻译参数
        Object[] resolvedArgs = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg instanceof Component c) {
                resolvedArgs[i] = resolve(c);
            } else {
                resolvedArgs[i] = arg;
            }
        }

        // 查表
        String template = current.get(key);
        if (template == null) {
            // 查不到：用 key 本身，参数填进去（模拟原版行为）
            template = key;
        }

        try {
            sb.append(String.format(template, resolvedArgs));
        } catch (Exception e) {
            sb.append(template);
        }
    }

    private static void loadJsonInto(String mod, String lang, Map<String, String> target) {
        String path = "/assets/" + mod + "/lang/" + lang + ".json";
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

    /**
     * 把 Component 翻译成"可直接发送"的 MutableComponent。
     *
     * 规则：
     *   · 服务端表里有 key → 用模板渲染
     *       - 模板里的 Component 参数：保留结构，客户端翻译
     *       - 模板里的 String 参数：按 § 颜色继承，直接拼进字面量
     *   · 服务端表里没有 key → 原样保留 translatable，交给客户端翻译
     *   · 样式（ClickEvent / HoverEvent / 颜色 / 加粗）整套搬运
     */
    public static net.minecraft.network.chat.MutableComponent translateComponent(Component src) {
        if (src == null) return Component.empty();

        net.minecraft.network.chat.MutableComponent out;
        var contents = src.getContents();

        if (contents instanceof TranslatableContents trans) {
            String key = trans.getKey();
            Object[] args = trans.getArgs();
            String template = current.get(key);

            if (template == null) {
                // ── 服务端表里没有这个 key → 保留 translatable，交给客户端 ──
                Object[] copied = new Object[args.length];
                for (int i = 0; i < args.length; i++) {
                    Object a = args[i];
                    copied[i] = (a instanceof Component c) ? translateComponent(c) : a;
                }
                out = Component.translatable(key, copied);

            } else {
                // ── 服务端表里有 key → 按 %s 拆分 ──
                out = Component.empty();
                String[] parts = template.split("%s", -1);

                // 追踪"当前生效的 § 颜色码"，让它能延续到下一个参数
                String pendingColor = "";

                for (int i = 0; i < parts.length; i++) {
                    String seg = parts[i];

                    // 记下这段里最后一个 §X
                    int lastIdx = -1;
                    for (int j = seg.length() - 2; j >= 0; j--) {
                        if (seg.charAt(j) == '§') { lastIdx = j; break; }
                    }
                    if (lastIdx >= 0) {
                        pendingColor = seg.substring(lastIdx, lastIdx + 2);
                    }

                    // 把该段原样 append（里面自带的 § 码会被 Minecraft 解析）
                    if (!seg.isEmpty()) {
                        out.append(Component.literal(seg.replace("%%", "%")));
                    }

                    // 把参数 append
                    if (i < args.length) {
                        Object a = args[i];
                        if (a instanceof Component c) {
                            // ★ Component 参数：保留结构，客户端翻译
                            //   为了颜色跟随，先把颜色码 append 出去
                            if (!pendingColor.isEmpty()) {
                                out.append(Component.literal(pendingColor));
                            }
                            out.append(translateComponent(c));
                        } else {
                            // String / 数字 参数：直接拼进带颜色码的字面量
                            out.append(Component.literal(pendingColor + a));
                        }
                    }
                }
            }

        } else if (contents instanceof PlainTextContents plain) {
            out = Component.literal(plain.text());

        } else if (contents instanceof KeybindContents keybind) {
            out = Component.keybind(keybind.getName());

        } else {
            out = Component.literal(src.getString());
        }

        // 样式整套搬运（ClickEvent / HoverEvent / color / bold 等）
        out.setStyle(src.getStyle());

        // 递归 siblings
        for (Component sibling : src.getSiblings()) {
            out.append(translateComponent(sibling));
        }

        return out;
    }
}