package com.villagerdetails.rule.type;

import com.villagerdetails.command.register.impl.BBSelectionServerImpl;
import com.villagerdetails.command.register.server.RegisterServer;
import com.villagerdetails.event.type.ListenerType;

import java.util.ArrayList;
import java.util.List;

import static com.villagerdetails.command.SwitchComponentType.*;
import static com.villagerdetails.event.type.ListenerType.*;
import static com.villagerdetails.rule.type.RuleCategoryType.MOD_SETTING;
import static com.villagerdetails.rule.type.RuleCategoryType.VILLAGER;

public enum RuleType {
    SETTING_EC_COMMAND_PERMISSION("ECCommand","/ec权限管理","管理除/ec c 以外的所有权限",List.of(MOD_SETTING),NONE,null,SETTING_PERMISSIONS,OWNER_STR),
    VILLAGER_BED_RESET("VillagerBedBinding", "村民床编辑器", "使用命名为bed的拴绳蹲下右键选择村民和床修改村民所绑定的床", List.of(VILLAGER), ENTITY_BLOCK_SELECTION, null, PERMISSIONS, FALSE_STR),
    VILLAGER_WORK_BLOCK_RESET( "VillagerWorkBlockBinding", "村民工作方块编辑器", "使用命名为work的拴绳蹲下右键选择村民和床修改村民所绑定的工作方块", List.of(VILLAGER), ENTITY_BLOCK_SELECTION, null, PERMISSIONS, FALSE_STR),
    VILLAGER_BATCH_BED_RESET( "VillagerBatchBedBinding", "批量村民床编辑器", "命名为tool的拴绳左右键选区，执行/entityCommand bindAreaBeds将范围内村民的床绑定为与其直线距离最近的床", List.of(VILLAGER), BLOCK_RANGE_SELECTION, new BBSelectionServerImpl(), PERMISSIONS, FALSE_STR),
    VILLAGER_AUTO_TRADER("VillagerAutoTrader","自动刷新交易","用命名牌写上目标附魔，右键图书管理员即可自动刷新出该满级附魔交易",List.of(VILLAGER),NONE,null, ON_OFF, FALSE_STR),
    VILLAGER_GOD_TOOLS("VillagerGodTools", "神装工具刷新", "使用331a/331b命名牌刷出三附魔钻石工具", List.of(VILLAGER), null, null, ON_OFF, FALSE_STR),
    VILLAGER_TERRACOTTA_TRADER("VillagerTerracottaTrader", "陶瓦刷新", "使用陶瓦名称命名牌同时刷出对应陶瓦和带釉陶瓦", List.of(VILLAGER), NONE, null, ON_OFF, FALSE_STR),
    VILLAGER_AUTO_LOCK_HIT_TRADER("VillagerAutoLockHitTrader","自动锁定村民交易","使用自动刷新交易规则刷新出的附魔书自动锁定该村民交易",List.of(VILLAGER) ,NONE,null, ON_OFF, FALSE_STR),
    VILLAGER_SILENT_AUTO_REROLL_TRADER("VillagerSilentTrader","无感村民交易刷新","使用自动刷新交易规则后自动移除命名名称",List.of(VILLAGER),NONE,null, ON_OFF, FALSE_STR),
    VILLAGER_ONE_TICK_TRADER_COUNT("VillagerOneTickTraderCount","每tick刷新交易次数","调整自动刷新交易单词刷新次数",List.of(VILLAGER),NONE,null, List.of("50","100","500","1000"),"500"),
    VILLAGER_HARD_WORKING("VillagerHardWorking","勤劳的村民","调整村民每次补货的数量(默认两倍补货)",List.of(VILLAGER),NONE,null, ON_OFF, FALSE_STR),
    VILLAGER_MOVE_CONTROLLER("VillagerMoveController","村民移动控制器","使用命名为move的拴绳蹲下右键选择村民和移动位置控制村民移动到目的地", List.of(VILLAGER), ENTITY_BLOCK_SELECTION,null, PERMISSIONS, FALSE_STR),
    VILLAGER_TOOLSMITH_EXCHANGE("VillagerToolsmithExchange", "村民可兑换钻石", "把工具匠的钻石换绿宝石改成绿宝石换钻石", List.of(VILLAGER), NONE, null, ON_OFF, FALSE_STR),
    ;

    private final int id;
    private final String registerName;
    private final String displayName;
    private final String displayInfo;
    private final List<RuleCategoryType> category;
    private final ListenerType listenerType;
    private final RegisterServer commandObject;
    private final List<String> quickSwitches;
    private final String state;

    RuleType(String registerName, String displayName, String displayInfo, List<RuleCategoryType> category, ListenerType listenerType, RegisterServer commandClass, List<String> quickSwitches, String state) {
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
        return displayName;
    }

    public String getDisplayInfo() {
        return displayInfo;
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
}