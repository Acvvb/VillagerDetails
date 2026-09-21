package com.villagerdetails.math.gridhashing.villager;

import com.villagerdetails.handler.entity.villager.VillagerBindHandler;
import com.villagerdetails.math.gridhashing.BindableMob;
import com.villagerdetails.math.gridhashing.BindableTarget;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;

/**
 * 村民床绑定策略
 * <p>
 * 连接通用匹配器与底层 Handler，负责单个村民的床绑定流程。
 * </p>
 */
public class VillagerBedStrategy {

    private final ServerLevel level;
    private final ServerPlayer operator;
    private final boolean sendMsg;

    /**
     * 构造函数（默认发送消息）
     */
    public VillagerBedStrategy(ServerLevel level, ServerPlayer operator) {
        this(level, operator, true);
    }

    /**
     * 构造函数
     *
     * @param level   服务端世界
     * @param operator 操作玩家
     * @param sendMsg 是否向玩家发送提示信息（区域批量绑定时传 false）
     */
    public VillagerBedStrategy(ServerLevel level, ServerPlayer operator, boolean sendMsg) {
        this.level = level;
        this.operator = operator;
        this.sendMsg = sendMsg;
    }

    /**
     * 执行单个村民绑床
     *
     * @param mob    村民适配器
     * @param target 床适配器
     * @return 绑定是否成功
     */
    public boolean bind(BindableMob mob, BindableTarget target) {
        return VillagerBindHandler.bindBed(
                level, operator,
                (Villager) mob.getEntity(),
                target.getPosition(),
                sendMsg
        );
    }
}