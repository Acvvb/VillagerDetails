package com.villagerdetails.handler.villager.trader;

import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

public final class IdTranslation {

    private IdTranslation() {}

    // ==============================================================
    // 附魔中文映射
    // ==============================================================

    private static final Map<String, String> CN_TO_ENCHANT = Map.ofEntries(
            // 保护类
            Map.entry("保护", "protection"),
            Map.entry("火焰保护", "fire_protection"),
            Map.entry("摔落保护", "feather_falling"),
            Map.entry("爆炸保护", "blast_protection"),
            Map.entry("弹射物保护", "projectile_protection"),
            Map.entry("水下呼吸", "respiration"),
            Map.entry("水下速掘", "aqua_affinity"),
            Map.entry("荆棘", "thorns"),
            Map.entry("深海探索者", "depth_strider"),
            Map.entry("冰霜行者", "frost_walker"),
            Map.entry("绑定诅咒", "binding_curse"),
            Map.entry("灵魂疾行", "soul_speed"),
            Map.entry("迅捷潜行", "swift_sneak"),

            // 武器类
            Map.entry("锋利", "sharpness"),
            Map.entry("亡灵杀手", "smite"),
            Map.entry("节肢杀手", "bane_of_arthropods"),
            Map.entry("击退", "knockback"),
            Map.entry("火焰附加", "fire_aspect"),
            Map.entry("抢夺", "looting"),
            Map.entry("横扫之刃", "sweeping_edge"),

            // 工具类
            Map.entry("效率", "efficiency"),
            Map.entry("精准采集", "silk_touch"),
            Map.entry("耐久", "unbreaking"),
            Map.entry("时运", "fortune"),

            // 弓类
            Map.entry("力量", "power"),
            Map.entry("冲击", "punch"),
            Map.entry("火矢", "flame"),
            Map.entry("无限", "infinity"),

            // 钓鱼竿
            Map.entry("海之眷顾", "luck_of_the_sea"),
            Map.entry("饵钓", "lure"),

            // 三叉戟
            Map.entry("忠诚", "loyalty"),
            Map.entry("穿刺", "impaling"),
            Map.entry("激流", "riptide"),
            Map.entry("引雷", "channeling"),

            // 弩
            Map.entry("多重射击", "multishot"),
            Map.entry("快速装填", "quick_charge"),
            Map.entry("穿透", "piercing"),

            // 通用
            Map.entry("经验修补", "mending"),
            Map.entry("消失诅咒", "vanishing_curse")
    );

    // ==============================================================
    // 物品中文映射：只加 16 种陶瓦
    // ==============================================================

    private static final Map<String, String> CN_TO_ITEM = buildItemMap();

    private static Map<String, String> buildItemMap() {
        Map<String, String> m = new HashMap<>();
        m.put("白色陶瓦", "white_terracotta");
        m.put("橙色陶瓦", "orange_terracotta");
        m.put("品红色陶瓦", "magenta_terracotta");
        m.put("淡蓝色陶瓦", "light_blue_terracotta");
        m.put("黄色陶瓦", "yellow_terracotta");
        m.put("黄绿色陶瓦", "lime_terracotta");
        m.put("粉红色陶瓦", "pink_terracotta");
        m.put("灰色陶瓦", "gray_terracotta");
        m.put("淡灰色陶瓦", "light_gray_terracotta");
        m.put("青色陶瓦", "cyan_terracotta");
        m.put("紫色陶瓦", "purple_terracotta");
        m.put("蓝色陶瓦", "blue_terracotta");
        m.put("棕色陶瓦", "brown_terracotta");
        m.put("绿色陶瓦", "green_terracotta");
        m.put("红色陶瓦", "red_terracotta");
        m.put("黑色陶瓦", "black_terracotta");
        return Map.copyOf(m);
    }

    // ==============================================================
    // 解析入口
    // ==============================================================

    /**
     * 把玩家输入的名字转成 Identifier。
     * 顺序：附魔中文 → 物品中文 → 英文 ID。
     *
     * @return 解析成功的 Identifier，失败返回 null
     */
    public static Identifier resolveEnchantId(String raw) {
        if (raw == null) return null;

        String trimmed = raw.replace('\u3000', ' ').trim();
        if (trimmed.isEmpty()) return null;

        // 1) 附魔中文名
        String enchantMapped = CN_TO_ENCHANT.get(trimmed);
        if (enchantMapped != null) {
            return Identifier.tryParse("minecraft:" + enchantMapped);
        }

        // 2) 物品中文名（目前只有 16 种陶瓦）
        String itemMapped = CN_TO_ITEM.get(trimmed);
        if (itemMapped != null) {
            return Identifier.tryParse("minecraft:" + itemMapped);
        }

        // 3) 英文 ID 兜底
        String lower = trimmed.toLowerCase();
        Identifier id = Identifier.tryParse(lower);
        if (id == null) id = Identifier.tryParse("minecraft:" + lower);
        return id;
    }
}