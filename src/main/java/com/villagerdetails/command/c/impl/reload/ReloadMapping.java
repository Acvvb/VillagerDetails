package com.villagerdetails.command.c.impl.reload;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.villagerdetails.command.c.server.RegisterServer;
import com.villagerdetails.config.ConfigRegistry;
import com.villagerdetails.config.ReloadableConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;

import static com.villagerdetails.command.CommandConstants.PERM_BASE;
import static com.villagerdetails.util.SendMessengerUtils.sendOrBroadcast;

public class ReloadMapping implements RegisterServer {

    public static final ReloadMapping INSTANCE = new ReloadMapping();

    private ReloadMapping() {
    }


    private static final String K_RELOAD_SUCCESS = "command.entity_binder.reload.success";
    private static final String K_RELOAD_FAIL = "command.entity_binder.reload.fail";

    private static final SuggestionProvider<CommandSourceStack> CONFIG_SUGGESTER = (_, builder) ->
            SharedSuggestionProvider.suggest(
                    ConfigRegistry.getAllNames(),
                    builder
            );

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("reload")
                .requires(src -> src.checkPermission(PERM_BASE, PermissionLevel.OWNERS))
                .executes(ctx -> reloadMappings(ctx, null))
                .then(Commands.argument("config", StringArgumentType.word())
                        .suggests(CONFIG_SUGGESTER)
                        .executes(ctx -> reloadMappings(ctx, StringArgumentType.getString(ctx, "config"))));
    }


    private static int reloadMappings(CommandContext<CommandSourceStack> ctx, String configName) {
        CommandSourceStack src = ctx.getSource();
        MinecraftServer server = src.getServer();
        ServerPlayer player = src.getPlayer();

        if (configName == null) {
            ConfigRegistry.Result result = ConfigRegistry.reloadAll(server);
            if (result.success()) {
                sendOrBroadcast(player, Component.translatable(K_RELOAD_SUCCESS, String.join(", ", result.loaded())));
                return 1;
            } else {
                sendOrBroadcast(player, Component.translatable(K_RELOAD_FAIL, String.join("; ", result.failed())));
                return 0;
            }
        }

        ReloadableConfig config = ConfigRegistry.get(configName);
        if (config == null) {
            sendOrBroadcast(player, Component.translatable("command.entity_binder.reload.unknown_config", configName,
                    String.join(", ", ConfigRegistry.getAllNames())));
            return 0;
        }

        try {
            config.reload(server);
            sendOrBroadcast(player, Component.translatable(K_RELOAD_SUCCESS, config.configName()));
            return 1;
        } catch (Exception e) {
            sendOrBroadcast(player, Component.translatable(K_RELOAD_FAIL,
                    config.configName() + ": " + e.getMessage()));
            return 0;
        }
    }

}
