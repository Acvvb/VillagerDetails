package com.villagerdetails.rule.type;

import com.villagerdetails.command.c.impl.rule.*;
import com.villagerdetails.command.c.server.RegisterServer;
import com.villagerdetails.command.c.server.RegisterTypeServer;
import com.villagerdetails.command.c.server.StateSuggestionServer;
import com.villagerdetails.config.ReloadableConfig;
import com.villagerdetails.config.impl.section.item.*;
import com.villagerdetails.event.type.ListenerType;
import com.villagerdetails.lang.ServerTranslations;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static com.villagerdetails.cache.RuleCache.isEnabled;
import static com.villagerdetails.command.SwitchComponentType.*;
import static com.villagerdetails.rule.type.RuleCategoryType.*;

public enum RuleType implements RegisterTypeServer {

    // ==========================================
    // 设置 (MOD_SETTING)
    // ==========================================

    /**
     * 模组语言设置，控制界面和消息的服务端翻译语言。
     */
    SETTING_LANGUAGE("Language", "setting.language", "setting.language.desc", List.of(MOD_SETTING), new ArrayList<>(), null, () -> ServerLangConfig.INSTANCE, ServerLangConfig::preloadLanguageView, RuleConfigKind.NONE, "zh_cn", null),

    /**
     * /ec 命令的全局权限管理（管理除 /ec c 以外的所有权限）。
     */
    SETTING_EC_COMMAND_PERMISSION("RuleCommand", "setting.ec_command_permission", "setting.ec_command_permission.desc", List.of(MOD_SETTING), new ArrayList<>(), null, null, () -> SETTING_PERMISSIONS, RuleConfigKind.NONE, OWNER_STR, null),


    // ==========================================
    // 修复 (FIX)
    // ==========================================

    /**
     * 全局移动提速防回弹。开启后，玩家自身或骑乘载具（如乐魂、模组飞行器）移动过快时，
     * 绕过服务端的速度校验，防止被拉回和刷屏警告。
     */
    GLOBAL_SPEED_BYPASS("GlobalSpeedBypass", "setting.global_speed_bypass", "setting.global_speed_bypass.desc", List.of(FIX), new ArrayList<>(), null, null, () -> ON_OFF, RuleConfigKind.NONE, FALSE_STR, null),


    // ==========================================
    // 实体 (ENTITY)
    // ==========================================

    /**
     * 无挤压。指定实体不参与实体挤压、不受到挤压伤害。实体名单由 /ec c NoSqueeze 维护。
     */
    VILLAGER_NO_SQUEEZE("NoSqueeze", "setting.villager_no_squeeze", "setting.villager_no_squeeze.desc", List.of(ENTITY, VILLAGER), new ArrayList<>(), () -> NoSqueezeServerImpl.INSTANCE, () -> NoSqueezeConfig.INSTANCE, () -> ON_OFF, RuleConfigKind.LIST, FALSE_STR, null),

    /**
     * 禁止生成。阻止特定实体生成。实体名单由 /ec c MobNoSpawn 维护。
     */
    MOB_NO_SPAWN("MobNoSpawn", "rule.entity.no_spawn", "rule.entity.no_spawn.desc", List.of(ENTITY, COMMAND), new ArrayList<>(), () -> MobNoSpawnServerImpl.INSTANCE, () -> MobNoSpawnConfig.INSTANCE, () -> ON_OFF, RuleConfigKind.LIST, FALSE_STR, null),

    /**
     * 僵尸村民快速治愈。命名牌写 help 贴到僵尸村民，立刻走完治愈流程（保留职业/交易/声望）。
     */
    ZOMBIE_VILLAGER_HELP("ZombieVillagerHelp", "setting.zombie_villager_help", "setting.zombie_villager_help.desc", List.of(VILLAGER, MONSTER), new ArrayList<>(), null, null, () -> ON_OFF, RuleConfigKind.NONE, FALSE_STR, null),

    /**
     * 猪灵金块交易。允许猪灵收金块，1个金块产出原版9次交易的物品。
     */
    PIGLIN_GOLD_BLOCK_MULTIPLY("PiglinGoldBlockMultiply", "setting.piglin_gold_block_multiply", "setting.piglin_gold_block_multiply.desc", List.of(ENTITY), new ArrayList<>(), null, null, () -> ON_OFF, RuleConfigKind.NONE, FALSE_STR, null),

    /**
     * 猪灵交易延迟。调整猪灵交易前欣赏金锭的等待时间。
     */
    PIGLIN_BARTER_DELAY("PiglinBarterDelay", "setting.piglin_barter_delay", "setting.piglin_barter_delay.desc", List.of(ENTITY), new ArrayList<>(), null, null, () -> List.of("3", "6", "12"), RuleConfigKind.NONE, "6", null),

    /**
     * 猪灵金块交易惩罚。如果给金块交易失败时的惩罚机制（如消耗数量）。
     */
    PIGLIN_GOLD_BLOCK_MULTIPLY_PUNISHMENT("PiglinGoldBlockMultiplyPunishment", "setting.piglin_gold_block_multiply_punishment", "setting.piglin_gold_block_multiply_punishment.desc", List.of(ENTITY), new ArrayList<>(), null, null, () -> List.of("1", "3", "9"), RuleConfigKind.NONE, "9", null),

    /**
     * 乐魂飞行速度。调整玩家骑乘乐魂时的飞行速度（没人骑时恢复原版）。
     */
    HAPPY_GHAST_SPEED("HappyGhastSpeed", "setting.happy_ghast_speed", "setting.happy_ghast_speed.desc", List.of(FIX), new ArrayList<>(), null, null, () -> List.of("0.05", "0.1", "0.2", "0.3", "0.5", "1.0"), RuleConfigKind.NONE, "0.05", null),


    // ==========================================
    // 怪物 (MONSTER)
    // ==========================================

    /**
     * 过门失智。怪物穿过下界传送门后失去 AI，变成无脑状态。
     */
    NO_BRAIN_ON_PORTAL("NoBrainOnPortal", "setting.no_brain_on_portal", "setting.no_brain_on_portal.desc", List.of(MONSTER), new ArrayList<>(), null, null, () -> ON_OFF, RuleConfigKind.NONE, FALSE_STR, null),


    // ==========================================
    // 植物 (PLANT)
    // ==========================================

    /**
     * 骨粉催熟花朵相关功能。
     */
    VILLAGER_BONE_MEAL_FLOWER("VillagerBoneMealFlower", "villagerdetails.rule.villagerbonemealflower", "villagerdetails.rule.villagerbonemealflower.desc", List.of(PLANT), new ArrayList<>(), null, null, () -> ON_OFF, RuleConfigKind.NONE, FALSE_STR, null),


    // ==========================================
    // 动物 (ANIMAL)
    // ==========================================

    /**
     * 羊无限剪毛。剪羊毛后羊立即恢复有毛状态，可反复剪。
     */
    INFINITE_SHEAR("InfiniteShear", "setting.infinite_shear", "setting.infinite_shear.desc", List.of(ANIMAL), new ArrayList<>(), null, null, () -> ON_OFF, RuleConfigKind.NONE, FALSE_STR, null),


    // ==========================================
    // 村民 (VILLAGER)
    // ==========================================

    /**
     * 村民床编辑器。使用命名为 bed 的拴绳蹲下右键选择村民和床修改绑定床。
     */
    VILLAGER_BED_RESET("VillagerBedBinding", "setting.villager_bed_reset", "setting.villager_bed_reset.desc", List.of(VILLAGER), List.of(ListenerType.ENTITY_BLOCK_SELECTION), null, null, () -> PERMISSIONS, RuleConfigKind.NONE, FALSE_STR, null),

    /**
     * 村民工作方块编辑器。使用命名为 work 的拴绳修改村民绑定的工作方块。
     */
    VILLAGER_WORK_BLOCK_RESET("VillagerWorkBlockBinding", "setting.villager_work_block_reset", "setting.villager_work_block_reset.desc", List.of(VILLAGER), List.of(ListenerType.ENTITY_BLOCK_SELECTION), null, null, () -> PERMISSIONS, RuleConfigKind.NONE, FALSE_STR, null),

    /**
     * 批量村民床编辑器。执行指令将范围内村民绑定到最近距离的床。
     */
    VILLAGER_BATCH_BED_RESET("VillagerBatchBedBinding", "setting.villager_batch_bed_reset", "setting.villager_batch_bed_reset.desc", List.of(VILLAGER, COMMAND), List.of(ListenerType.BLOCK_RANGE_SELECTION), () -> VillagerBatchBedBindingServerImpl.INSTANCE, null, () -> PERMISSIONS, RuleConfigKind.NONE, FALSE_STR, null),

    /**
     * 自动刷新交易。用命名牌写目标附魔，右键图书管理员刷出满级附魔交易。
     */
    VILLAGER_AUTO_TRADER("VillagerAutoTrader", "setting.villager_auto_trader", "setting.villager_auto_trader.desc", List.of(VILLAGER), new ArrayList<>(), null, null, () -> ON_OFF, RuleConfigKind.NONE, FALSE_STR, null),

    /**
     * 神装工具刷新。使用 331a/331b 命名牌刷出三附魔钻石工具。
     */
    VILLAGER_GOD_TOOLS("VillagerGodTools", "setting.villager_god_tools", "setting.villager_god_tools.desc", List.of(VILLAGER), new ArrayList<>(), null, null, () -> ON_OFF, RuleConfigKind.NONE, FALSE_STR, null),

    /**
     * 陶瓦刷新。使用陶瓦名称命名牌刷出对应陶瓦和带釉陶瓦。
     */
    VILLAGER_TERRACOTTA_TRADER("VillagerTerracottaTrader", "setting.villager_terracotta_trader", "setting.villager_terracotta_trader.desc", List.of(VILLAGER), new ArrayList<>(), null, null, () -> ON_OFF, RuleConfigKind.NONE, FALSE_STR, null),

    /**
     * 自动锁定村民交易。使用自动刷新规则刷出的附魔书自动锁定该村民交易。
     */
    VILLAGER_AUTO_LOCK_HIT_TRADER("VillagerAutoLockHitTrader", "setting.villager_auto_lock_hit_trader", "setting.villager_auto_lock_hit_trader.desc", List.of(VILLAGER), new ArrayList<>(), null, null, () -> ON_OFF, RuleConfigKind.NONE, FALSE_STR, null),

    /**
     * 无感村民交易刷新。使用自动刷新交易规则后自动移除命名名称。
     */
    VILLAGER_SILENT_AUTO_REROLL_TRADER("VillagerSilentTrader", "setting.villager_silent_auto_reroll_trader", "setting.villager_silent_auto_reroll_trader.desc", List.of(VILLAGER), new ArrayList<>(), null, null, () -> ON_OFF, RuleConfigKind.NONE, FALSE_STR, null),

    /**
     * 每 tick 刷新交易次数。调整自动刷新交易的单 tick 刷新次数。
     */
    VILLAGER_ONE_TICK_TRADER_COUNT("VillagerOneTickTraderCount", "setting.villager_one_tick_trader_count", "setting.villager_one_tick_trader_count.desc", List.of(VILLAGER), new ArrayList<>(), null, null, () -> List.of("50", "100", "500", "1000"), RuleConfigKind.NONE, "500", null),

    /**
     * 勤劳的村民。调整村民每次补货的数量（默认两倍补货）。
     */
    VILLAGER_HARD_WORKING("VillagerHardWorking", "setting.villager_hard_working", "setting.villager_hard_working.desc", List.of(VILLAGER), new ArrayList<>(), null, null, () -> List.of("1", "2", "5"), RuleConfigKind.NONE, "1", null),

    /**
     * 无限交易。村民的交易永不耗尽。
     */
    VILLAGER_INFINITE_TRADE("VillagerInfiniteTrade", "setting.villager_infinite_trade", "setting.villager_infinite_trade.desc", List.of(VILLAGER), new ArrayList<>(), null, null, () -> ON_OFF, RuleConfigKind.NONE, FALSE_STR, null),

    /**
     * 村民移动控制器。使用命名为 move 的拴绳控制村民移动到目的地。
     */
    VILLAGER_MOVE_CONTROLLER("VillagerMoveController", "setting.villager_move_controller", "setting.villager_move_controller.desc", List.of(VILLAGER), new ArrayList<>(), null, null, () -> PERMISSIONS, RuleConfigKind.NONE, FALSE_STR, null),

    /**
     * 村民可兑换钻石。把工具匠的钻石换绿宝石改成绿宝石换钻石。
     */
    VILLAGER_TOOLSMITH_EXCHANGE("VillagerToolsmithExchange", "setting.villager_toolsmith_exchange", "setting.villager_toolsmith_exchange.desc", List.of(VILLAGER), new ArrayList<>(), null, null, () -> ON_OFF, RuleConfigKind.NONE, FALSE_STR, null),

    /**
     * 绿宝石块自动拆解。允许使用绿宝石块支付村民交易，自动拆成绿宝石。
     */
    VILLAGER_EMERALD_DECOMPOSE("VillagerEmeraldDecompose", "setting.villager_emerald_decompose", "setting.villager_emerald_decompose.desc", List.of(VILLAGER), new ArrayList<>(), null, null, () -> ON_OFF, RuleConfigKind.NONE, FALSE_STR, null),

    /**
     * 书架自动拆解。允许使用书架支付村民交易，自动拆成书。
     */
    VILLAGER_BOOKSHELF_DECOMPOSE("VillagerBookshelfDecompose", "setting.villager_bookshelf_decompose", "setting.villager_bookshelf_decompose.desc", List.of(VILLAGER), new ArrayList<>(), null, null, () -> ON_OFF, RuleConfigKind.NONE, FALSE_STR, null),


    // ==========================================
    // 方块 (BLOCK)
    // ==========================================

    /**
     * 方块挖掘抗性。通过指令配置特定方块的挖掘抗性（数值）。
     */
    BLOCK_MINING_RESISTANCE("BlockMiningResistance", "rule.block.mining_resistance", "rule.block.mining_resistance.desc", List.of(BLOCK, COMMAND), new ArrayList<>(), () -> BlockMiningResistanceServerImpl.INSTANCE, () -> BlockMiningResistanceConfig.INSTANCE, () -> ON_OFF, RuleConfigKind.LIST, FALSE_STR, null),

    /**
     * 方块可采集。通过指令配置特定方块可被采集（哪怕原版不可采集）。
     */
    BLOCK_COLLECTABLE("BlockCollectable", "rule.block.collectable", "rule.block.collectable.desc", List.of(BLOCK, COMMAND), new ArrayList<>(), () -> BlockCollectableServerImpl.INSTANCE, () -> BlockCollectableConfig.INSTANCE, () -> ON_OFF, RuleConfigKind.LIST, FALSE_STR, null),

    /**
     * 方块爆炸抗性。通过指令配置特定方块的爆炸抗性（数值）。
     */
    BLOCK_EXPLOSION_RESISTANCE("BlockExplosionResistance", "rule.block.explosion_resistance", "rule.block.explosion_resistance.desc", List.of(BLOCK, COMMAND), new ArrayList<>(), () -> BlockExplosionResistanceServerImpl.INSTANCE, () -> BlockExplosionResistanceConfig.INSTANCE, () -> ON_OFF, RuleConfigKind.LIST, FALSE_STR, null),

    /**
     * 方块红石强充能。被配置的方块作为红石导体，传递红石信号。
     */
    BLOCK_STRONG_POWER("BlockStrongPower", "rule.block.strong_power", "rule.block.strong_power.desc", List.of(BLOCK, COMMAND), new ArrayList<>(), () -> BlockStrongPowerServerImpl.INSTANCE, () -> BlockStrongPowerConfig.INSTANCE, () -> ON_OFF, RuleConfigKind.LIST, FALSE_STR, null),

    /**
     * TNT不破坏掉落物。TNT爆炸时不破坏掉落的物品。
     */
    TNT_NO_DESTROY_DROPS("TntNoDestroyDrops", "setting.tnt_no_destroy_drops", "setting.tnt_no_destroy_drops.desc", List.of(BLOCK), new ArrayList<>(), null, null, () -> ON_OFF, RuleConfigKind.NONE, FALSE_STR, null),

    /**
     * 箱子合成。
     */
    BOX_CRAFTING("BoxCrafting", "setting.box_crafting", "setting.box_crafting.desc", List.of(BLOCK), new ArrayList<>(), null, null, () -> ON_OFF, RuleConfigKind.NONE, FALSE_STR, null),

    /**
     * 传送门绑定。将特定范围内的方块绑定到传送门。
     */
    PORTAL_BINDING("PortalBinding", "setting.PortalBinding", "setting.PortalBinding.desc", List.of(BLOCK), List.of(ListenerType.BLOCK_RANGE_SELECTION), () -> PortalBindingServerImpl.INSTANCE, () -> PortalBindingConfig.INSTANCE, () -> ON_OFF, RuleConfigKind.NONE, FALSE_STR, null),

    /**
     * 传送门绑定增强版。
     */
    PORTAL_BINDING_ENHANCED("PortalBindingEnhanced", "setting.PortalBindingEnhanced", "setting.PortalBindingEnhanced.desc", List.of(BLOCK), List.of(ListenerType.BLOCK_RANGE_SELECTION), () -> PortalBindingServerImpl.INSTANCE, null, () -> ON_OFF, RuleConfigKind.NONE, FALSE_STR, null),

    /**
     * 末地门重力方块。配置的方块如果在末地传送门 5x5x5 范围内且悬空，会像沙子一样掉落。
     */
    ENDER_PORTAL_GRAVITY_BLOCK("EndPortalGravityBlock", "rule.block.end_portal_gravity", "rule.block.end_portal_gravity.desc", List.of(BLOCK, COMMAND), new ArrayList<>(), () -> EndPortalGravityBlockServerImpl.INSTANCE, () -> EndPortalGravityBlockConfig.INSTANCE, () -> ON_OFF, RuleConfigKind.LIST, FALSE_STR, null),

    /**
     * 熔炉脏东西高亮。熔炉的输入槽里有烧不了的东西、或者燃料槽里不是有效燃料时，
     * 在客户端把这个熔炉框成高亮（服务端判定，客户端只负责画）。
     * 扫描半径 / 间隔在 {@code villagerdetails/config.json} 的 {@code FurnaceDirtyHighlight} 节里配。
     */
    FURNACE_DIRTY_HIGHLIGHT("FurnaceDirtyHighlight", "rule.block.furnace_dirty_highlight", "rule.block.furnace_dirty_highlight.desc", List.of(BLOCK), new ArrayList<>(), null, () -> FurnaceDirtyHighlightConfig.INSTANCE, () -> ON_OFF, RuleConfigKind.NONE, FALSE_STR, null),

    ;


    /**
     * 注册名：规则的唯一标识符。
     */
    private final String registerName;

    /**
     * 显示名的翻译键。
     */
    private final String displayName;

    /**
     * 描述信息的翻译键。
     */
    private final String displayInfo;

    /**
     * 规则所属的分类列表。
     */
    private final List<RuleCategoryType> category;

    /**
     * 事件监听类型列表。
     */
    private final List<ListenerType> listenerType;

    /**
     * 命令处理对象的延迟加载 Supplier。
     */
    private final Supplier<RegisterServer> commandObject;

    /**
     * 绑定的可重载配置实例的延迟加载 Supplier。
     */
    private final Supplier<ReloadableConfig> reloadableConfig;

    /**
     * 快捷开关列表的延迟加载 Supplier。
     */
    private final Supplier<List<String>> quickSwitches;

    /**
     * 配置类型枚举。
     */
    private final RuleConfigKind configKind;

    /**
     * 默认状态值。
     */
    private final String state;

    /**
     * 状态参数的服务端补全器的延迟加载 Supplier。
     */
    private final Supplier<StateSuggestionServer> stateSuggestionServer;


    RuleType(String registerName, String displayName, String displayInfo, List<RuleCategoryType> category, List<ListenerType> listenerType, Supplier<RegisterServer> commandClass, Supplier<ReloadableConfig> reloadableConfig, Supplier<List<String>> quickSwitches, RuleConfigKind configKind, String state, Supplier<StateSuggestionServer> stateSuggestionServer) {
        this.listenerType = listenerType;
        this.reloadableConfig = reloadableConfig;
        this.quickSwitches = quickSwitches;
        this.registerName = registerName;
        this.displayName = displayName;
        this.displayInfo = displayInfo;
        this.category = category;
        this.commandObject = commandClass;
        this.configKind = configKind == null ? RuleConfigKind.NONE : configKind;
        this.state = state;
        this.stateSuggestionServer = stateSuggestionServer;
    }

    public String getRegisterName() {
        return registerName;
    }

    public String getDisplayName() {
        return ServerTranslations.resolve(Component.translatable(displayName));
    }

    public String getDisplayInfo() {
        return ServerTranslations.resolve(Component.translatable(displayInfo));
    }

    public String getDisplayNameKey() {
        return displayName;
    }

    public String getDisplayInfoKey() {
        return displayInfo;
    }

    public List<RuleCategoryType> getCategory() {
        return category;
    }

    public List<ListenerType> getListenerType() {
        return listenerType;
    }

    @Override
    public RegisterServer getCommandObject() {
        return commandObject != null ? commandObject.get() : null;
    }

    public ReloadableConfig getReloadableConfig() {
        return reloadableConfig != null ? reloadableConfig.get() : null;
    }

    @Override
    public Predicate<CommandSourceStack> requirement() {
        return src -> isEnabled(src, this);
    }

    public List<String> getQuickSwitches() {
        return quickSwitches != null ? quickSwitches.get() : List.of();
    }

    public RuleConfigKind getConfigKind() {
        return configKind;
    }

    public String getState() {
        return state;
    }

    public StateSuggestionServer getStateSuggestionServer() {
        return stateSuggestionServer != null ? stateSuggestionServer.get() : null;
    }

    public static RuleType getRuleTypeByRegisterName(String registerName) {
        for (RuleType ruleType : RuleType.values()) {
            if (ruleType.registerName.equalsIgnoreCase(registerName)) {
                return ruleType;
            }
        }
        return null;
    }

    public static List<RuleType> getRuleTypeListByRuleCategoryType(RuleCategoryType ruleCategoryType) {
        List<RuleType> ruleTypeSet = new ArrayList<>();
        if (ruleCategoryType == null) return ruleTypeSet;
        for (RuleType ruleType : RuleType.values()) {
            for (RuleCategoryType type : ruleType.getCategory()) {
                if (type.equals(ruleCategoryType)) {
                    ruleTypeSet.add(ruleType);
                    break;
                }
            }
        }
        return ruleTypeSet;
    }

    /**
     * 获取所有规则绑定的、需要统一重载的配置实例。
     * 直接遍历，无需缓存（此方法仅在服务器启动或重载配置时调用）。
     */
    public static List<ReloadableConfig> getReloadableConfigs() {
        return Arrays.stream(values())
                .map(RuleType::getReloadableConfig)
                .filter(Objects::nonNull).distinct().toList();
    }
}