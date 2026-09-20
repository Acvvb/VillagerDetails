package com.villagerdetails.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.villagerdetails.VillagerDetails;
import com.villagerdetails.event.type.BindingType;

import com.villagerdetails.permission.BindingTypeSwitch;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Arrays;
import java.util.stream.Collectors;

public class EntityBinderCommand {

    /** 绑定类型名称的 Tab 补全 */
    private static final SuggestionProvider<CommandSourceStack> BINDING_TYPE_SUGGESTER = (context, builder) ->
            SharedSuggestionProvider.suggest(
                    Arrays.stream(BindingType.values())
                            .map(BindingType::getName)
                            .collect(Collectors.toList()),
                    builder
            );

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(VillagerDetails.MOD_ID)
                .executes(EntityBinderCommand::showHelp)

                // /entitybinder list
                .then(Commands.literal("list")
                        .executes(EntityBinderCommand::listTypes)
                )

                // /entitybinder reset
                .then(Commands.literal("reset")
                        .executes(EntityBinderCommand::resetAll)
                )

                // /entitybinder toggle <type> [on|off]
                .then(Commands.literal("toggle")
                        .then(Commands.argument("type", StringArgumentType.word())
                                .suggests(BINDING_TYPE_SUGGESTER)
                                .executes(ctx -> toggleType(ctx, null))
                                .then(Commands.argument("state", BoolArgumentType.bool())
                                        .executes(ctx -> toggleType(ctx, BoolArgumentType.getBool(ctx, "state")))
                                )
                        )
                );

        dispatcher.register(root);
    }

    // ==================== 帮助信息 ====================

    private static int showHelp(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return 0;

        player.sendSystemMessage(Component.literal("§6========== EntityBinder 帮助 =========="));
        player.sendSystemMessage(Component.literal("§e/entitybinder list §7- 列出所有绑定类型"));
        player.sendSystemMessage(Component.literal("§e/entitybinder toggle <type> [on|off] §7- 开关绑定类型"));
        player.sendSystemMessage(Component.literal("§e/entitybinder reset §7- 重置所有开关"));
        player.sendSystemMessage(Component.literal("§6======================================"));
        return 1;
    }

    // ==================== list ====================

    private static int listTypes(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return 0;

        player.sendSystemMessage(Component.literal("§6========== 绑定类型列表 =========="));
        for (BindingType type : BindingType.values()) {
            boolean enabled = BindingTypeSwitch.isEnabled(type);
            String status = enabled ? "§a开启" : "§c关闭";
            player.sendSystemMessage(Component.literal(
                    String.format("§e%-12s §7ID: %-2d §7[%s] §7- %s",
                            type.getName(), type.getId(), status, type.getMsg())
            ));
        }
        player.sendSystemMessage(Component.literal("§6================================"));
        return 1;
    }

    // ==================== reset ====================

    private static int resetAll(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return 0;

        BindingTypeSwitch.resetAll();
        player.sendSystemMessage(Component.literal("§a所有绑定类型已重置为默认开启状态"));
        return 1;
    }

    // ==================== toggle ====================

    private static int toggleType(CommandContext<CommandSourceStack> context, Boolean state) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return 0;

        String typeName = StringArgumentType.getString(context, "type");
        BindingType type = BindingType.ofName(typeName).orElse(null);

        if (type == null) {
            player.sendSystemMessage(Component.literal("§c未知的绑定类型: " + typeName));
            return 0;
        }

        if (state == null) {
            // 未指定 on/off，切换当前状态
            boolean current = BindingTypeSwitch.isEnabled(type);
            BindingTypeSwitch.setEnabled(type, !current);
            player.sendSystemMessage(Component.literal(
                    String.format("§a%s (%s) 已%s", type.getMsg(), type.getName(), !current ? "§a开启" : "§c关闭")
            ));
        } else {
            BindingTypeSwitch.setEnabled(type, state);
            player.sendSystemMessage(Component.literal(
                    String.format("§a%s (%s) 已%s", type.getMsg(), type.getName(), state ? "§a开启" : "§c关闭")
            ));
        }
        return 1;
    }
}