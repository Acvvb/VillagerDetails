package com.villagerdetails.config.impl.data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class VillagerTradeItemRegistry {

    private VillagerTradeItemRegistry() {}

    public static Map<String, List<String>> getDefaultIndex() {
        return DEFAULT_INDEX;
    }

    private static final Map<String, List<String>> DEFAULT_INDEX = buildDefaultIndex();

    private static Map<String, List<String>> buildDefaultIndex() {
        Map<String, List<String>> map = new LinkedHashMap<>();

        // ============================================================
        // 1. 附魔（全部 43 种，不写 minecraft: 前缀）
        // ============================================================
        put(map, "protection", "保护");
        put(map, "fire_protection", "火焰保护");
        put(map, "feather_falling", "摔落保护");
        put(map, "blast_protection", "爆炸保护");
        put(map, "projectile_protection", "弹射物保护");
        put(map, "respiration", "水下呼吸");
        put(map, "aqua_affinity", "水下速掘");
        put(map, "thorns", "荆棘");
        put(map, "depth_strider", "深海探索者");
        put(map, "frost_walker", "冰霜行者");
        put(map, "binding_curse", "绑定诅咒");
        put(map, "soul_speed", "灵魂疾行");
        put(map, "swift_sneak", "迅捷潜行");
        put(map, "sharpness", "锋利");
        put(map, "smite", "亡灵杀手");
        put(map, "bane_of_arthropods", "节肢杀手");
        put(map, "knockback", "击退");
        put(map, "fire_aspect", "火焰附加");
        put(map, "looting", "抢夺");
        put(map, "sweeping_edge", "横扫之刃");
        put(map, "efficiency", "效率");
        put(map, "silk_touch", "精准采集");
        put(map, "unbreaking", "耐久");
        put(map, "fortune", "时运");
        put(map, "power", "力量");
        put(map, "punch", "冲击");
        put(map, "flame", "火矢");
        put(map, "infinity", "无限");
        put(map, "luck_of_the_sea", "海之眷顾");
        put(map, "lure", "饵钓");
        put(map, "loyalty", "忠诚");
        put(map, "impaling", "穿刺");
        put(map, "riptide", "激流");
        put(map, "channeling", "引雷");
        put(map, "multishot", "多重射击");
        put(map, "quick_charge", "快速装填");
        put(map, "piercing", "穿透");
        put(map, "density", "致密");
        put(map, "breach", "破甲");
        put(map, "wind_burst", "风爆");
        put(map, "lunge", "突进");
        put(map, "mending", "经验修补");
        put(map, "vanishing_curse", "消失诅咒");

        // ============================================================
        // 2. 特殊物品（附魔书不带前缀，其他的正常带）
        // ============================================================
        put(map, "enchanted_book", "附魔书");
        put(map, "minecraft:book", "书");
        put(map, "minecraft:writable_book", "书与笔");
        put(map, "minecraft:written_book", "成书");

        // ============================================================
        // 3. 付款 / 通用交易物
        // ============================================================
        put(map, "minecraft:emerald", "绿宝石");
        put(map, "minecraft:paper", "纸");
        put(map, "minecraft:clay_ball", "黏土球");
        put(map, "minecraft:brick", "红砖");
        put(map, "minecraft:nether_brick", "下界砖");
        put(map, "minecraft:quartz", "下界石英");

        // ============================================================
        // 4. 矿石 / 材料 / 农作物
        // ============================================================
        put(map, "minecraft:stone", "石头");
        put(map, "minecraft:andesite", "安山岩");
        put(map, "minecraft:diorite", "闪长岩");
        put(map, "minecraft:granite", "花岗岩");
        put(map, "minecraft:coal", "煤炭");
        put(map, "minecraft:charcoal", "木炭");
        put(map, "minecraft:iron_ingot", "铁锭");
        put(map, "minecraft:gold_ingot", "金锭");
        put(map, "minecraft:diamond", "钻石");
        put(map, "minecraft:lapis_lazuli", "青金石");
        put(map, "minecraft:redstone", "红石");
        put(map, "minecraft:string", "线");
        put(map, "minecraft:leather", "皮革");
        put(map, "minecraft:flint", "燧石");
        put(map, "minecraft:glowstone", "荧石");
        put(map, "minecraft:ender_pearl", "末影珍珠");
        put(map, "minecraft:experience_bottle", "附魔之瓶");
        put(map, "minecraft:rotten_flesh", "腐肉");
        put(map, "minecraft:rabbit_foot", "兔子脚");
        put(map, "minecraft:rabbit_hide", "兔子皮");
        put(map, "minecraft:pumpkin", "南瓜");
        put(map, "minecraft:wheat", "小麦");
        put(map, "minecraft:wheat_seeds", "小麦种子");
        put(map, "minecraft:potato", "马铃薯");
        put(map, "minecraft:baked_potato", "烤马铃薯");
        put(map, "minecraft:carrot", "胡萝卜");
        put(map, "minecraft:beetroot", "甜菜根");
        put(map, "minecraft:beetroot_seeds", "甜菜种子");
        put(map, "minecraft:melon_seeds", "西瓜种子");
        put(map, "minecraft:pumpkin_seeds", "南瓜种子");
        put(map, "minecraft:sugar_cane", "甘蔗");
        put(map, "minecraft:cactus", "仙人掌");
        put(map, "minecraft:sweet_berries", "甜浆果");
        put(map, "minecraft:dried_kelp_block", "干海带块");
        put(map, "minecraft:hay_block", "干草捆");
        put(map, "minecraft:cocoa_beans", "可可豆");

        // ============================================================
        // 5. 食物
        // ============================================================
        put(map, "minecraft:bread", "面包");
        put(map, "minecraft:cake", "蛋糕");
        put(map, "minecraft:cookie", "曲奇");
        put(map, "minecraft:pumpkin_pie", "南瓜派");
        put(map, "minecraft:apple", "苹果");
        put(map, "minecraft:golden_apple", "金苹果");
        put(map, "minecraft:cooked_porkchop", "熟猪排");
        put(map, "minecraft:cooked_chicken", "熟鸡肉");
        put(map, "minecraft:cooked_beef", "熟牛肉");
        put(map, "minecraft:cooked_mutton", "熟羊肉");
        put(map, "minecraft:cooked_rabbit", "熟兔肉");
        put(map, "minecraft:cooked_cod", "熟鳕鱼");
        put(map, "minecraft:cooked_salmon", "熟鲑鱼");
        put(map, "minecraft:rabbit_stew", "兔肉煲");
        put(map, "minecraft:beetroot_soup", "甜菜汤");
        put(map, "minecraft:mushroom_stew", "蘑菇煲");
        put(map, "minecraft:suspicious_stew", "迷之炖菜");
        put(map, "minecraft:raw_porkchop", "生猪排");
        put(map, "minecraft:raw_chicken", "生鸡肉");
        put(map, "minecraft:raw_beef", "生牛肉");
        put(map, "minecraft:raw_mutton", "生羊肉");
        put(map, "minecraft:raw_rabbit", "生兔肉");
        put(map, "minecraft:raw_cod", "生鳕鱼");
        put(map, "minecraft:raw_salmon", "生鲑鱼");
        put(map, "minecraft:tropical_fish", "热带鱼");
        put(map, "minecraft:pufferfish", "河豚");

        // ============================================================
        // 6. 常见成品（工具/武器/盔甲/杂项）
        // ============================================================
        put(map, "minecraft:bookshelf", "书架");
        put(map, "minecraft:painting", "画");
        put(map, "minecraft:name_tag", "命名牌");
        put(map, "minecraft:saddle", "鞍");
        put(map, "minecraft:bell", "钟");
        put(map, "minecraft:glass", "玻璃");
        put(map, "minecraft:glass_pane", "玻璃板");
        put(map, "minecraft:lantern", "灯笼");
        put(map, "minecraft:candle", "蜡烛");
        put(map, "minecraft:clock", "时钟");
        put(map, "minecraft:compass", "指南针");
        put(map, "minecraft:ink_sac", "墨囊");
        put(map, "minecraft:arrow", "箭");
        put(map, "minecraft:bow", "弓");
        put(map, "minecraft:crossbow", "弩");
        put(map, "minecraft:fishing_rod", "钓鱼竿");
        put(map, "minecraft:shield", "盾牌");
        put(map, "minecraft:flint_and_steel", "打火石");
        put(map, "minecraft:shears", "剪刀");
        put(map, "minecraft:bucket", "铁桶");
        put(map, "minecraft:water_bucket", "水桶");
        put(map, "minecraft:milk_bucket", "奶桶");
        put(map, "minecraft:lava_bucket", "熔岩桶");
        put(map, "minecraft:campfire", "营火");
        put(map, "minecraft:torch", "火把");
        put(map, "minecraft:oak_boat", "橡木船");
        put(map, "minecraft:spruce_boat", "云杉木船");
        put(map, "minecraft:birch_boat", "白桦木船");
        put(map, "minecraft:jungle_boat", "丛林木船");
        put(map, "minecraft:acacia_boat", "金合欢木船");
        put(map, "minecraft:dark_oak_boat", "深色橡木船");

        // 工具
        put(map, "minecraft:stone_axe", "石斧");
        put(map, "minecraft:stone_shovel", "石锹");
        put(map, "minecraft:stone_pickaxe", "石镐");
        put(map, "minecraft:stone_hoe", "石锄");
        put(map, "minecraft:iron_axe", "铁斧");
        put(map, "minecraft:iron_shovel", "铁锹");
        put(map, "minecraft:iron_pickaxe", "铁镐");
        put(map, "minecraft:iron_hoe", "铁锄");
        put(map, "minecraft:diamond_axe", "钻石斧");
        put(map, "minecraft:diamond_shovel", "钻石锹");
        put(map, "minecraft:diamond_pickaxe", "钻石镐");
        put(map, "minecraft:diamond_hoe", "钻石锄");

        // 盔甲
        put(map, "minecraft:leather_helmet", "皮革帽子");
        put(map, "minecraft:leather_chestplate", "皮革外套");
        put(map, "minecraft:leather_leggings", "皮革裤子");
        put(map, "minecraft:leather_boots", "皮革靴子");
        put(map, "minecraft:chainmail_helmet", "锁链头盔");
        put(map, "minecraft:chainmail_chestplate", "锁链胸甲");
        put(map, "minecraft:chainmail_leggings", "锁链护腿");
        put(map, "minecraft:chainmail_boots", "锁链靴子");
        put(map, "minecraft:iron_helmet", "铁头盔");
        put(map, "minecraft:iron_chestplate", "铁胸甲");
        put(map, "minecraft:iron_leggings", "铁护腿");
        put(map, "minecraft:iron_boots", "铁靴子");
        put(map, "minecraft:diamond_helmet", "钻石头盔");
        put(map, "minecraft:diamond_chestplate", "钻石胸甲");
        put(map, "minecraft:diamond_leggings", "钻石护腿");
        put(map, "minecraft:diamond_boots", "钻石靴子");

        // ============================================================
        // 7. 染料 & 陶瓦
        // ============================================================
        put(map, "minecraft:white_dye", "白色染料");
        put(map, "minecraft:light_gray_dye", "淡灰色染料");
        put(map, "minecraft:gray_dye", "灰色染料");
        put(map, "minecraft:black_dye", "黑色染料");
        put(map, "minecraft:brown_dye", "棕色染料");
        put(map, "minecraft:red_dye", "红色染料");
        put(map, "minecraft:orange_dye", "橙色染料");
        put(map, "minecraft:yellow_dye", "黄色染料");
        put(map, "minecraft:lime_dye", "黄绿色染料");
        put(map, "minecraft:green_dye", "绿色染料");
        put(map, "minecraft:cyan_dye", "青色染料");
        put(map, "minecraft:light_blue_dye", "淡蓝色染料");
        put(map, "minecraft:blue_dye", "蓝色染料");
        put(map, "minecraft:purple_dye", "紫色染料");
        put(map, "minecraft:magenta_dye", "品红色染料");
        put(map, "minecraft:pink_dye", "粉红色染料");

        put(map, "minecraft:white_terracotta", "白色陶瓦");
        put(map, "minecraft:orange_terracotta", "橙色陶瓦");
        put(map, "minecraft:magenta_terracotta", "品红色陶瓦");
        put(map, "minecraft:light_blue_terracotta", "淡蓝色陶瓦");
        put(map, "minecraft:yellow_terracotta", "黄色陶瓦");
        put(map, "minecraft:lime_terracotta", "黄绿色陶瓦");
        put(map, "minecraft:pink_terracotta", "粉红色陶瓦");
        put(map, "minecraft:gray_terracotta", "灰色陶瓦");
        put(map, "minecraft:light_gray_terracotta", "淡灰色陶瓦");
        put(map, "minecraft:cyan_terracotta", "青色陶瓦");
        put(map, "minecraft:purple_terracotta", "紫色陶瓦");
        put(map, "minecraft:blue_terracotta", "蓝色陶瓦");
        put(map, "minecraft:brown_terracotta", "棕色陶瓦");
        put(map, "minecraft:green_terracotta", "绿色陶瓦");
        put(map, "minecraft:red_terracotta", "红色陶瓦");
        put(map, "minecraft:black_terracotta", "黑色陶瓦");

        put(map, "minecraft:white_glazed_terracotta", "白色带釉陶瓦");
        put(map, "minecraft:orange_glazed_terracotta", "橙色带釉陶瓦");
        put(map, "minecraft:magenta_glazed_terracotta", "品红色带釉陶瓦");
        put(map, "minecraft:light_blue_glazed_terracotta", "淡蓝色带釉陶瓦");
        put(map, "minecraft:yellow_glazed_terracotta", "黄色带釉陶瓦");
        put(map, "minecraft:lime_glazed_terracotta", "黄绿色带釉陶瓦");
        put(map, "minecraft:pink_glazed_terracotta", "粉红色带釉陶瓦");
        put(map, "minecraft:gray_glazed_terracotta", "灰色带釉陶瓦");
        put(map, "minecraft:light_gray_glazed_terracotta", "淡灰色带釉陶瓦");
        put(map, "minecraft:cyan_glazed_terracotta", "青色带釉陶瓦");
        put(map, "minecraft:purple_glazed_terracotta", "紫色带釉陶瓦");
        put(map, "minecraft:blue_glazed_terracotta", "蓝色带釉陶瓦");
        put(map, "minecraft:brown_glazed_terracotta", "棕色带釉陶瓦");
        put(map, "minecraft:green_glazed_terracotta", "绿色带釉陶瓦");
        put(map, "minecraft:red_glazed_terracotta", "红色带釉陶瓦");
        put(map, "minecraft:black_glazed_terracotta", "黑色带釉陶瓦");

        // ============================================================
        // 8. 流浪商人特殊物品（珊瑚 / 树苗 / 杂项）
        // ============================================================
        put(map, "minecraft:tube_coral_block", "管珊瑚块");
        put(map, "minecraft:brain_coral_block", "脑纹珊瑚块");
        put(map, "minecraft:bubble_coral_block", "气泡珊瑚块");
        put(map, "minecraft:fire_coral_block", "火珊瑚块");
        put(map, "minecraft:horn_coral_block", "鹿角珊瑚块");
        put(map, "minecraft:dead_tube_coral_block", "失活的管珊瑚块");
        put(map, "minecraft:dead_brain_coral_block", "失活的脑纹珊瑚块");
        put(map, "minecraft:dead_bubble_coral_block", "失活的气泡珊瑚块");
        put(map, "minecraft:dead_fire_coral_block", "失活的火珊瑚块");
        put(map, "minecraft:dead_horn_coral_block", "失活的鹿角珊瑚块");

        put(map, "minecraft:oak_sapling", "橡木树苗");
        put(map, "minecraft:spruce_sapling", "云杉树苗");
        put(map, "minecraft:birch_sapling", "白桦树苗");
        put(map, "minecraft:jungle_sapling", "丛林树苗");
        put(map, "minecraft:acacia_sapling", "金合欢树苗");
        put(map, "minecraft:dark_oak_sapling", "深色橡木树苗");
        put(map, "minecraft:mangrove_propagule", "红树胎生苗");
        put(map, "minecraft:cherry_sapling", "樱花树苗");
        put(map, "minecraft:pale_oak_sapling", "苍白橡木树苗");

        return Collections.unmodifiableMap(map);
    }

    private static void put(Map<String, List<String>> map, String id, String... names) {
        map.put(id, List.of(names));
    }
}