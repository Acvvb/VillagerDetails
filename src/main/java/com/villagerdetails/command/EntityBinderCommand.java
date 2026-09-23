package com.villagerdetails.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.villagerdetails.VillagerDetails;
import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.config.WorldBindingConfig;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Arrays;
import java.util.stream.Collectors;

import static com.villagerdetails.util.SendMessengerUtils.sendOrBroadcast;

public class EntityBinderCommand {

    /** 规则名称的 Tab 补全，使用 registerName 作为补全源 */
    private static final SuggestionProvider<CommandSourceStack> RULE_SUGGESTER = (_, builder) ->
            SharedSuggestionProvider.suggest(
                    Arrays.stream(RuleType.values())
                            .map(RuleType::getRegisterName) // 改为使用 registerName
                            .collect(Collectors.toList()),
                    builder
            );

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(VillagerDetails.MOD_ID)
                // 无参数时，列出所有规则
                .executes(EntityBinderCommand::listRules)

                // /entityController <registerName> [on|off]
                .then(Commands.argument("rule", StringArgumentType.word())
                        .suggests(RULE_SUGGESTER)
                        .executes(ctx -> toggleRule(ctx, null))
                        .then(Commands.argument("state", BoolArgumentType.bool())
                                .executes(ctx -> toggleRule(ctx, BoolArgumentType.getBool(ctx, "state")))
                        )
                );
        dispatcher.register(root);
    }


    /** 列出所有规则 */
    private static int listRules(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return 0;

        sendOrBroadcast(player,Component.literal("§6========== 控制器规则列表 =========="));
        for (RuleType rule : RuleType.values()) {
            boolean currentState = RuleCache.isEnabled(rule);
            String status = currentState ? "§a开启" : "§c关闭";
            sendOrBroadcast(player,Component.literal(
                    String.format("§e%s §7ID: %-2d §7[%s]",
                            rule.getRegisterName(), rule.getId(), status)
            ));
            sendOrBroadcast(player,Component.literal(
                    String.format("  §7名称: %s", rule.getDisplayName())
            ));
            sendOrBroadcast(player,Component.literal(
                    String.format("  §7说明: %s", rule.getDisplayInfo())
            ));
            sendOrBroadcast(player,Component.literal(
                    String.format("  §7分类: %s", rule.getCategory())
            ));
            sendOrBroadcast(player,Component.literal(" "));
        }
        sendOrBroadcast(player,Component.literal("§6================================"));
        return 1;
    }

    // ==================== toggle ====================

    private static int toggleRule(CommandContext<CommandSourceStack> context, Boolean state) {

        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return 0;

        String ruleName = StringArgumentType.getString(context, "rule");
        RuleType rule = Arrays.stream(RuleType.values())
                .filter(r -> r.getRegisterName().equalsIgnoreCase(ruleName))
                .findFirst()
                .orElse(null);

        if (rule == null) {
            sendOrBroadcast(player,Component.literal("§c未知的规则名称: " + ruleName));
            return 0;
        }

        // 未指定 state 时，显示规则详情而非切换开关
        if (state == null) {
            boolean currentState = RuleCache.isEnabled(rule);
            String status = currentState ? "§a开启" : "§c关闭";

            sendOrBroadcast(player,Component.literal("§6========== 规则详情 =========="));
            sendOrBroadcast(player,Component.literal(
                    String.format("§e%s(%s)", rule.getDisplayName(), rule.getRegisterName())
            ));
            sendOrBroadcast(player,Component.literal(
                    String.format("§e%s", rule.getDisplayInfo())
            ));
            sendOrBroadcast(player,Component.literal(
                    String.format("§e%s", rule.getCategory())
            ));
            sendOrBroadcast(player,Component.literal(
                    String.format("§e%s", status)
            ));
            sendOrBroadcast(player,Component.literal("§6================================"));
            return 1;
        }

        boolean targetState = state;
        RuleCache.setEnabled(rule, targetState);

        WorldBindingConfig config = WorldBindingConfig.getOrCreate(player.level());
        config.setBindingState(rule.getRegisterName(), targetState);
        config.setDirty();

        sendOrBroadcast(player,Component.literal(
                String.format("§a规则 %s (%s) 已%s",
                        rule.getDisplayName(), rule.getRegisterName(), targetState ? "§a开启" : "§c关闭")
        ));
        return 1;
    }
}