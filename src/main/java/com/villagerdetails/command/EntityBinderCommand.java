package com.villagerdetails.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.villagerdetails.VillagerDetails;
import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.command.register.server.RegisterServer;
import com.villagerdetails.config.WorldBindingConfig;
import com.villagerdetails.handler.villager.trader.IdTranslation;
import com.villagerdetails.rule.type.RuleCategoryType;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static com.villagerdetails.command.SwitchComponentType.DISABLE;
import static com.villagerdetails.command.SwitchComponentType.ENABLE;
import static com.villagerdetails.util.SendMessengerUtils.sendOrBroadcast;

public class EntityBinderCommand {

    public final static String COMMAND_BASE = "/" + VillagerDetails.BAST_COMMAND;

    /** reload 子命令的权限节点 */
    private static final Identifier PERM_RELOAD = VillagerDetails.id("c.reload");

    private static final SuggestionProvider<CommandSourceStack> RULE_SUGGESTER = (_, builder) ->
            SharedSuggestionProvider.suggest(
                    Arrays.stream(RuleType.values())
                            .map(RuleType::getRegisterName)
                            .collect(Collectors.toList()),
                    builder
            );

    private static final SuggestionProvider<CommandSourceStack> CATEGORY_SUGGESTER = (_, builder) ->
            SharedSuggestionProvider.suggest(
                    Arrays.stream(RuleCategoryType.values())
                            .map(RuleCategoryType::getRegisterName)
                            .collect(Collectors.toList()),
                    builder
            );

    private static final Logger log = LogManager.getLogger(EntityBinderCommand.class);

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(VillagerDetails.BAST_COMMAND)
                .executes(EntityBinderCommand::helpRules)
                .then(Commands.literal("list")
                        .executes(EntityBinderCommand::listRules)
                        .then(Commands.argument("category", StringArgumentType.word())
                                .suggests(CATEGORY_SUGGESTER)
                                .executes(EntityBinderCommand::listRulesByCategory)
                        )
                        .then(Commands.literal("enable")
                                .executes(ctx -> listRulesByStatus(ctx, true))
                        )
                        .then(Commands.literal("disable")
                                .executes(ctx -> listRulesByStatus(ctx, false))
                        )
                )
                // ★ 原 /c 挂到根下，成为 /xxx c
                .then(buildCSubcommand())
                .then(Commands.argument("rule", StringArgumentType.word())
                        .suggests(RULE_SUGGESTER)
                        .executes(ctx -> toggleRule(ctx, null))
                        .then(Commands.argument("state", BoolArgumentType.bool())
                                .executes(ctx -> toggleRule(ctx, BoolArgumentType.getBool(ctx, "state")))
                        )
                );
        dispatcher.register(root);
    }


    /**
     * 构建 /xxx c 子命令树。
     * 包含：
     *   · 所有 RegisterServer 注册的子命令（规则开启才可用）
     *   · reload
     */
    private static LiteralArgumentBuilder<CommandSourceStack> buildCSubcommand() {
        LiteralArgumentBuilder<CommandSourceStack> c = Commands.literal("c");

        // 遍历 RuleType 挂所有 RegisterServer
        for (RuleType ruleType : RuleType.values()) {
            RegisterServer registerServer = ruleType.getCommandObject();
            if (registerServer == null) continue;
            LiteralArgumentBuilder<CommandSourceStack> subCommand = registerServer.register()
                    .requires(_ -> RuleCache.isEnabled(ruleType));
            c = c.then(subCommand);
        }

        // reload
        c = c.then(Commands.literal("reload")
                .requires(src -> src.checkPermission(PERM_RELOAD, PermissionLevel.GAMEMASTERS))
                .executes(EntityBinderCommand::reloadMappings)
        );

        return c;
    }

    /** 重新加载 IdTranslation 映射 */
    private static int reloadMappings(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        MinecraftServer server = src.getServer();

        try {
            IdTranslation.loadFromWorld(server);
            src.sendSuccess(() -> Component.literal(
                    "§a[VillagerDetails] 映射配置已重新加载"), true);
            return 1;
        } catch (Exception e) {
            src.sendFailure(Component.literal(
                    "§c[VillagerDetails] 重新加载失败：" + e.getMessage()));
            return 0;
        }
    }

    // ==============================================================
    // 以下原有代码保持不变
    // ==============================================================

    private static int listRulesByStatus(CommandContext<CommandSourceStack> ctx, boolean enabled) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        List<RuleType> filteredRules = Arrays.stream(RuleType.values())
                .filter(rule -> RuleCache.isEnabled(rule) == enabled)
                .collect(Collectors.toList());
        String statusText = enabled ? "已经开启" : "已经关闭";
        sendOrBroadcast(player, Component.literal("§6============== " + statusText + " 规则列表 =============== "));
        if (filteredRules.isEmpty()) {
            sendOrBroadcast(player, Component.nullToEmpty(("没有更多"+ statusText +"规则")));
        } else {
            sendRuleList(player, filteredRules);
        }
        return 1;
    }

    private static int helpRules(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        MutableComponent mutableComponent = Component.empty();
        mutableComponent.append("规则分类\n");
        mutableComponent.append(categoryDisplay(List.of(RuleCategoryType.values())));
        sendOrLog(player, mutableComponent);
        return 1;
    }

    private static int listRules(CommandContext<CommandSourceStack> context) {
        return listRulesByCategory(context, null);
    }

    private static int listRulesByCategory(CommandContext<CommandSourceStack> context) {
        String categoryName = StringArgumentType.getString(context, "category");
        RuleCategoryType targetCategory = RuleCategoryType.getTypeByRegisterName(categoryName);

        if (targetCategory == null) {
            ServerPlayer player = context.getSource().getPlayer();
            sendOrBroadcast(player, Component.literal("§c未知的分类名称: " + categoryName));
            return 0;
        }

        return listRulesByCategory(context, targetCategory);
    }

    public static void sendRuleList(ServerPlayer player, List<RuleType> ruleTypeList) {
        for (RuleType rule : ruleTypeList) {
            boolean currentState = RuleCache.isEnabled(rule);
            player.sendSystemMessage(Component.empty()
                    .append(getRuleName(rule))
                    .append(switchComponent(rule, ENABLE, currentState))
                    .append(" ")
                    .append(switchComponent(rule, DISABLE, currentState))
            );
        }
    }

    private static int listRulesByCategory(CommandContext<CommandSourceStack> context, RuleCategoryType targetCategory) {
        ServerPlayer player = context.getSource().getPlayer();
        if (targetCategory == null) {
            sendOrBroadcast(player, Component.literal("§6========== 控制器规则列表（全部） =========="));
            sendRuleList(player, List.of(RuleType.values()));
        } else {
            sendOrBroadcast(player, Component.literal("§6========== 规则列表: " + targetCategory.getDisplayName() + " =========="));
            sendRuleList(player, RuleType.getRuleTypeListByRuleCategoryType(targetCategory));
        }
        return 1;
    }

    private static int toggleRule(CommandContext<CommandSourceStack> context, Boolean state) {
        ServerPlayer player = context.getSource().getPlayer();
        String ruleName = StringArgumentType.getString(context, "rule");
        RuleType rule = RuleType.getRuleTypeByRegisterName(ruleName);

        if (rule == null) {
            sendOrBroadcast(player, Component.literal("§c未知的规则名称: " + ruleName));
            return 0;
        }

        if (state == null) {
            boolean currentState = RuleCache.isEnabled(rule);
            MutableComponent mutableComponent = Component.empty();
            mutableComponent.append(divider())
                    .append(getRuleName(rule))
                    .append("\n分类： ")
                    .append(categoryDisplay(rule.getCategory()))
                    .append("\n")
                    .append(rule.getDisplayInfo())
                    .append("\n值： ")
                    .append(switchComponent(rule, ENABLE, currentState))
                    .append(" ")
                    .append(switchComponent(rule, DISABLE, currentState));
            sendOrLog(player, mutableComponent);
            return 1;
        }

        boolean targetState = state;
        RuleCache.setEnabled(rule, targetState);

        WorldBindingConfig config = WorldBindingConfig.getOrCreate(context.getSource().getServer());
        config.setBindingState(rule.getRegisterName(), targetState);

        sendOrBroadcast(player, Component.literal(
                String.format("§a %s (%s) 已%s",
                        rule.getDisplayName(), rule.getRegisterName(), targetState ? "§a开启" : "§c关闭")
        ));
        return 1;
    }

    private static MutableComponent getRuleName(RuleType ruleType) {
        return Component.literal("§f" + ruleType.getDisplayName() + " §f(" + ruleType.getRegisterName() + ")  ")
                .withStyle(style -> style
                        .withClickEvent(new ClickEvent.RunCommand(String.join(" ", COMMAND_BASE, ruleType.getRegisterName())))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal(ruleType.getDisplayInfo())))
                );
    }

    private static MutableComponent buildClickableButton(String text, int color, String command, String hoverText) {
        MutableComponent component = Component.literal(text)
                .withStyle(style -> style.withColor(TextColor.fromRgb(color)))
                .withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand(command)));

        if (hoverText != null && !hoverText.isEmpty()) {
            component.withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(Component.literal(hoverText))));
        }

        return component;
    }

    private static MutableComponent switchComponent(RuleType rule, SwitchComponentType type, boolean isEnable) {
        boolean targetState = (type == SwitchComponentType.ENABLE);
        boolean isMatch = (isEnable == targetState);
        int color;
        if (isMatch) {
            color = targetState ? 0x2ecc71 : 0xe74c3c;
        } else {
            color = 0xAAAAAA;
        }
        String command = String.join(" ", COMMAND_BASE, rule.getRegisterName(), type.getCommandStr());
        String hoverText = String.format(type.getOnClickMessage(), isEnable ? "已开启" : "已关闭");
        return buildClickableButton("[" + type.getDisplayName() + "]", color, command, hoverText);
    }

    private static MutableComponent categoryDisplay(List<RuleCategoryType> ruleCategoryTypes) {
        MutableComponent result = Component.empty();
        for (RuleCategoryType type : ruleCategoryTypes) {
            String command = String.join(" ", COMMAND_BASE, "list", type.getRegisterName());
            MutableComponent component = buildClickableButton(
                    "[" + type.getDisplayName() + "]",
                    0x5dade2,
                    command,
                    null
            );
            result.append(component).append(" ");
        }
        return result;
    }

    private static MutableComponent divider() {
        return Component.literal("§6================================================\n")
                .withStyle(style -> style.withColor(TextColor.fromRgb(0x555555)));
    }

    private static void sendOrLog(ServerPlayer player, MutableComponent mutableComponent) {
        if (player != null) {
            player.sendSystemMessage(mutableComponent);
        } else {
            log.info(mutableComponent.toString());
        }
    }
}