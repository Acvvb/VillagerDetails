package com.villagerdetails.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.villagerdetails.VillagerDetails;
import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.command.register.server.RegisterServer;
import com.villagerdetails.config.WorldBindingConfig;
import com.villagerdetails.handler.villager.trader.refresh.IdTranslation;
import com.villagerdetails.rule.type.RuleCategoryType;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static com.villagerdetails.cache.RuleCache.isEnabled;
import static com.villagerdetails.command.CommandConstants.COMMAND_BASE;
import static com.villagerdetails.command.CommandConstants.PERM_BASE;
import static com.villagerdetails.rule.type.RuleType.SETTING_EC_COMMAND_PERMISSION;
import static com.villagerdetails.util.SendMessengerUtils.sendOrBroadcast;
public class EntityBinderCommand {



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

    private static final SuggestionProvider<CommandSourceStack> STATE_SUGGESTER = (ctx, builder) -> {
        RuleType rule = RuleType.getRuleTypeByRegisterName(StringArgumentType.getString(ctx, "rule"));
        if (rule == null) {
            return builder.buildFuture();
        }
        return SharedSuggestionProvider.suggest(
                rule.getQuickSwitches(),
                builder);
    };

    private static final Logger log = LogManager.getLogger(EntityBinderCommand.class);

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(VillagerDetails.BAST_COMMAND)
                // /ec 自身执行时手动检查权限 2（否则会连带屏蔽 /ec c）
                .executes(ctx -> {
                    if (!hasBasePermission(ctx.getSource())) {
                        return 0;
                    }
                    return helpRules(ctx);
                })
                // list 分支：整体要求权限 2
                .then(Commands.literal("list")
                        .requires(EntityBinderCommand::hasBasePermission)
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
                // c 分支：所有人可访问（内部 reload 等敏感节点由自身 requires 保护）
                .then(buildCSubcommand())
                .then(Commands.argument("rule", StringArgumentType.word())
                        .requires(EntityBinderCommand::hasBasePermission)
                        .suggests(RULE_SUGGESTER)
                        .executes(ctx -> toggleRule(ctx, null))
                        .then(Commands.argument("state", StringArgumentType.word())
                                .suggests(STATE_SUGGESTER)
                                .executes(ctx -> toggleRule(ctx, StringArgumentType.getString(ctx, "state")))
                        )
                );
        dispatcher.register(root);
    }

    /**
     * 检查命令源是否拥有指定的权限节点。
     *
     * @param src 命令源
     * @return 拥有返回 true，否则 false
     */
    private static boolean hasBasePermission(CommandSourceStack src) {
        PermissionLevel permissionLevel = SwitchComponentType.permissionLevelOf(RuleCache.getState(SETTING_EC_COMMAND_PERMISSION));
        return src.checkPermission(PERM_BASE, permissionLevel != null ? permissionLevel : PermissionLevel.OWNERS);
    }

    /**
     * 构建 /ec c 子命令树。
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
                    .requires(src -> isEnabled(src,ruleType));
            c = c.then(subCommand);
        }

        // reload
        c = c.then(Commands.literal("reload")
                .requires(src -> src.checkPermission(PERM_BASE, PermissionLevel.OWNERS))
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


    private static int listRulesByStatus(CommandContext<CommandSourceStack> ctx, boolean enabled) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        List<RuleType> filteredRules = Arrays.stream(RuleType.values())
                .filter(rule -> isEnabled(rule) == enabled)
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
            String currentState = RuleCache.getState(rule);
            player.sendSystemMessage(Component.empty()
                    .append(getRuleName(rule))
                    .append(quickSwitchComponents(rule, currentState))
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

    private static int toggleRule(CommandContext<CommandSourceStack> context, String state) {
        ServerPlayer player = context.getSource().getPlayer();
        String ruleName = StringArgumentType.getString(context, "rule");
        RuleType rule = RuleType.getRuleTypeByRegisterName(ruleName);

        if (rule == null) {
            sendOrBroadcast(player, Component.literal("§c未知的规则名称: " + ruleName));
            return 0;
        }

        if (state == null) {
            String currentState = RuleCache.getState(rule);
            MutableComponent mutableComponent = Component.empty();
            mutableComponent.append(divider())
                    .append(getRuleName(rule))
                    .append("\n分类： ")
                    .append(categoryDisplay(rule.getCategory()))
                    .append("\n")
                    .append(rule.getDisplayInfo())
                    .append("\n值： ")
                    .append(quickSwitchComponents(rule, currentState));
            sendOrLog(player, mutableComponent);
            return 1;
        }

        String canonical = rule.getQuickSwitches().stream()
                .filter(s -> s.equalsIgnoreCase(state))
                .findFirst()
                .orElse(null);
        if (canonical == null) {
            sendOrBroadcast(player, Component.literal("§c无效的状态值: " + state));
            return 0;
        }

        RuleCache.setState(rule, canonical);

        WorldBindingConfig config = WorldBindingConfig.getOrCreate(context.getSource().getServer());
        config.setBindingState(rule.getRegisterName(), canonical);

        sendOrBroadcast(player, Component.literal(
                String.format("§a %s (%s) 已切换为 %s",
                        rule.getDisplayName(), rule.getRegisterName(), SwitchComponentType.displayNameOf(canonical))
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

    private static MutableComponent quickSwitchComponents(RuleType rule, String currentState) {
        MutableComponent result = Component.empty();
        List<String> switches = rule.getQuickSwitches();
        for (int i = 0; i < switches.size(); i++) {
            if (i > 0) {
                result.append(" ");
            }
            result.append(switchComponent(rule, switches.get(i), currentState));
        }
        return result;
    }

    private static MutableComponent switchComponent(RuleType rule, String state, String currentState) {
        boolean isMatch = state.equalsIgnoreCase(currentState);
        boolean isOn = !SwitchComponentType.FALSE_STR.equalsIgnoreCase(state);
        int color;
        if (isMatch) {
            color = isOn ? 0x2ecc71 : 0xe74c3c;
        } else {
            color = 0xAAAAAA;
        }
        String displayName = SwitchComponentType.displayNameOf(state);
        String command = String.join(" ", COMMAND_BASE, rule.getRegisterName(), state);
        String hoverText = String.format(SwitchComponentType.INFO, SwitchComponentType.displayNameOf(currentState));
        return buildClickableButton("[" + displayName + "]", color, command, hoverText);
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