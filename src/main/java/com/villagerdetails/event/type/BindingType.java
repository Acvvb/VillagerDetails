package com.villagerdetails.event.type;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Optional;

public enum BindingType {
    // 构造参数顺序：id, name, msg, 实体Class, 工具物品, 工具名称, 国际化消息前缀
    BED(1, "Villager_Bed", "床绑定", Villager.class, Items.LEAD, "bed", "msg.villager.bed"),
    WORK_BLOCK(2, "Villager_WorkBlock", "工作方块绑定", Villager.class, Items.LEAD, "workblock", "msg.villager.work_block"),
    ;

    private final int id;
    private final String name;
    private final String msg;
    private final Class<? extends Entity> entityClass;
    private final Item requiredItem;
    private final String requiredToolName;
    private final String i18nPrefix;

    BindingType(int id, String name, String msg,
                Class<? extends Entity> entityClass, Item requiredItem,
                String requiredToolName, String i18nPrefix) {
        this.id = id;
        this.name = name;
        this.msg = msg;
        this.entityClass = entityClass;
        this.requiredItem = requiredItem;
        this.requiredToolName = requiredToolName;
        this.i18nPrefix = i18nPrefix;
    }

    // ==================== Getter ====================

    public int getId() { return id; }
    public String getName() { return name; }
    public String getMsg() { return msg; }
    public Class<? extends Entity> getEntityClass() { return entityClass; }
    public Item getRequiredItem() { return requiredItem; }
    public String getRequiredToolName() { return requiredToolName; }
    public String getI18nPrefix() { return i18nPrefix; }

    // ==================== 静态查找方法 ====================

    public static Optional<BindingType> ofEntity(String entityTypeName) {
        for (BindingType value : values()) {
            if (value.requiredToolName.equalsIgnoreCase(entityTypeName)) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    public static Optional<BindingType> ofId(int id) {
        for (BindingType value : values()) {
            if (value.id == id) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    public static Optional<BindingType> ofName(String name) {
        for (BindingType value : values()) {
            if (value.name.equalsIgnoreCase(name)) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }
}