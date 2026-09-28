package com.villagerdetails.util;

import com.villagerdetails.lang.ServerTranslations;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 消息发送工具类
 */
public class SendMessengerUtils {



    private static final Logger log = LogManager.getLogger(SendMessengerUtils.class);

    /**
     * 向指定玩家发送系统消息（聊天框）
     */
    public static void sendToPlayer(ServerPlayer player, Component message) {
        if (player != null) {
            player.sendSystemMessage(message);
        }
    }

    /**
     * 向当前操作者发送消息，若无操作者则后台打印
     *
     * @param player  操作玩家（可为 null）
     * @param message 消息组件
     */
    public static void sendOrBroadcast(ServerPlayer player, Component message) {
        if (player != null) {
            sendToPlayer(player, message);
        } else {
            log.info(message.getString());
        }
    }

    /**
     * 向指定玩家发送动作栏消息
     *
     * @param player  操作玩家（可为 null）
     * @param message 消息组件
     */
    public static void sendOverlayOrBroadcast(ServerPlayer player, Component message) {
        if (player != null) {
            player.sendSystemMessage(Component.literal(ServerTranslations.resolve(message)), true);
        } else {
            log.info("未找到操作玩家，消息内容: {}", message.getString());
        }
    }
}