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
import com.villagerdetails.config.ConfigRegistry;
import com.villagerdetails.config.ReloadableConfig;
import com.villagerdetails.config.impl.RuleConfig;
import com.villagerdetails.rule.type.RuleCategoryType;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;


import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static com.villagerdetails.cache.RuleCache.isEnabled;
import static com.villagerdetails.command.CommandConstants.COMMAND_BASE;
import static com.villagerdetails.command.CommandConstants.PERM_BASE;
import static com.villagerdetails.rule.type.RuleType.SETTING_EC_COMMAND_PERMISSION;
import static com.villagerdetails.util.SendMessengerUtils.sendOrBroadcast;

public class EntityBinderCommand {

    // ============================================================
    // 语言键
    // ============================================================
    private static final String K_RELOAD_SUCCESS       = "command.entity_binder.reload.success";
    private static final String K_RELOAD_FAIL          = "command.entity_binder.reload.fail";

    private static final String K_STATUS_ENABLED       = "command.entity_binder.status.enabled";
    private static final String K_STATUS_DISABLED      = "command.entity_binder.status.disabled";

    private static final String K_LIST_HEADER_STATUS   = "command.entity_binder.list.header.status";
    private static final String K_LIST_EMPTY_STATUS    = "command.entity_binder.list.empty.status";
    private static final String K_LIST_HEADER_ALL      = "command.entity_binder.list.header.all";
    private static final String K_LIST_HEADER_CATEGORY = "command.entity_binder.list.header.category";

    private static final String K_HELP_CATEGORIES      = "command.entity_binder.help.categories";

    private static final String K_ERR_UNKNOWN_CATEGORY = "command.entity_binder.error.unknown_category";
    private static final String K_ERR_UNKNOWN_RULE     = "command.entity_binder.error.unknown_rule";
    private static final String K_ERR_INVALID_STATE    = "command.entity_binder.error.invalid_state";
    private static final String K_ERR_INVALID_OPTION   = "command.entity_binder.error.invalid_option";

    private static final String K_TOGGLE_CATEGORY      = "command.entity_binder.toggle.category_label";
    private static final String K_TOGGLE_VALUE         = "command.entity_binder.toggle.value_label";
    private static final String K_TOGGLE_SUCCESS       = "command.entity_binder.toggle.success";
    private static final String K_TOGGLE_EMPTY_VALUE   = "command.entity_binder.toggle.empty_value";

    //补全
    private static final SuggestionProvider<CommandSourceStack> RULE_SUGGESTER = (_, builder) ->
            SharedSuggestionProvider.suggest(
                    Arrays.stream(RuleType.values())
                            .map(RuleType::getRegisterName)
                            .collect(Collectors.toList()),
                    builder);

    private static final SuggestionProvider<CommandSourceStack> CATEGORY_SUGGESTER = (_, builder) ->
            SharedSuggestionProvider.suggest(
                    Arrays.stream(RuleCategoryType.values())
                            .map(RuleCategoryType::getRegisterName)
                            .collect(Collectors.toList()),
                    builder);

    private static final SuggestionProvider<CommandSourceStack> STATE_SUGGESTER = (ctx, builder) -> {
        RuleType rule = RuleType.getRuleTypeByRegisterName(StringArgumentType.getString(ctx, "rule"));
        if (rule == null) {
            return builder.buildFuture();
        }
        return SharedSuggestionProvider.suggest(rule.getQuickSwitches(), builder);
    };

    private static final SuggestionProvider<CommandSourceStack> CONFIG_SUGGESTER = (_, builder) ->
            SharedSuggestionProvider.suggest(
                    ConfigRegistry.getAllNames(),
                    builder
            );

    // ============================================================
    // 注册
    // ============================================================

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(VillagerDetails.BAST_COMMAND)
                .executes(ctx -> {
                    if (!hasBasePermission(ctx.getSource())) {
                        return 0;
                    }
                    return helpRules(ctx);
                })
                .then(Commands.literal("list")
                        .requires(EntityBinderCommand::hasBasePermission)
                        .executes(EntityBinderCommand::listRules)
                        .then(Commands.argument("category", StringArgumentType.word())
                                .suggests(CATEGORY_SUGGESTER)
                                .executes(EntityBinderCommand::listRulesByCategory))
                        .then(Commands.literal("enable")
                                .executes(ctx -> listRulesByStatus(ctx, true)))
                        .then(Commands.literal("disable")
                                .executes(ctx -> listRulesByStatus(ctx, false))))
                .then(buildCSubcommand())
                .then(Commands.argument("rule", StringArgumentType.word())
                        .requires(EntityBinderCommand::hasBasePermission)
                        .suggests(RULE_SUGGESTER)
                        .executes(ctx -> toggleRule(ctx, null))
                        .then(Commands.argument("state", StringArgumentType.greedyString())
                                .suggests(STATE_SUGGESTER)
                                .executes(ctx -> toggleRule(ctx, StringArgumentType.getString(ctx, "state")))));
        dispatcher.register(root);
    }

    private static boolean hasBasePermission(CommandSourceStack src) {
        PermissionLevel permissionLevel = SwitchComponentType.permissionLevelOf(
                RuleCache.getState(SETTING_EC_COMMAND_PERMISSION));
        return src.checkPermission(PERM_BASE,
                permissionLevel != null ? permissionLevel : PermissionLevel.OWNERS);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildCSubcommand() {
        LiteralArgumentBuilder<CommandSourceStack> c = Commands.literal("c");

        for (RuleType ruleType : RuleType.values()) {
            RegisterServer registerServer = ruleType.getCommandObject();
            if (registerServer == null) continue;
            LiteralArgumentBuilder<CommandSourceStack> subCommand = registerServer.register()
                    .requires(src -> isEnabled(src, ruleType));
            c = c.then(subCommand);
        }

        c = c.then(Commands.literal("reload")
                .requires(src -> src.checkPermission(PERM_BASE, PermissionLevel.OWNERS))
                .executes(ctx -> reloadMappings(ctx, null))                    // /ec c reload
                .then(Commands.argument("config", StringArgumentType.word())    // /ec c reload <name>
                        .suggests(CONFIG_SUGGESTER)
                        .executes(ctx -> reloadMappings(ctx, StringArgumentType.getString(ctx, "config")))
                )
        );

        return c;
    }

    // ============================================================
    // /ec c reload
    // ============================================================

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
            sendOrBroadcast(player, Component.literal("§c未知的配置名: " + configName + "（可用：" + String.join(", ", ConfigRegistry.getAllNames()) + "）"));
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

    // ============================================================
    // list 相关
    // ============================================================

    private static int listRulesByStatus(CommandContext<CommandSourceStack> ctx, boolean enabled)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        List<RuleType> filteredRules = Arrays.stream(RuleType.values())
                .filter(rule -> isEnabled(rule) == enabled)
                .collect(Collectors.toList());

        Component statusComp = Component.translatable(enabled ? K_STATUS_ENABLED : K_STATUS_DISABLED);
        sendOrBroadcast(player, Component.translatable(K_LIST_HEADER_STATUS, statusComp));

        if (filteredRules.isEmpty()) {
            sendOrBroadcast(player, Component.translatable(K_LIST_EMPTY_STATUS, statusComp));
        } else {
            sendRuleList(player, filteredRules);
        }
        return 1;
    }

    private static int helpRules(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        MutableComponent mutableComponent = Component.empty();
        mutableComponent.append(Component.translatable(K_HELP_CATEGORIES));
        mutableComponent.append(categoryDisplay(List.of(RuleCategoryType.values())));
        sendOrBroadcast(player, mutableComponent);   // ★ 统一走 SendMessengerUtils
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
            sendOrBroadcast(player, Component.translatable(K_ERR_UNKNOWN_CATEGORY, categoryName));
            return 0;
        }

        return listRulesByCategory(context, targetCategory);
    }

    /** 逐条发送规则列表，每条都走 SendMessengerUtils */
    public static void sendRuleList(ServerPlayer player, List<RuleType> ruleTypeList) {
        for (RuleType rule : ruleTypeList) {
            String currentState = RuleCache.getState(rule);
            Component line = Component.empty()
                    .append(getRuleName(rule))
                    .append(quickSwitchComponents(rule, currentState));
            sendOrBroadcast(player, line);   // ★ 统一走 SendMessengerUtils
        }
    }

    private static int listRulesByCategory(CommandContext<CommandSourceStack> context,
                                           RuleCategoryType targetCategory) {
        ServerPlayer player = context.getSource().getPlayer();
        if (targetCategory == null) {
            sendOrBroadcast(player, Component.translatable(K_LIST_HEADER_ALL));
            sendRuleList(player, List.of(RuleType.values()));
        } else {
            sendOrBroadcast(player, Component.translatable(
                    K_LIST_HEADER_CATEGORY, targetCategory.getDisplayName()));
            sendRuleList(player, RuleType.getRuleTypeListByRuleCategoryType(targetCategory));
        }
        return 1;
    }

    // ============================================================
    // 开关规则
    // ============================================================

    private static int toggleRule(CommandContext<CommandSourceStack> context, String state) {
        ServerPlayer player = context.getSource().getPlayer();
        String ruleName = StringArgumentType.getString(context, "rule");
        RuleType rule = RuleType.getRuleTypeByRegisterName(ruleName);

        if (rule == null) {
            sendOrBroadcast(player, Component.translatable(K_ERR_UNKNOWN_RULE, ruleName));
            return 0;
        }

        // 无 state → 显示当前
        if (state == null) {
            String currentState = RuleCache.getState(rule);
            MutableComponent mc = Component.empty();
            mc.append(divider())
                    .append(getRuleName(rule))
                    .append(Component.translatable(K_TOGGLE_CATEGORY))
                    .append(categoryDisplay(rule.getCategory()))
                    .append("\n")
                    .append(Component.translatable(rule.getDisplayInfo()))
                    .append(Component.translatable(K_TOGGLE_VALUE))
                    .append(quickSwitchComponents(rule, currentState));
            sendOrBroadcast(player, mc);   // ★ 统一走 SendMessengerUtils
            return 1;
        }

        // 校验 state
        String canonical = rule.isMultiSelect()
                ? canonicalMultiSelect(rule, state, player)
                : canonicalSingleSelect(rule, state, player);
        if (canonical == null) return 0;

        RuleCache.setState(rule, canonical);

        RuleConfig config = RuleConfig.getOrCreate(context.getSource().getServer());
        config.setBindingState(rule.getRegisterName(), canonical);

        Component valueComp = canonical.isEmpty()
                ? Component.translatable(K_TOGGLE_EMPTY_VALUE)
                : Component.literal(canonical);

        sendOrBroadcast(player, Component.translatable(
                K_TOGGLE_SUCCESS,
                Component.translatable(rule.getDisplayName()),
                rule.getRegisterName(),
                valueComp));
        return 1;
    }

    /** 单选：预定义列表必须匹配；自定义列表放行任意非空输入 */
    private static String canonicalSingleSelect(RuleType rule, String state, ServerPlayer player) {
        // ★ 自定义列表 → 允许任意非空输入
        if (SwitchComponentType.isCustomList(rule.getQuickSwitches())) {
            String trimmed = state == null ? "" : state.trim();
            if (trimmed.isEmpty()) {
                if (state != null) {
                    sendOrBroadcast(player, Component.translatable(K_ERR_INVALID_STATE, state));
                }
                return null;
            }
            return trimmed;
        }

        // 预定义列表 → 必须匹配
        String canonical = rule.getQuickSwitches().stream()
                .filter(s -> s.equalsIgnoreCase(state))
                .findFirst()
                .orElse(null);
        if (canonical == null) {
            sendOrBroadcast(player, Component.translatable(K_ERR_INVALID_STATE, state));
            return null;
        }
        return canonical;
    }

    /** 多选：预定义列表必须逐项匹配；自定义列表放行任意非空项 */
    private static String canonicalMultiSelect(RuleType rule, String state, ServerPlayer player) {
        boolean custom = SwitchComponentType.isCustomList(rule.getQuickSwitches());

        java.util.Set<String> selected = new java.util.LinkedHashSet<>();
        for (String part : state.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;

            if (custom) {
                // ★ 自定义列表 → 直接接受
                selected.add(trimmed);
            } else {
                // 预定义列表 → 必须匹配
                String match = rule.getQuickSwitches().stream()
                        .filter(s -> s.equalsIgnoreCase(trimmed))
                        .findFirst()
                        .orElse(null);
                if (match == null) {
                    sendOrBroadcast(player, Component.translatable(K_ERR_INVALID_OPTION, trimmed));
                    return null;
                }
                selected.add(match);
            }
        }
        return String.join(",", selected);
    }

    // ============================================================
    // 组件构造
    // ============================================================

    private static MutableComponent getRuleName(RuleType ruleType) {
        MutableComponent nameComp = Component.empty()
                .append(Component.literal("§f"))
                .append(Component.translatable(ruleType.getDisplayName()))
                .append(Component.literal(" §f(" + ruleType.getRegisterName() + ")  "));

        return nameComp.withStyle(style -> style
                .withClickEvent(new ClickEvent.RunCommand(
                        String.join(" ", COMMAND_BASE, ruleType.getRegisterName())))
                .withHoverEvent(new HoverEvent.ShowText(
                        Component.translatable(ruleType.getDisplayInfo()))));
    }

    private static MutableComponent buildClickableButton(String text, int color,
                                                         String command, String hoverText) {
        MutableComponent component = Component.literal(text)
                .withStyle(style -> style.withColor(TextColor.fromRgb(color)))
                .withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand(command)));

        if (hoverText != null && !hoverText.isEmpty()) {
            component.withStyle(style -> style.withHoverEvent(
                    new HoverEvent.ShowText(Component.literal(hoverText))));
        }
        return component;
    }

    private static MutableComponent quickSwitchComponents(RuleType rule, String currentState) {
        MutableComponent result = Component.empty();
        List<String> switches = rule.getQuickSwitches();
        for (int i = 0; i < switches.size(); i++) {
            if (i > 0) result.append(" ");
            result.append(switchComponent(rule, switches.get(i), currentState));
        }
        return result;
    }

    private static MutableComponent switchComponent(RuleType rule, String option, String currentState) {
        boolean selected;
        String nextState;

        if (rule.isMultiSelect()) {
            selected = isSelectedIn(currentState, option);
            nextState = selected ? removeFrom(currentState, option) : appendTo(currentState, option);
        } else {
            selected = option.equalsIgnoreCase(currentState);
            nextState = option;
        }

        int color;
        if (rule.isMultiSelect()) {
            color = selected ? 0x2ecc71 : 0xAAAAAA;
        } else {
            boolean isOn = !SwitchComponentType.FALSE_STR.equalsIgnoreCase(option);
            color = selected ? (isOn ? 0x2ecc71 : 0xe74c3c) : 0xAAAAAA;
        }

        String displayName = SwitchComponentType.displayNameOf(option);
        String command = String.join(" ", COMMAND_BASE, rule.getRegisterName(), nextState);
        String hoverText = String.format(SwitchComponentType.INFO,
                SwitchComponentType.displayNameOf(currentState));
        return buildClickableButton("[" + displayName + "]", color, command, hoverText);
    }

    private static MutableComponent categoryDisplay(List<RuleCategoryType> ruleCategoryTypes) {
        MutableComponent result = Component.empty();
        for (RuleCategoryType type : ruleCategoryTypes) {
            String command = String.join(" ", COMMAND_BASE, "list", type.getRegisterName());
            MutableComponent component = buildClickableButton(
                    "[" + type.getDisplayName() + "]", 0x5dade2, command, null);
            result.append(component).append(" ");
        }
        return result;
    }

    private static MutableComponent divider() {
        return Component.literal("§6================================================\n")
                .withStyle(style -> style.withColor(TextColor.fromRgb(0x555555)));
    }

    private static boolean isSelectedIn(String state, String option) {
        if (state == null || state.isEmpty()) return false;
        for (String part : state.split(",")) {
            if (part.trim().equalsIgnoreCase(option)) return true;
        }
        return false;
    }

    private static String appendTo(String state, String option) {
        if (isSelectedIn(state, option)) return state;
        if (state == null || state.isEmpty()) return option;
        return state + "," + option;
    }

    private static String removeFrom(String state, String option) {
        if (state == null || state.isEmpty()) return "";
        List<String> parts = new java.util.ArrayList<>();
        for (String part : state.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty() && !trimmed.equalsIgnoreCase(option)) {
                parts.add(trimmed);
            }
        }
        return String.join(",", parts);
    }
}