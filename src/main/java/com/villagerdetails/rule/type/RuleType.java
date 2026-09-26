package com.villagerdetails.rule.type;

import com.villagerdetails.command.register.impl.BBSelectionServerImpl;
import com.villagerdetails.command.register.server.RegisterServer;
import com.villagerdetails.event.type.ListenerType;

import java.util.ArrayList;
import java.util.List;

import static com.villagerdetails.event.type.ListenerType.BLOCK_RANGE_SELECTION;
import static com.villagerdetails.event.type.ListenerType.ENTITY_BLOCK_SELECTION;
import static com.villagerdetails.rule.type.RuleCategoryType.VILLAGER;

public enum RuleType {

    VILLAGER_BED_RESET("VillagerBedBinding", "村民床编辑器", "使用命名为bed的拴绳蹲下右键选择村民和床修改村民所绑定的床", List.of(VILLAGER), ENTITY_BLOCK_SELECTION, null, false),
    VILLAGER_WORK_BLOCK_RESET( "VillagerWorkBlockBinding", "村民工作方块编辑器", "使用命名为work的拴绳蹲下右键选择村民和床修改村民所绑定的工作方块", List.of(VILLAGER), ENTITY_BLOCK_SELECTION, null, false),
    VILLAGER_BATCH_BED_RESET( "VillagerBatchBedBinding", "批量村民床编辑器", "命名为tool的拴绳左右键选区，执行/entityCommand bindAreaBeds将范围内村民的床绑定为与其直线距离最近的床", List.of(VILLAGER), BLOCK_RANGE_SELECTION, new BBSelectionServerImpl(), false),
    VILLAGER_AUTO_TRADER("VillagerAutoTrader","自动刷新交易","用命名牌写上目标附魔，右键图书管理员即可自动刷新出该满级附魔交易",List.of(VILLAGER),null,null,false),
    VILLAGER_AUTO_LOCK_HIT_TRADER("VillagerAutoLockHitTrader","自动锁定村民交易","使用自动刷新交易规则刷新出的附魔书自动锁定该村民交易",List.of(VILLAGER) ,null,null,false),
    VILLAGER_SILENT_AUTO_REROLL_TRADER("VillagerSilentTrader","无感村民交易刷新","使用自动刷新交易规则后自动移除命名名称",List.of(VILLAGER),null,null,false),
    VILLAGER_HARD_WORKING("VillagerHardWorking","勤劳的村民","调整村民每次补货的数量(默认两倍补货)",List.of(VILLAGER),null,null,false),
    VILLAGER_MOVE_CONTROLLER("VillagerMoveController","村民移动控制器","使用命名为move的拴绳蹲下右键选择村民和移动位置控制村民移动到目的地", List.of(VILLAGER), ENTITY_BLOCK_SELECTION,null,false)
    ;

    private final int id;
    private final String registerName;
    private final String displayName;
    private final String displayInfo;
    private final List<RuleCategoryType> category;
    private final ListenerType listenerType;
    private final RegisterServer commandObject;
    private final boolean state;

    RuleType(String registerName, String displayName, String displayInfo, List<RuleCategoryType> category, ListenerType listenerType, RegisterServer commandClass, boolean state) {
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

    public boolean isState() {
        return state;
    }

    /**
     * 根据 id 查找对应的 RuleType（改为静态方法）
     */
    public static RuleType getRuleTypeById(int id) {
        for (RuleType ruleType : RuleType.values()) {
            if (ruleType.id == id) {
                return ruleType;
            }
        }
        return null;
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