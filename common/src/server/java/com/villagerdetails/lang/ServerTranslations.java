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
import java.util.*;
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
        return translateComponent(src, current);
    }

    /**
     * 将组件树翻译为目标组件树，等价于原递归实现的语义。
     * 采用显式栈做后序遍历：每个组件对应一个 {@link Task}，父组件展开时
     * 为每个子组件（含 translatable 参数中的 Component）创建独立 Task，
     * 因此同一 Component 实例在不同父下仍会被独立翻译。
     */
    private static MutableComponent translateComponent(Component root, Map<String, String> table) {
        Task rootTask = new Task(root);
        Deque<Task> stack = new ArrayDeque<>();
        stack.push(rootTask);

        while (!stack.isEmpty()) {
            Task t = stack.peek();
            if (!t.expanded) {
                t.expanded = true;
                expandAndPush(t, stack);
            } else {
                stack.pop();
                t.result = build(t, table);
            }
        }
        return rootTask.result;
    }

    /**
     * 首次访问某个 Task 时，为它的参数（仅 Component 类型）和兄弟节点
     * 创建子 Task 并压栈，同时把 Task 引用记录到 argRefs / siblingTasks。
     */
    private static void expandAndPush(Task t, Deque<Task> stack) {
        if (t.src.getContents() instanceof TranslatableContents trans) {
            Object[] args = trans.getArgs();
            Object[] refs = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof Component c) {
                    Task child = new Task(c);
                    refs[i] = child;
                    stack.push(child);
                } else {
                    refs[i] = args[i];
                }
            }
            t.argRefs = refs;
        }
        for (Component sib : t.src.getSiblings()) {
            Task child = new Task(sib);
            t.siblingTasks.add(child);
            stack.push(child);
        }
    }

    /**
     * 子 Task 全部完成后构建当前组件：按 contents 类型分派，
     * 复制样式，再按顺序拼接各兄弟 Task 的结果。
     */
    private static MutableComponent build(Task t, Map<String, String> table) {
        MutableComponent out = switch (t.src.getContents()) {
            case TranslatableContents trans -> buildTranslatable(trans, t, table);
            case PlainTextContents plain -> Component.literal(plain.text());
            case KeybindContents keybind -> Component.keybind(keybind.getName());
            default -> {
                MutableComponent copy = t.src.copy();
                copy.getSiblings().clear();
                yield copy;
            }
        };

        out.setStyle(t.src.getStyle());
        for (Task sib : t.siblingTasks) {
            out.append(sib.result);
        }
        return out;
    }

    /**
     * 构建 translatable 组件：表内没有对应键时保留原 key，
     * 仅把 Component 类型参数替换为子 Task 的翻译结果；
     * 有对应模板时按模板渲染（%% 转义、%s 占位、颜色继承）。
     */
    private static MutableComponent buildTranslatable(TranslatableContents trans, Task t,
                                                      Map<String, String> table) {
        String key = trans.getKey();
        Object[] args = trans.getArgs();
        String template = table.get(key);

        if (template == null) {
            Object[] copied = args;
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof Component) {
                    if (copied == args) copied = args.clone();
                    copied[i] = ((Task) t.argRefs[i]).result;
                }
            }
            return Component.translatable(key, copied);
        }

        if (args.length == 0) {
            return Component.literal(unescapePercent(template));
        }

        return renderTemplate(template, t.argRefs);
    }

    /**
     * 按模板渲染参数：逐段切割 %s，处理 %% 转义，并把最近出现的 §x 颜色码
     * 传递给参数和后续文本。argRefs 中的 Component 参数已由子 Task 构建好。
     */
    private static MutableComponent renderTemplate(String template, Object[] argRefs) {
        MutableComponent out = Component.empty();

        int argIdx = 0;
        int from = 0;
        int len = template.length();
        String pendingColor = "";

        while (argIdx < argRefs.length) {
            int pos = template.indexOf("%s", from);
            if (pos < 0) break;

            String seg = template.substring(from, pos);
            from = pos + 2;
            if (!seg.isEmpty()) {
                out.append(Component.literal(pendingColor + unescapePercent(seg)));
            }

            int lastIdx = seg.lastIndexOf('§');
            if (lastIdx >= 0 && lastIdx + 1 < seg.length()) {
                pendingColor = seg.substring(lastIdx, lastIdx + 2);
            }

            Object ref = argRefs[argIdx++];
            if (ref instanceof Task task) {
                out.append(colorize(task.result, pendingColor));
            } else {
                out.append(Component.literal(pendingColor + ref));
            }
        }

        if (from < len) {
            out.append(Component.literal(pendingColor + unescapePercent(template.substring(from))));
        }
        return out;
    }

    /** 将字符串中的 %% 还原为 %；不含 %% 时直接返回原引用。 */
    private static String unescapePercent(String s) {
        return s.contains("%%") ? s.replace("%%", "%") : s;
    }

    /**
     * 迭代翻译用的任务节点：src 为源组件，result 为翻译结果。
     * 仅 Translatable 会用到 argRefs；siblingTasks 与 src 的兄弟节点一一对应。
     */
    private static final class Task {
        final Component src;
        final List<Task> siblingTasks = new ArrayList<>();
        Object[] argRefs;
        MutableComponent result;
        boolean expanded;

        Task(Component src) {
            this.src = src;
        }
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