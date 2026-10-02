package com.villagerdetails.rule.type;

import com.villagerdetails.command.c.impl.rule.BlockCollectableServerImpl;
import com.villagerdetails.command.c.impl.rule.BlockExplosionResistanceServerImpl;
import com.villagerdetails.command.c.impl.rule.BlockMiningResistanceServerImpl;
import com.villagerdetails.command.c.impl.rule.VillagerBatchBedBindingServerImpl;
import com.villagerdetails.command.c.impl.suggestion.EntityIdSuggestionServer;
import com.villagerdetails.command.c.server.RegisterServer;
import com.villagerdetails.command.c.server.RegisterTypeServer;
import com.villagerdetails.command.c.server.StateSuggestionServer;
import com.villagerdetails.config.impl.ServerLangConfig;
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
    SETTING_LANGUAGE("Language", "setting.language", "setting.language.desc", List.of(MOD_SETTING), new ArrayList<>(), null, false, ServerLangConfig.getPreloadLanguages(), "en_us"),

    SETTING_EC_COMMAND_PERMISSION("RuleCommand", "setting.ec_command_permission", "setting.ec_command_permission.desc", List.of(MOD_SETTING), new ArrayList<>(), null, false, SETTING_PERMISSIONS, OWNER_STR),


    // 实体
    VILLAGER_NO_SQUEEZE("NoSqueeze", "setting.villager_no_squeeze", "setting.villager_no_squeeze.desc", List.of(ENTITY, VILLAGER), new ArrayList<>(), null, true, List.of(NONE, "minecraft:villager", "minecraft:piglin"), NONE, EntityIdSuggestionServer.INSTANCE),

    PIGLIN_GOLD_BLOCK_MULTIPLY("PiglinGoldBlockMultiply", "setting.piglin_gold_block_multiply", "setting.piglin_gold_block_multiply.desc", List.of(ENTITY), new ArrayList<>(), null, false, ON_OFF, FALSE_STR),

    PIGLIN_BARTER_DELAY("PiglinBarterDelay", "setting.piglin_barter_delay", "setting.piglin_barter_delay.desc", List.of(ENTITY), new ArrayList<>(), null, false, List.of("3", "6", "12"), "6"),

    PIGLIN_GOLD_BLOCK_MULTIPLY_PUNISHMENT("PiglinGoldBlockMultiplyPunishment", "setting.piglin_gold_block_multiply_punishment", "setting.piglin_gold_block_multiply_punishment.desc", List.of(ENTITY), new ArrayList<>(), null, false, List.of("1", "3", "9"), "9"),


    //怪物
    NO_BRAIN_ON_PORTAL("NoBrainOnPortal", "setting.no_brain_on_portal", "setting.no_brain_on_portal.desc", List.of(MONSTER), new ArrayList<>(), null, false, ON_OFF, FALSE_STR),


    //植物
    VILLAGER_BONE_MEAL_FLOWER("VillagerBoneMealFlower", "villagerdetails.rule.villagerbonemealflower", "villagerdetails.rule.villagerbonemealflower.desc", List.of(PLANT), new ArrayList<>(), null, false, ON_OFF, FALSE_STR),


    //动物
    INFINITE_SHEAR("InfiniteShear", "setting.infinite_shear", "setting.infinite_shear.desc", List.of(ANIMAL), new ArrayList<>(), null, false, ON_OFF, FALSE_STR),


    // 村民
    VILLAGER_BED_RESET("VillagerBedBinding", "setting.villager_bed_reset", "setting.villager_bed_reset.desc", List.of(VILLAGER), List.of(ListenerType.ENTITY_BLOCK_SELECTION), null, false, PERMISSIONS, FALSE_STR),

    VILLAGER_WORK_BLOCK_RESET("VillagerWorkBlockBinding", "setting.villager_work_block_reset", "setting.villager_work_block_reset.desc", List.of(VILLAGER), List.of(ListenerType.ENTITY_BLOCK_SELECTION), null, false, PERMISSIONS, FALSE_STR),

    VILLAGER_BATCH_BED_RESET("VillagerBatchBedBinding", "setting.villager_batch_bed_reset", "setting.villager_batch_bed_reset.desc", List.of(VILLAGER, COMMAND), List.of(ListenerType.BLOCK_RANGE_SELECTION), VillagerBatchBedBindingServerImpl.INSTANCE, false, PERMISSIONS, FALSE_STR),

    VILLAGER_AUTO_TRADER("VillagerAutoTrader", "setting.villager_auto_trader", "setting.villager_auto_trader.desc", List.of(VILLAGER), new ArrayList<>(), null, false, ON_OFF, FALSE_STR),

    VILLAGER_GOD_TOOLS("VillagerGodTools", "setting.villager_god_tools", "setting.villager_god_tools.desc", List.of(VILLAGER), new ArrayList<>(), null, false, ON_OFF, FALSE_STR),

    VILLAGER_TERRACOTTA_TRADER("VillagerTerracottaTrader", "setting.villager_terracotta_trader", "setting.villager_terracotta_trader.desc", List.of(VILLAGER), new ArrayList<>(), null, false, ON_OFF, FALSE_STR),

    VILLAGER_AUTO_LOCK_HIT_TRADER("VillagerAutoLockHitTrader", "setting.villager_auto_lock_hit_trader", "setting.villager_auto_lock_hit_trader.desc", List.of(VILLAGER), new ArrayList<>(), null, false, ON_OFF, FALSE_STR),

    VILLAGER_SILENT_AUTO_REROLL_TRADER("VillagerSilentTrader", "setting.villager_silent_auto_reroll_trader", "setting.villager_silent_auto_reroll_trader.desc", List.of(VILLAGER), new ArrayList<>(), null, false, ON_OFF, FALSE_STR),

    VILLAGER_ONE_TICK_TRADER_COUNT("VillagerOneTickTraderCount", "setting.villager_one_tick_trader_count", "setting.villager_one_tick_trader_count.desc", List.of(VILLAGER), new ArrayList<>(), null, false, List.of("50", "100", "500", "1000"), "500"),

    VILLAGER_HARD_WORKING("VillagerHardWorking", "setting.villager_hard_working", "setting.villager_hard_working.desc", List.of(VILLAGER), new ArrayList<>(), null, false, List.of("1", "2", "5"), "1"),

    VILLAGER_INFINITE_TRADE("VillagerInfiniteTrade", "setting.villager_infinite_trade", "setting.villager_infinite_trade.desc", List.of(VILLAGER), new ArrayList<>(), null, false, ON_OFF, FALSE_STR),

    VILLAGER_MOVE_CONTROLLER("VillagerMoveController", "setting.villager_move_controller", "setting.villager_move_controller.desc", List.of(VILLAGER), new ArrayList<>(), null, false, PERMISSIONS, FALSE_STR),

    VILLAGER_TOOLSMITH_EXCHANGE("VillagerToolsmithExchange", "setting.villager_toolsmith_exchange", "setting.villager_toolsmith_exchange.desc", List.of(VILLAGER), new ArrayList<>(), null, false, ON_OFF, FALSE_STR),

    VILLAGER_EMERALD_DECOMPOSE("VillagerEmeraldDecompose", "setting.villager_emerald_decompose", "setting.villager_emerald_decompose.desc", List.of(VILLAGER), new ArrayList<>(), null, false, ON_OFF, FALSE_STR),

    VILLAGER_BOOKSHELF_DECOMPOSE("VillagerBookshelfDecompose", "setting.villager_bookshelf_decompose", "setting.villager_bookshelf_decompose.desc", List.of(VILLAGER), new ArrayList<>(), null, false, ON_OFF, FALSE_STR),


    // 方块
    BLOCK_MINING_RESISTANCE("BlockMiningResistance", "rule.block.mining_resistance", "rule.block.mining_resistance.desc", List.of(BLOCK, COMMAND), new ArrayList<>(), BlockMiningResistanceServerImpl.INSTANCE, false, ON_OFF, FALSE_STR),

    BLOCK_COLLECTABLE("BlockCollectable", "rule.block.collectable", "rule.block.collectable.desc", List.of(BLOCK, COMMAND), new ArrayList<>(), BlockCollectableServerImpl.INSTANCE, false, ON_OFF, FALSE_STR),

    BLOCK_EXPLOSION_RESISTANCE("BlockExplosionResistance", "rule.block.explosion_resistance", "rule.block.explosion_resistance.desc", List.of(BLOCK, COMMAND), new ArrayList<>(), BlockExplosionResistanceServerImpl.INSTANCE, false, ON_OFF, FALSE_STR),

    TNT_NO_DESTROY_DROPS("TntNoDestroyDrops", "setting.tnt_no_destroy_drops", "setting.tnt_no_destroy_drops.desc", List.of(BLOCK), new ArrayList<>(), null, false, ON_OFF, FALSE_STR),

    ;


    private final String registerName;
    private final String displayName;
    private final String displayInfo;
    private final List<RuleCategoryType> category;
    private final List<ListenerType> listenerType;
    private final RegisterServer commandObject;
    private final boolean multiSelect;
    private final List<String> quickSwitches;
    private final String state;
    private final StateSuggestionServer stateSuggestionServer;

    RuleType(String registerName, String displayName, String displayInfo, List<RuleCategoryType> category, List<ListenerType> listenerType, RegisterServer commandClass, boolean multiSelect, List<String> quickSwitches, String state) {
        this(registerName, displayName, displayInfo, category, listenerType, commandClass, multiSelect, quickSwitches, state, null);
    }

    RuleType(String registerName, String displayName, String displayInfo, List<RuleCategoryType> category, List<ListenerType> listenerType, RegisterServer commandClass, boolean multiSelect, List<String> quickSwitches, String state, StateSuggestionServer stateSuggestionServer) {
        this.listenerType = listenerType;
        this.multiSelect = multiSelect;
        this.quickSwitches = quickSwitches;
        this.registerName = registerName;
        this.displayName = displayName;
        this.displayInfo = displayInfo;
        this.category = category;
        this.commandObject = commandClass;
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

    public boolean isMultiSelect() {
        return multiSelect;
    }

    public List<String> getQuickSwitches() {
        return quickSwitches;
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