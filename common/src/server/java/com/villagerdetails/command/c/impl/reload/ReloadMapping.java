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

/**
 * {@code /ec c reload [config]} —— 从磁盘重载配置。
 *
 * <p><b>语义：以「物理文件」为单位整份重读。</b>
 * 所有规则侧配置（{@code NoSqueeze}、{@code MobNoSpawn}、方块表、传送门绑定……）都挤在
 * 同一份 {@code villagerdetails/config.json} 里，只重载其中一个 section 既省不了 IO，
 * 又会多出一条「谁先谁后」的驱动路径，所以：
 * <ul>
 *     <li>不带参数：整份重读，所有配置重新应用；</li>
 *     <li>带参数：<b>先校验名字是否合法</b>（名单 = {@link ConfigRegistry#getReloadableNames()}
 *         的 3 个父配置 + 规则侧 12 个 section 名），合法后<b>同样整份重读</b>。
 *         参数只是「查阅 / 防手滑」，不是「只刷这一份」—— 成功提示里会写明是整份重读。</li>
 * </ul>
 *
 * <p>重读走 {@link ConfigRegistry#reloadAllFromDisk(MinecraftServer)}：
 * {@code UnifiedConfigFile} 会真的去读 {@code config.json}（不经过 {@code ensureLoaded} 的短路），
 * 并且<b>不在末尾 save()</b>，玩家手改的内容不会被刚读到的内存快照盖回去。
 */
public class ReloadMapping implements RegisterServer {

    public static final ReloadMapping INSTANCE = new ReloadMapping();

    private ReloadMapping() {
    }

    private static final String K_RELOAD_SUCCESS = "command.entity_binder.reload.success";
    private static final String K_RELOAD_FAIL = "command.entity_binder.reload.fail";
    private static final String K_UNKNOWN_CONFIG = "command.entity_binder.reload.unknown_config";

    /**
     * 可点名的配置名：3 个父配置 + 12 个规则侧 section（去重保序）。
     *
     * <p>补全的名单是「全部可点的名字」，不是「能单独重载的名字」——
     * 因为执行时一律整份重读，列出来只是方便玩家确认自己没拼错。
     */
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

    /**
     * @param requestedName 玩家点名的配置；{@code null} 表示 {@code /ec c reload} 无参数。
     *                      合法与否只影响「是否继续」，真正执行的都是整份重读。
     */
    private static int reloadMappings(CommandContext<CommandSourceStack> ctx,
                                      @Nullable String requestedName) {
        CommandSourceStack src = ctx.getSource();
        MinecraftServer server = src.getServer();
        ServerPlayer player = src.getPlayer();
        List<String> known = ConfigRegistry.getReloadableNames();

        if (requestedName != null && !known.contains(requestedName)) {
            sendOrBroadcast(player, Component.translatable(K_UNKNOWN_CONFIG,
                    requestedName, String.join(", ", known)));
            return 0;
        }

        ConfigRegistry.Result result = ConfigRegistry.reloadAllFromDisk(server);
        if (!result.success()) {
            sendOrBroadcast(player, Component.translatable(K_RELOAD_FAIL,
                    String.join("; ", result.failed())));
            return 0;
        }

        sendOrBroadcast(player, Component.translatable(K_RELOAD_SUCCESS,
                String.join(", ", result.loaded())));
        return 1;
    }
}
