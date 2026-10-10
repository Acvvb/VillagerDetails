package com.villagerdetails.config.impl.section.item;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.villagerdetails.config.ReloadableConfig;
import com.villagerdetails.config.impl.UnifiedConfigFile;
import com.villagerdetails.lang.ServerTranslations;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;

import static com.villagerdetails.rule.type.RuleType.SETTING_LANGUAGE;

public final class ServerLangConfig implements ReloadableConfig {

    private ServerLangConfig() {
    }

    public static final ServerLangConfig INSTANCE = new ServerLangConfig();

    private static final Logger LOGGER = LogManager.getLogger(ServerLangConfig.class);

    private static final List<String> DEFAULT_PRELOAD = List.of("en_us", "zh_cn");
    private static final String DEFAULT_LANG = "zh_cn";

    private static volatile List<String> preloadLanguage = DEFAULT_PRELOAD;
    private static volatile String defaultLang = DEFAULT_LANG;

    public static List<String> getPreloadLanguages() {
        return preloadLanguage;
    }

    /**
     * 「预加载语言」列表的<b>实时视图</b>：每次读都现取一次当前配置。
     * 直接抓 {@link #getPreloadLanguages()} 的话抓到的是当时的那个 List 实例，
     * 配置重载换了实例之后，界面上的按钮列表还停在旧的一份。
     *
     * @return 永远反映当前 {@link #preloadLanguage} 的只读视图（元素顺序与内容都跟配置走）
     */
    public static List<String> preloadLanguageView() {
        return new AbstractList<>() {
            @Override
            public String get(int index) {
                return preloadLanguage.get(index);
            }

            @Override
            public int size() {
                return preloadLanguage.size();
            }
        };
    }

    public static String getDefaultLang() {
        return defaultLang;
    }

    @Override
    public String configName() {
        return SETTING_LANGUAGE.getRegisterName();
    }

    @Override
    public void reload(MinecraftServer server) {
        loadFromWorld();
        ServerTranslations.reload();
        ServerTranslations.switchTo(defaultLang);
    }

    public static void loadFromWorld() {
        JsonObject sec = UnifiedConfigFile.getSection(SETTING_LANGUAGE.getRegisterName());
        boolean dirty = false;

        if (sec.has("preload") && sec.get("preload").isJsonArray()) {
            List<String> list = new ArrayList<>();
            for (JsonElement e : sec.getAsJsonArray("preload")) {
                if (e.isJsonPrimitive()) list.add(e.getAsString());
            }
            if (!list.isEmpty()) preloadLanguage = List.copyOf(list);
        } else {
            JsonArray arr = new JsonArray();
            DEFAULT_PRELOAD.forEach(arr::add);
            sec.add("preload", arr);
            dirty = true;
        }

        if (sec.has("default") && sec.get("default").isJsonPrimitive()) {
            defaultLang = sec.get("default").getAsString();
        } else {
            sec.addProperty("default", DEFAULT_LANG);
            dirty = true;
        }

        if (dirty) UnifiedConfigFile.save();
        LOGGER.info("preload={}, default={}", preloadLanguage, defaultLang);
    }
}