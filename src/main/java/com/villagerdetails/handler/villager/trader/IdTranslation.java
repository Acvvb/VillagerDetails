package com.villagerdetails.handler.villager.trader;

import net.minecraft.resources.Identifier;

import java.util.Map;

public final class IdTranslation {

    private IdTranslation() {}   // 工具类，禁止实例化

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

    /**
     * 把玩家输入的中文名转成附魔 ID。
     * 支持：中文全名（经验修补）、英文 ID（mending）、带命名空间（minecraft:mending）
     *
     * @return 解析成功的 Identifier，失败返回 null
     */
    public static Identifier resolveEnchantId(String raw) {
        if (raw == null) return null;
        String trimmed = raw.replace('\u3000', ' ').trim();
        String mapped = CN_TO_ENCHANT.get(trimmed);
        if (mapped != null) {
            return Identifier.tryParse("minecraft:" + mapped);
        }
        String lower = trimmed.toLowerCase();
        Identifier id = Identifier.tryParse(lower);
        if (id == null) id = Identifier.tryParse("minecraft:" + lower);
        return id;
    }
}