package com.villagerdetails.network.sync.clientbound;

import com.villagerdetails.network.channel.SyncChannel;
import com.villagerdetails.network.sync.payload.clientbound.PortalViewSyncPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * S→C：把要「视图高亮」的绑定区域推给某个客户端。只影响本机渲染，不参与任何服务端逻辑。
 *
 * <p>一次 {@link #syncTo} 只推<b>一侧</b>；一条绑定横跨两个维度，调用方连着调两次即可
 * （客户端按维度并存，后一个包不会把前一个盖掉）。
 *
 * <p>放在 {@code network.sync.clientbound} 包下 = 自动注册成 S→C 包类型，不用手动登记。
 */
public class PortalViewSyncChannel extends SyncChannel<PortalViewSyncPayload> {

    public static final PortalViewSyncChannel INSTANCE = new PortalViewSyncChannel();

    protected PortalViewSyncChannel() {
        super(PortalViewSyncPayload.ID);
    }

    /**
     * 推某一侧的高亮区域；客户端会替换掉该维度原有的记录
     */
    public void syncTo(ServerPlayer player, ResourceKey<Level> dim, BlockPos min, BlockPos max) {
        sendTo(player, new PortalViewSyncPayload(dim, min, max));
    }

    /**
     * 清掉这个客户端在所有维度上的视图高亮
     */
    public void clear(ServerPlayer player) {
        sendTo(player, new PortalViewSyncPayload(null, null, null));
    }

    /**
     * 清掉<b>所有在线客户端</b>的视图高亮。
     *
     * <p>用在高亮区域本身消失的场景（例如 {@code /ec c PortalBinding delete}）：
     * 高亮只是客户端缓存，服务端没有「谁在看哪条」的账本，所以只能整体清一遍，
     * 否则删掉的那条区域在别人屏幕上还会一直画着。空包语义就是「清空所有维度」。
     */
    public void clearAll() {
        broadcast(new PortalViewSyncPayload(null, null, null));
    }
}
