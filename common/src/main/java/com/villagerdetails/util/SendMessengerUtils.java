package com.villagerdetails.util;

import com.villagerdetails.lang.ServerTranslations;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class SendMessengerUtils {

    private static final Logger log = LogManager.getLogger(SendMessengerUtils.class);

    public static void sendOrBroadcast(ServerPlayer player, Component message) {
        Component out = ServerTranslations.translateComponent(message);  // ★
        if (player != null) {
            player.sendSystemMessage(out, false);
        } else {
            log.info(ServerTranslations.resolve(message));
        }
    }

    public static void sendOverlayOrBroadcast(ServerPlayer player, Component message) {
        Component out = ServerTranslations.translateComponent(message);  // ★
        if (player != null) {
            player.sendSystemMessage(out, true);
        } else {
            log.info("未找到操作玩家，消息内容: {}", ServerTranslations.resolve(message));
        }
    }
}