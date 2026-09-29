package com.villagerdetails.rule.type;

import com.villagerdetails.command.register.impl.BBSelectionServerImpl;
import com.villagerdetails.command.register.server.RegisterServer;
import com.villagerdetails.config.ServerLangConfig;
import com.villagerdetails.event.type.ListenerType;
import com.villagerdetails.lang.ServerTranslations;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

import static com.villagerdetails.command.SwitchComponentType.*;
import static com.villagerdetails.event.type.ListenerType.*;
import static com.villagerdetails.rule.type.RuleCategoryType.*;

public enum RuleType {


    //设置
    SETTING_LANGUAGE("Language", "setting.language", "setting.language.desc", List.of(MOD_SETTING), NONE, null, false, ServerLangConfig.getPreloadLanguages(), "en_us"),

    SETTING_EC_COMMAND_PERMISSION("ECCommand", "setting.ec_command_permission", "setting.ec_command_permission.desc", List.of(MOD_SETTING), NONE, null, false, SETTING_PERMISSIONS, OWNER_STR),


    // 实体
    VILLAGER_NO_SQUEEZE("NoSqueeze", "setting.villager_no_squeeze", "setting.villager_no_squeeze.desc", List.of(ENTITY, VILLAGER), NONE, null, true, List.of("minecraft:villager", "minecraft:piglin"), ""),
    NO_BRAIN_ON_PORTAL("NoBrainOnPortal", "setting.no_brain_on_portal", "setting.no_brain_on_portal.desc", List.of(ENTITY), NONE, null, false, ON_OFF, FALSE_STR),

    // 村民
    VILLAGER_BED_RESET("VillagerBedBinding", "setting.villager_bed_reset", "setting.villager_bed_reset.desc", List.of(VILLAGER), ENTITY_BLOCK_SELECTION, null, false, PERMISSIONS, FALSE_STR),

    VILLAGER_WORK_BLOCK_RESET("VillagerWorkBlockBinding", "setting.villager_work_block_reset", "setting.villager_work_block_reset.desc", List.of(VILLAGER), ENTITY_BLOCK_SELECTION, null, false, PERMISSIONS, FALSE_STR),

    VILLAGER_BATCH_BED_RESET("VillagerBatchBedBinding", "setting.villager_batch_bed_reset", "setting.villager_batch_bed_reset.desc", List.of(VILLAGER), BLOCK_RANGE_SELECTION, new BBSelectionServerImpl(), false, PERMISSIONS, FALSE_STR),

    VILLAGER_AUTO_TRADER("VillagerAutoTrader", "setting.villager_auto_trader", "setting.villager_auto_trader.desc", List.of(VILLAGER), NONE, null, false, ON_OFF, FALSE_STR),

    VILLAGER_GOD_TOOLS("VillagerGodTools", "setting.villager_god_tools", "setting.villager_god_tools.desc", List.of(VILLAGER), NONE, null, false, ON_OFF, FALSE_STR),

    VILLAGER_TERRACOTTA_TRADER("VillagerTerracottaTrader", "setting.villager_terracotta_trader", "setting.villager_terracotta_trader.desc", List.of(VILLAGER), NONE, null, false, ON_OFF, FALSE_STR),

    VILLAGER_AUTO_LOCK_HIT_TRADER("VillagerAutoLockHitTrader", "setting.villager_auto_lock_hit_trader", "setting.villager_auto_lock_hit_trader.desc", List.of(VILLAGER), NONE, null, false, ON_OFF, FALSE_STR),

    VILLAGER_SILENT_AUTO_REROLL_TRADER("VillagerSilentTrader", "setting.villager_silent_auto_reroll_trader", "setting.villager_silent_auto_reroll_trader.desc", List.of(VILLAGER), NONE, null, false, ON_OFF, FALSE_STR),

    VILLAGER_ONE_TICK_TRADER_COUNT("VillagerOneTickTraderCount", "setting.villager_one_tick_trader_count", "setting.villager_one_tick_trader_count.desc", List.of(VILLAGER), NONE, null, false, List.of("50", "100", "500", "1000"), "500"),

    VILLAGER_HARD_WORKING("VillagerHardWorking", "setting.villager_hard_working", "setting.villager_hard_working.desc", List.of(VILLAGER), NONE, null, false, List.of("1", "2", "5"), "1"),

    VILLAGER_INFINITE_TRADE("VillagerInfiniteTrade", "setting.villager_infinite_trade", "setting.villager_infinite_trade.desc", List.of(VILLAGER), NONE, null, false, ON_OFF, FALSE_STR),

    VILLAGER_MOVE_CONTROLLER("VillagerMoveController", "setting.villager_move_controller", "setting.villager_move_controller.desc", List.of(VILLAGER), ENTITY_BLOCK_SELECTION, null, false, PERMISSIONS, FALSE_STR),

    VILLAGER_TOOLSMITH_EXCHANGE("VillagerToolsmithExchange", "setting.villager_toolsmith_exchange", "setting.villager_toolsmith_exchange.desc", List.of(VILLAGER), NONE, null, false, ON_OFF, FALSE_STR),

    VILLAGER_EMERALD_DECOMPOSE("VillagerEmeraldDecompose", "setting.villager_emerald_decompose", "setting.villager_emerald_decompose.desc", List.of(VILLAGER), NONE, null, false, ON_OFF, FALSE_STR),

    VILLAGER_BOOKSHELF_DECOMPOSE("VillagerBookshelfDecompose", "setting.villager_bookshelf_decompose", "setting.villager_bookshelf_decompose.desc", List.of(VILLAGER), NONE, null, false, ON_OFF, FALSE_STR)

    ;

    private final int id;
    private final String registerName;
    private final String displayName;
    private final String displayInfo;
    private final List<RuleCategoryType> category;
    private final ListenerType listenerType;
    private final RegisterServer commandObject;
    private final boolean multiSelect;
    private final List<String> quickSwitches;
    private final String state;

    RuleType(String registerName, String displayName, String displayInfo, List<RuleCategoryType> category, ListenerType listenerType, RegisterServer commandClass, boolean multiSelect, List<String> quickSwitches, String state) {
        this.multiSelect = multiSelect;
        this.quickSwitches = quickSwitches;
        this.id = this.ordinal();
        this.registerName = registerName;
        this.displayName = displayName;
        this.displayInfo = displayInfo;
        this.category = category;
        this.listenerType = listenerType;
        this.commandObject = commandClass;
        this.state = state;
    }

    public int getId() {
        return id;
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

    public ListenerType getListenerType() {
        return listenerType;
    }

    public RegisterServer getCommandObject() {
        return commandObject;
    }

    public List<String> getQuickSwitches() {
        return quickSwitches;
    }

    public String getState() {
        return state;
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
        if(ruleCategoryType == null) return ruleTypeSet;
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

    public boolean isMultiSelect() {
        return multiSelect;
    }
}