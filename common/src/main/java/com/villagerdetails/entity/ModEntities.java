package com.villagerdetails.entity;

import com.villagerdetails.VillagerDetails;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

/**
 * 本 mod 自己注册的实体类型（目前只有一个：方块座位）。
 *
 * <p>只登记这一个：{@link BlockSeatEntity} 是「玩家坐在楼梯 / 半砖上」用的空壳载具，
 * 见那个类的注释。注册要点：
 * <ul>
 *     <li><b>尺寸 0×0</b>：没有碰撞箱、没有渲染、不挡视线；</li>
 *     <li><b>{@code noSave()}</b>：不进存档（配合 {@code discard}，玩家站起来就没有任何残留）；
 *         代价是原版 {@code Entity#startRiding} 会因为它 {@code canSerialize() == false} 拒绝上坐，
 *         所以由 {@code EntityStartRidingMixin} 放行（和「玩家当载具」同一个口子）；</li>
 *     <li><b>{@code noSummon()}</b>：不给 {@code /summon} 用，免得被人刷一地隐形实体；</li>
 *     <li><b>{@code clientTrackingRange(8)}</b>：必须 &gt; 0，否则客户端收不到生成包、
 *         不知道玩家骑了个东西，坐姿就不生效（原版 {@code MARKER} 就是 0，所以不能直接复用它）。</li>
 * </ul>
 *
 * <p>实体类型必须在<b>两端</b>都登记（客户端要靠同一份类型解码生成包），所以本类放在 {@code main}，
 * 由 {@code VillagerDetails#onInitialize} 调 {@link #register()} 触发静态初始化。
 */
public final class ModEntities {

    private ModEntities() {
    }

    /**
     * 注册名 {@code villagerdetails:block_seat}
     */
    private static final ResourceKey<EntityType<?>> BLOCK_SEAT_KEY =
            ResourceKey.create(Registries.ENTITY_TYPE, VillagerDetails.id("block_seat"));

    /**
     * 方块座位
     */
    public static final EntityType<BlockSeatEntity> BLOCK_SEAT = registerBlockSeat();

    private static EntityType<BlockSeatEntity> registerBlockSeat() {
        EntityType<BlockSeatEntity> type = EntityType.Builder.of(BlockSeatEntity::new, MobCategory.MISC)
                .sized(0.0F, 0.0F)
                .clientTrackingRange(8)
                .noSave()
                .noSummon()
                .noLootTable()
                .build(BLOCK_SEAT_KEY);
        return Registry.register(BuiltInRegistries.ENTITY_TYPE, BLOCK_SEAT_KEY, type);
    }

    /**
     * 触发本类静态初始化（也就是完成注册）；两端入口各调一次，重复调用无害。
     */
    public static void register() {
        // 静态字段初始化即注册，这里不需要额外动作
    }
}
