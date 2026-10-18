package com.villagerdetails.rule.type;

import com.villagerdetails.command.c.impl.rule.*;
import com.villagerdetails.command.c.server.RegisterServer;
import com.villagerdetails.command.c.server.RegisterTypeServer;
import com.villagerdetails.command.c.server.StateSuggestionServer;
import com.villagerdetails.config.impl.section.item.ServerLangConfig;
import com.villagerdetails.event.type.ListenerType;
import com.villagerdetails.lang.ServerTranslations;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static com.villagerdetails.cache.RuleCache.isEnabled;
import static com.villagerdetails.command.SwitchComponentType.*;
import static com.villagerdetails.rule.type.RuleCategoryType.*;

public enum RuleType implements RegisterTypeServer {


    //设置
    // 语言默认 zh_cn：和 ServerLangConfig 的 defaultLang 保持一致（语言以这条规则为准，配置里的 default 只是兜底）
    SETTING_LANGUAGE("Language", "setting.language", "setting.language.desc", List.of(MOD_SETTING), new ArrayList<>(), null, ServerLangConfig.preloadLanguageView(), RuleConfigKind.NONE, "zh_cn", null),

    SETTING_EC_COMMAND_PERMISSION("RuleCommand", "setting.ec_command_permission", "setting.ec_command_permission.desc", List.of(MOD_SETTING), new ArrayList<>(), null, SETTING_PERMISSIONS, RuleConfigKind.NONE, OWNER_STR, null),


    // 实体
    // 无挤压：规则状态就是普通的「是 / 否」；具体保护哪些实体由 /ec c NoSqueeze <实体id> true|false
    // 存进 NoSqueezeConfig（以前是把这个实体 id 列表塞在状态里，现在状态只管开关）
    VILLAGER_NO_SQUEEZE("NoSqueeze", "setting.villager_no_squeeze", "setting.villager_no_squeeze.desc", List.of(ENTITY, VILLAGER), new ArrayList<>(), NoSqueezeServerImpl.INSTANCE, ON_OFF, RuleConfigKind.LIST, FALSE_STR, null),

    // 禁止生成：和「无挤压」一样是实体 id 纯名单（列表模式），名单由 /ec c MobNoSpawn <实体id> true|false 维护
    MOB_NO_SPAWN("MobNoSpawn", "rule.entity.no_spawn", "rule.entity.no_spawn.desc", List.of(ENTITY, COMMAND), new ArrayList<>(), MobNoSpawnServerImpl.INSTANCE, ON_OFF, RuleConfigKind.LIST, FALSE_STR, null),

    // 命名牌写 help 贴到僵尸村民 → 立刻走完原版治愈流程（职业 / 交易 / 声望保留）
    ZOMBIE_VILLAGER_HELP("ZombieVillagerHelp", "setting.zombie_villager_help", "setting.zombie_villager_help.desc", List.of(VILLAGER, MONSTER), new ArrayList<>(), null, ON_OFF, RuleConfigKind.NONE, FALSE_STR, null),

    PIGLIN_GOLD_BLOCK_MULTIPLY("PiglinGoldBlockMultiply", "setting.piglin_gold_block_multiply", "setting.piglin_gold_block_multiply.desc", List.of(ENTITY), new ArrayList<>(), null, ON_OFF, RuleConfigKind.NONE, FALSE_STR, null),

    PIGLIN_BARTER_DELAY("PiglinBarterDelay", "setting.piglin_barter_delay", "setting.piglin_barter_delay.desc", List.of(ENTITY), new ArrayList<>(), null, List.of("3", "6", "12"), RuleConfigKind.NONE, "6", null),

    PIGLIN_GOLD_BLOCK_MULTIPLY_PUNISHMENT("PiglinGoldBlockMultiplyPunishment", "setting.piglin_gold_block_multiply_punishment", "setting.piglin_gold_block_multiply_punishment.desc", List.of(ENTITY), new ArrayList<>(), null, List.of("1", "3", "9"), RuleConfigKind.NONE, "9", null),


    //怪物
    NO_BRAIN_ON_PORTAL("NoBrainOnPortal", "setting.no_brain_on_portal", "setting.no_brain_on_portal.desc", List.of(MONSTER), new ArrayList<>(), null, ON_OFF, RuleConfigKind.NONE, FALSE_STR, null),


    //植物
    VILLAGER_BONE_MEAL_FLOWER("VillagerBoneMealFlower", "villagerdetails.rule.villagerbonemealflower", "villagerdetails.rule.villagerbonemealflower.desc", List.of(PLANT), new ArrayList<>(), null, ON_OFF, RuleConfigKind.NONE, FALSE_STR, null),


    //动物
    INFINITE_SHEAR("InfiniteShear", "setting.infinite_shear", "setting.infinite_shear.desc", List.of(ANIMAL), new ArrayList<>(), null, ON_OFF, RuleConfigKind.NONE, FALSE_STR, null),


    // 村民
    VILLAGER_BED_RESET("VillagerBedBinding", "setting.villager_bed_reset", "setting.villager_bed_reset.desc", List.of(VILLAGER), List.of(ListenerType.ENTITY_BLOCK_SELECTION), null, PERMISSIONS, RuleConfigKind.NONE, FALSE_STR, null),

    VILLAGER_WORK_BLOCK_RESET("VillagerWorkBlockBinding", "setting.villager_work_block_reset", "setting.villager_work_block_reset.desc", List.of(VILLAGER), List.of(ListenerType.ENTITY_BLOCK_SELECTION), null, PERMISSIONS, RuleConfigKind.NONE, FALSE_STR, null),

    VILLAGER_BATCH_BED_RESET("VillagerBatchBedBinding", "setting.villager_batch_bed_reset", "setting.villager_batch_bed_reset.desc", List.of(VILLAGER, COMMAND), List.of(ListenerType.BLOCK_RANGE_SELECTION), VillagerBatchBedBindingServerImpl.INSTANCE, PERMISSIONS, RuleConfigKind.NONE, FALSE_STR, null),

    VILLAGER_AUTO_TRADER("VillagerAutoTrader", "setting.villager_auto_trader", "setting.villager_auto_trader.desc", List.of(VILLAGER), new ArrayList<>(), null, ON_OFF, RuleConfigKind.NONE, FALSE_STR, null),

    VILLAGER_GOD_TOOLS("VillagerGodTools", "setting.villager_god_tools", "setting.villager_god_tools.desc", List.of(VILLAGER), new ArrayList<>(), null, ON_OFF, RuleConfigKind.NONE, FALSE_STR, null),

    VILLAGER_TERRACOTTA_TRADER("VillagerTerracottaTrader", "setting.villager_terracotta_trader", "setting.villager_terracotta_trader.desc", List.of(VILLAGER), new ArrayList<>(), null, ON_OFF, RuleConfigKind.NONE, FALSE_STR, null),

    VILLAGER_AUTO_LOCK_HIT_TRADER("VillagerAutoLockHitTrader", "setting.villager_auto_lock_hit_trader", "setting.villager_auto_lock_hit_trader.desc", List.of(VILLAGER), new ArrayList<>(), null, ON_OFF, RuleConfigKind.NONE, FALSE_STR, null),

    VILLAGER_SILENT_AUTO_REROLL_TRADER("VillagerSilentTrader", "setting.villager_silent_auto_reroll_trader", "setting.villager_silent_auto_reroll_trader.desc", List.of(VILLAGER), new ArrayList<>(), null, ON_OFF, RuleConfigKind.NONE, FALSE_STR, null),

    VILLAGER_ONE_TICK_TRADER_COUNT("VillagerOneTickTraderCount", "setting.villager_one_tick_trader_count", "setting.villager_one_tick_trader_count.desc", List.of(VILLAGER), new ArrayList<>(), null, List.of("50", "100", "500", "1000"), RuleConfigKind.NONE, "500", null),

    VILLAGER_HARD_WORKING("VillagerHardWorking", "setting.villager_hard_working", "setting.villager_hard_working.desc", List.of(VILLAGER), new ArrayList<>(), null, List.of("1", "2", "5"), RuleConfigKind.NONE, "1", null),

    VILLAGER_INFINITE_TRADE("VillagerInfiniteTrade", "setting.villager_infinite_trade", "setting.villager_infinite_trade.desc", List.of(VILLAGER), new ArrayList<>(), null, ON_OFF, RuleConfigKind.NONE, FALSE_STR, null),

    VILLAGER_MOVE_CONTROLLER("VillagerMoveController", "setting.villager_move_controller", "setting.villager_move_controller.desc", List.of(VILLAGER), new ArrayList<>(), null, PERMISSIONS, RuleConfigKind.NONE, FALSE_STR, null),

    VILLAGER_TOOLSMITH_EXCHANGE("VillagerToolsmithExchange", "setting.villager_toolsmith_exchange", "setting.villager_toolsmith_exchange.desc", List.of(VILLAGER), new ArrayList<>(), null, ON_OFF, RuleConfigKind.NONE, FALSE_STR, null),

    VILLAGER_EMERALD_DECOMPOSE("VillagerEmeraldDecompose", "setting.villager_emerald_decompose", "setting.villager_emerald_decompose.desc", List.of(VILLAGER), new ArrayList<>(), null, ON_OFF, RuleConfigKind.NONE, FALSE_STR, null),

    VILLAGER_BOOKSHELF_DECOMPOSE("VillagerBookshelfDecompose", "setting.villager_bookshelf_decompose", "setting.villager_bookshelf_decompose.desc", List.of(VILLAGER), new ArrayList<>(), null, ON_OFF, RuleConfigKind.NONE, FALSE_STR, null),


    // 方块
    BLOCK_MINING_RESISTANCE("BlockMiningResistance", "rule.block.mining_resistance", "rule.block.mining_resistance.desc", List.of(BLOCK, COMMAND), new ArrayList<>(), BlockMiningResistanceServerImpl.INSTANCE, ON_OFF, RuleConfigKind.LIST, FALSE_STR, null),

    BLOCK_COLLECTABLE("BlockCollectable", "rule.block.collectable", "rule.block.collectable.desc", List.of(BLOCK, COMMAND), new ArrayList<>(), BlockCollectableServerImpl.INSTANCE, ON_OFF, RuleConfigKind.LIST, FALSE_STR, null),

    BLOCK_EXPLOSION_RESISTANCE("BlockExplosionResistance", "rule.block.explosion_resistance", "rule.block.explosion_resistance.desc", List.of(BLOCK, COMMAND), new ArrayList<>(), BlockExplosionResistanceServerImpl.INSTANCE, ON_OFF, RuleConfigKind.LIST, FALSE_STR, null),

    BLOCK_STRONG_POWER("BlockStrongPower", "rule.block.strong_power", "rule.block.strong_power.desc", List.of(BLOCK, COMMAND), new ArrayList<>(), BlockStrongPowerServerImpl.INSTANCE, ON_OFF, RuleConfigKind.LIST, FALSE_STR, null),

    TNT_NO_DESTROY_DROPS("TntNoDestroyDrops", "setting.tnt_no_destroy_drops", "setting.tnt_no_destroy_drops.desc", List.of(BLOCK), new ArrayList<>(), null, ON_OFF, RuleConfigKind.NONE, FALSE_STR, null),

    BOX_CRAFTING("BoxCrafting", "setting.box_crafting", "setting.box_crafting.desc", List.of(BLOCK), new ArrayList<>(), null, ON_OFF, RuleConfigKind.NONE, FALSE_STR, null),

    PORTAL_BINDING("PortalBinding", "setting.PortalBinding", "setting.PortalBinding.desc", List.of(BLOCK), List.of(ListenerType.BLOCK_RANGE_SELECTION), PortalBindingServerImpl.INSTANCE, ON_OFF, RuleConfigKind.NONE, FALSE_STR, null),

    PORTAL_BINDING_ENHANCED("PortalBindingEnhanced", "setting.PortalBindingEnhanced", "setting.PortalBindingEnhanced.desc", List.of(BLOCK), List.of(ListenerType.BLOCK_RANGE_SELECTION), PortalBindingServerImpl.INSTANCE, ON_OFF, RuleConfigKind.NONE, FALSE_STR, null),

    ;


    private final String registerName;
    private final String displayName;
    private final String displayInfo;
    private final List<RuleCategoryType> category;
    private final List<ListenerType> listenerType;
    private final RegisterServer commandObject;
    private final List<String> quickSwitches;

    /**
     * 这条规则是不是「逐条配置类」—— 决定配置内容要不要同步给客户端。
     *
     * <p>只在 {@link RuleConfigKind#LIST} 时才有内容可同步；这类规则在
     * {@code RuleDataRegistry} 里一定登记了一条 {@code Entry}（那边有启动自检兜底）。
     */
    private final RuleConfigKind configKind;

    private final String state;
    private final StateSuggestionServer stateSuggestionServer;

    /**
     * 唯一的构造器：
     * 普通规则传 {@code RuleConfigKind.NONE} 与 {@code null}；
     * 逐条配置类规则传 {@code RuleConfigKind.LIST}，{@code stateSuggestionServer} 视需要传 {@code null} 或实现。
     */
    RuleType(String registerName, String displayName, String displayInfo, List<RuleCategoryType> category, List<ListenerType> listenerType, RegisterServer commandClass, List<String> quickSwitches, RuleConfigKind configKind, String state, StateSuggestionServer stateSuggestionServer) {
        this.listenerType = listenerType;
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

    /**
     * 显示名的翻译键（客户端界面用，保证和命令显示的是同一条文案）
     */
    public String getDisplayNameKey() {
        return displayName;
    }

    /**
     * 显示描述的翻译键
     */
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
        return commandObject;
    }

    @Override
    public Predicate<CommandSourceStack> requirement() {
        return src -> isEnabled(src, this);
    }

    public List<String> getQuickSwitches() {
        return quickSwitches;
    }

    /**
     * 这条规则属于哪种配置形态（{@link RuleConfigKind#NONE} = 不是逐条配置类）
     */
    public RuleConfigKind getConfigKind() {
        return configKind;
    }

    public String getState() {
        return state;
    }

    public StateSuggestionServer getStateSuggestionServer() {
        return stateSuggestionServer;
    }

    /**
     * 根据 registerName 查找对应的 RuleType（忽略大小写）
     */
    public static RuleType getRuleTypeByRegisterName(String registerName) {
        for (RuleType ruleType : RuleType.values()) {
            if (ruleType.registerName.equalsIgnoreCase(registerName)) {
                return ruleType;
            }
        }
        return null;
    }

    /**
     * 根据 分类 查找对应的 分类内所有规则
     */
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

}