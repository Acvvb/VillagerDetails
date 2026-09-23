package com.villagerdetails.handler.binding.type;

import com.villagerdetails.rule.type.RuleType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public enum BindingType {
    // 构造参数顺序：id, 实体Class, 工具物品, 工具名称, 国际化消息前缀
    BED(1, RuleType.BED_RESET, Villager.class, Items.LEAD, "bed", "msg.villager.bed"),
    WORK_BLOCK(2, RuleType.WORK_BLOCK_RESET, Villager.class, Items.LEAD, "work", "msg.villager.work_block"),
    ;

    private final int id;
    private final RuleType ruleType;
    private final Class<? extends Entity> entityClass;
    private final Item requiredItem;
    private final String requiredToolName;
    private final String i18nPrefix;

    BindingType(int id, RuleType ruleType, Class<? extends Entity> entityClass, Item requiredItem, String requiredToolName, String i18nPrefix) {
        this.id = id;
        this.ruleType = ruleType;
        this.entityClass = entityClass;
        this.requiredItem = requiredItem;
        this.requiredToolName = requiredToolName;
        this.i18nPrefix = i18nPrefix;
    }

    // ==================== Getter ====================

    public int getId() {
        return id;
    }

    public RuleType getRuleType() {
        return ruleType;
    }

    public Class<? extends Entity> getEntityClass() {
        return entityClass;
    }

    public Item getRequiredItem() {
        return requiredItem;
    }

    public String getRequiredToolName() {
        return requiredToolName;
    }

    public String getI18nPrefix() {
        return i18nPrefix;
    }
}