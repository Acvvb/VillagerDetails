package com.villagerdetails.handler.monster.zombieVillager;

import com.villagerdetails.mixin.monster.zombieVillager.ZombieVillagerInvoker;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;

import static com.villagerdetails.cache.RuleCache.isEnabled;
import static com.villagerdetails.rule.type.RuleType.ZOMBIE_VILLAGER_HELP;
import static com.villagerdetails.util.SendMessengerUtils.sendOverlayOrBroadcast;

/**
 * 「命名牌写 help 贴到僵尸村民 → 立刻变回村民」。
 *
 * <p>触发点在 {@code NameTagItemMixin}（命名牌成功贴上去之后），这里只写「贴了 help 之后干什么」。
 *
 * <p>走的是<b>原版治愈流程</b>：调 {@link ZombieVillagerInvoker#villagerdetails$startConverting}
 * 把这只僵尸村民剩下的治愈时间设成 0，下一个 tick 原版 {@code tick()} 自己就会走完
 * {@code finishConversion} —— 所以治愈音效、职业 / 交易 / 声望 / 经验的保留、
 * 「僵尸医生」成就，全都和喂金苹果治好的效果一致。
 *
 * <p>「0」只写进这一只实体的实例字段：原版金苹果那条路用的是它自己随机出来的
 * 2401~6000 tick，完全不受影响。
 */
public final class ZombieVillagerCureHandler {

    /**
     * 触发用的名字（比较时忽略大小写与首尾空白）
     */
    public static final String HELP_NAME = "help";

    private ZombieVillagerCureHandler() {
    }

    /**
     * 玩家用命名牌给僵尸村民贴完名字之后调（只在原版命名成功、服务端侧调用）。
     *
     * @param rawName 命名牌上的自定义名字原文
     */
    public static void tryCure(ServerPlayer player, ZombieVillager zombieVillager, String rawName) {
        if (rawName == null || !HELP_NAME.equalsIgnoreCase(rawName.trim())) {
            return;
        }

        if (!isEnabled(player, ZOMBIE_VILLAGER_HELP)) {
            sendOverlayOrBroadcast(player,
                    Component.translatable("msg.zombie_villager.help_rule_disabled"));
            return;
        }

        // 别把 help 这个名字留在实体上 —— 治愈后的村民是新实体，名字不该跟着走
        zombieVillager.setCustomName(null);

        ((ZombieVillagerInvoker) zombieVillager)
                .villagerdetails$startConverting(player.getUUID(), 0);

        sendOverlayOrBroadcast(player, Component.translatable("msg.zombie_villager.cure_success"));
    }
}
