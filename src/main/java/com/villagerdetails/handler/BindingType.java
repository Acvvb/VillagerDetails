package com.villagerdetails.handler;

import com.villagerdetails.util.BindingToolUtils;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Optional;

public enum BindingType {
    // 构造参数顺序：实体Class, 工具物品, 工具名称, 国际化消息前缀
    BED(Villager.class, Items.LEAD, "bed", "msg.villager.bed"),
    WORK_BLOCK(Villager.class, Items.LEAD, "workblock", "msg.villager.work_block"),
    // 以后要加新的绑定类型，直接在这里加一行就行
    ;

    private final Class<? extends Entity> entityClass;
    private final Item requiredItem;
    private final String requiredToolName;
    private final String i18nPrefix; // 国际化消息前缀

    BindingType(Class<? extends Entity> entityClass, Item requiredItem, String requiredToolName, String i18nPrefix) {
        this.entityClass = entityClass;
        this.requiredItem = requiredItem;
        this.requiredToolName = requiredToolName;
        this.i18nPrefix = i18nPrefix;
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

    public static BindingType isHoldingAnyTool(Player player, InteractionHand hand, Entity entity) {
        for (BindingType type : BindingType.values()) {
            if (type.entityClass.isInstance(entity)){
                if (BindingToolUtils.isNotHoldingTool(player, hand, type.getRequiredItem(), type.getRequiredToolName())) {
                    return type;
                }
            }
        }
        return null;
    }

    /**
     * 根据实体类型查找对应的绑定类型
     */
    public static Optional<BindingType> ofEntity(String entityTypeName) {
        for (BindingType value : values()) {
            if (value.requiredToolName.equalsIgnoreCase(entityTypeName)) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }
}