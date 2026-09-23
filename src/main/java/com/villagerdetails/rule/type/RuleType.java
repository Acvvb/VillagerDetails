package com.villagerdetails.rule.type;

import com.villagerdetails.command.register.impl.BBSelectionServerImpl;
import com.villagerdetails.command.register.server.RegisterServer;
import com.villagerdetails.event.type.ListenerType;

import java.util.List;

import static com.villagerdetails.event.type.ListenerType.BLOCK_RANGE_SELECTION;
import static com.villagerdetails.event.type.ListenerType.ENTITY_BLOCK_SELECTION;
import static com.villagerdetails.rule.type.RuleCategoryType.VILLAGER;

public enum RuleType {

    BED_RESET("VillagerBedBinding", "村民床编辑器", "使用命名为bed的拴绳蹲下右键选择村民和床修改村民所绑定的床", List.of(VILLAGER), ENTITY_BLOCK_SELECTION, null, false),
    WORK_BLOCK_RESET( "VillagerWorkBlockBinding", "村民工作方块编辑器", "使用命名为work的拴绳蹲下右键选择村民和床修改村民所绑定的工作方块", List.of(VILLAGER), ENTITY_BLOCK_SELECTION, null, false),
    BATCH_BED_RESET( "VillagerBatchBedBinding", "批量村民床编辑器", "命名为tool的拴绳左右键选区，执行/entityCommand bindAreaBeds将范围内村民的床绑定为与其直线距离最近的床", List.of(VILLAGER), BLOCK_RANGE_SELECTION, new BBSelectionServerImpl(), false)
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
}