package com.villagerdetails.command.c.impl.reload;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.villagerdetails.command.c.server.RegisterServer;
import com.villagerdetails.config.ConfigRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import org.jspecify.annotations.Nullable;

import java.util.List;

import static com.villagerdetails.command.CommandConstants.PERM_BASE;
import static com.villagerdetails.util.SendMessengerUtils.sendOrBroadcast;

public class ReloadMapping implements RegisterServer {

    public static final ReloadMapping INSTANCE = new ReloadMapping();

    private ReloadMapping() {
    }

    private static final String K_RELOAD_SUCCESS = "command.entity_binder.reload.success";
    private static final String K_RELOAD_FAIL = "command.entity_binder.reload.fail";
    private static final String K_UNKNOWN_CONFIG = "command.entity_binder.reload.unknown_config";

    private static final SuggestionProvider<CommandSourceStack> CONFIG_SUGGESTER = (_, builder) ->
            SharedSuggestionProvider.suggest(ConfigRegistry.getReloadableNames(), builder);

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("reload")
                .requires(src -> src.checkPermission(PERM_BASE, PermissionLevel.OWNERS))
                .executes(ctx -> reloadMappings(ctx, null))
                .then(Commands.argument("config", StringArgumentType.word())
                        .suggests(CONFIG_SUGGESTER)
                        .executes(ctx -> reloadMappings(ctx, StringArgumentType.getString(ctx, "config"))));
    }

    private static int reloadMappings(CommandContext<CommandSourceStack> ctx, @Nullable String requestedName) {
        CommandSourceStack src = ctx.getSource();
        MinecraftServer server = src.getServer();
        ServerPlayer player = src.getPlayer();
        List<String> known = ConfigRegistry.getReloadableNames();

        if (requestedName != null && !known.contains(requestedName)) {
            sendOrBroadcast(player, Component.translatable(K_UNKNOWN_CONFIG,
                    requestedName, String.join(", ", known)));
            return 0;
        }

        try {
            // 核心逻辑：直接拿成功加载的名单，失败直接抛异常进 catch
            List<String> loaded;
            if (requestedName == null) {
                loaded = ConfigRegistry.reloadAllFromDisk(server);
            } else {
                loaded = ConfigRegistry.reloadSpecificFromDisk(server, requestedName);
            }

            sendOrBroadcast(player, Component.translatable(K_RELOAD_SUCCESS,
                    String.join(", ", loaded)));
            return 1;
        } catch (Exception e) {
            // 捕获所有异常，把错误信息打给玩家
            sendOrBroadcast(player, Component.translatable(K_RELOAD_FAIL, e.getMessage()));
            return 0;
        }
    }
}