package com.villagerdetails.util;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
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
     * 向指定维度的所有在线玩家广播消息（聊天框）
     */
    public static void broadcastToDimension(ServerLevel level, Component message) {
        for (ServerPlayer player : level.players()) {
            player.sendSystemMessage(message);
        }
    }

    /**
     * 向全服所有维度的所有在线玩家广播消息（聊天框）
     */
    public static void broadcastToAll(ServerLevel level, Component message) {
        level.getServer().getPlayerList().getPlayers().forEach(player -> player.sendSystemMessage(message));
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
            LogManager.getLogger(System.class).info(message);
        }
    }

    /**
     * 向指定玩家发送动作栏消息
     *
     * @param player  操作玩家（可为 null）
     * @param message 消息组件
     */
    public static void sendOrBroadcastActionBar(ServerPlayer player, Component message) {
        if (player != null) {
            player.sendSystemMessage(message, true);
        } else {
            log.info("未找到操作玩家，消息内容: {}", message.getString());
        }
    }
}