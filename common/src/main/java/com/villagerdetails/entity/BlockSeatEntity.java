package com.villagerdetails.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.NonNull;

/**
 * 「方块座位」——玩家坐在楼梯 / 半砖上时骑着的那个<b>看不见的小实体</b>。
 *
 * <p>为什么必须有一个实体：原版「玩家坐着」的样子（腿收起来）来自
 * {@code LivingEntity#isPassenger()}（客户端 {@code LivingEntityRenderer} 读它决定用坐姿模型），
 * 所以想让玩家坐到某个位置，就得让他真的「骑」点东西。这里这个实体就是个空壳：
 * <ul>
 *     <li>尺寸 0×0、不可见（{@link #shouldRenderAtSqrDistance} 恒 false）、不可选中（{@link #isPickable}）、
 *         不能被推（{@link #isPushable}）；</li>
 *     <li>{@link #tick()} 是空的：不重力、不泡水、不撞方块、不会被活塞推动；</li>
 *     <li>伤害免疫（{@link #hurtServer} 恒 false），打不掉也点不掉；</li>
 *     <li>类型注册成 {@code noSave()}（见 {@link ModEntities}），<b>不会写进存档</b> ——
 *         玩家站起来后实例直接 {@code discard}，存档里永远不会留垃圾实体。</li>
 * </ul>
 *
 * <p>位置换算交给原版：把一个高 0 的实体放在「方块顶面」，原版 {@code Entity#positionRider} 会用
 * 玩家自己的 vehicle 挂点 {@code (0, 0.6, 0)} 把他摆低 0.6 格 —— 这正好是坐姿模型「屁股贴面」的高度，
 * 所以这里不需要再手写偏移量。
 *
 * <p>客户端也会通过生成包造出这个类（无渲染器 → 什么都不画），但 {@link #seatBlock()} 只有服务端会用。
 */
public class BlockSeatEntity extends Entity {

    /**
     * 这个座位来自哪个方块（服务端判定「方块被挖了没」用；客户端恒为 {@code null}）
     */
    private BlockPos seatBlock;

    public BlockSeatEntity(EntityType<? extends BlockSeatEntity> type, Level level) {
        super(type, level);
    }

    /**
     * 记下座面来源方块；只影响服务端的存活判定
     */
    public void setSeatBlock(BlockPos pos) {
        this.seatBlock = pos == null ? null : pos.immutable();
    }

    /**
     * 座面来源方块，可能为 {@code null}（客户端 / 没设置过）
     */
    public BlockPos seatBlock() {
        return seatBlock;
    }

    // ==============================================================
    // 空壳：什么都不存、什么都不做
    // ==============================================================

    @Override
    protected void defineSynchedData(SynchedEntityData.@NonNull Builder builder) {
    }

    @Override
    protected void readAdditionalSaveData(@NonNull ValueInput input) {
    }

    @Override
    protected void addAdditionalSaveData(@NonNull ValueOutput output) {
    }

    /**
     * 座位不吃伤害（也压根选不中）
     */
    @Override
    public boolean hurtServer(@NonNull ServerLevel level, @NonNull DamageSource source, float amount) {
        return false;
    }

    /**
     * 不参与任何 tick：没有重力、不会掉下去，也不会被其它实体推动
     */
    @Override
    public void tick() {
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return false;
    }
}
