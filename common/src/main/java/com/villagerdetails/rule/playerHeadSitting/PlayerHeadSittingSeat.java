package com.villagerdetails.rule.playerHeadSitting;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * 「玩家头顶乘坐」的落座位置：<b>直接按碰撞箱算</b> —— 乘客的碰撞箱底 = 载具碰撞箱顶。
 *
 * <p>没有 {@code yOffset} 之类的配置项：座位高度由对方的碰撞箱决定，两个玩家的碰撞箱永远
 * 只是相切、不会重叠；也自动跟着姿势走（对方潜行时身高 1.8 → 1.5，乘客自然降低 0.3）。
 *
 * <p>为什么两端都要算：原版<b>不广播乘客坐标</b>（{@code ServerEntity#sendChanges} 对
 * {@code isPassenger()} 的实体只发朝向），每个客户端各自摆乘客；服务端那份决定服务端坐标 /
 * 触碰箱 / 下坐位置。公式放这里，两端调同一个，画面和服务端永远一致。
 */
public final class PlayerHeadSittingSeat {

    private PlayerHeadSittingSeat() {
    }

    /**
     * 乘客应该站的位置：载具碰撞箱的正上方。
     *
     * <p>{@code getBoundingBox().maxY} 就是载具碰撞箱顶；乘客脚底落在这一点上，
     * 两个箱子相切、不重叠。
     */
    public static Vec3 seatPosition(Entity vehicle) {
        return new Vec3(vehicle.getX(), vehicle.getBoundingBox().maxY, vehicle.getZ());
    }

    /**
     * 把乘客摆到载具碰撞箱顶上（服务端 / 客户端各调一次，公式相同）
     */
    public static void snapToSeat(Entity passenger, Entity vehicle) {
        Vec3 seat = seatPosition(vehicle);
        passenger.setPos(seat.x, seat.y, seat.z);
    }
}
