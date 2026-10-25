package com.villagerdetails.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.villagerdetails.VillagerDetails;
import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.command.c.server.StateSuggestionServer;
import com.villagerdetails.lang.ServerTranslations;
import com.villagerdetails.rule.RuleChangeService;
import com.villagerdetails.rule.RuleSwitches;
import com.villagerdetails.rule.type.RuleCategoryType;
import com.villagerdetails.rule.type.RuleType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static com.villagerdetails.cache.RuleCache.isEnabled;
import static com.villagerdetails.command.CommandConstants.*;
import static com.villagerdetails.command.c.CCommand.buildCSubcommand;
import static com.villagerdetails.rule.type.RuleType.SETTING_EC_COMMAND_PERMISSION;
import static com.villagerdetails.util.SendMessengerUtils.sendOrBroadcast;

/**
 * 规则管理命令的注册与执行。
 *
 * <p>命令树：
 * <pre>
 *   /vd                             显示分类帮助
 *   /vd list                        列出全部规则
 *   /vd list &lt;category&gt;          按分类列出规则
 *   /vd list enable|disable         按启用/禁用状态列出规则
 *   /vd &lt;rule&gt;                    显示某条规则的当前状态与快捷开关
 *   /vd &lt;rule&gt; &lt;state&gt;          修改某条规则的状态
 *   /vd c ...                       子命令（由 {@link com.villagerdetails.command.c.CCommand} 提供）
 * </pre>
 *
 * <p>所有写操作都走 {@link RuleChangeService#change}，与客户端界面改规则共用同一个入口。
 */
public class RuleCommand {

    private static final Logger LOGGER = LogManager.getLogger(RuleCommand.class);

    /** 唯一实例：registerHook() 的 lambda 通过它调实例方法 register(dispatcher) */
    private static final RuleCommand INSTANCE = new RuleCommand();

    /**
     * {@link CommandRegistrationCallback} 是否已挂载。
     *
     * <p>挂两次的后果是回调触发时 {@link #register(CommandDispatcher)} 被调两次，
     * Brigadier 会抛 {@code command already exists}，所以这里必须防重。
     */
    private static boolean commandHookRegistered;

    private RuleCommand() {
    }

    // ============================================================
    // 语言键
    // ============================================================

    private static final String K_STATUS_ENABLED = "command.entity_binder.status.enabled";
    private static final String K_STATUS_DISABLED = "command.entity_binder.status.disabled";

    private static final String K_LIST_HEADER_STATUS = "command.entity_binder.list.header.status";
    private static final String K_LIST_EMPTY_STATUS = "command.entity_binder.list.empty.status";
    private static final String K_LIST_HEADER_ALL = "command.entity_binder.list.header.all";
    private static final String K_LIST_HEADER_CATEGORY = "command.entity_binder.list.header.category";

    private static final String K_HELP_CATEGORIES = "command.entity_binder.help.categories";

    private static final String K_ERR_UNKNOWN_CATEGORY = "command.entity_binder.error.unknown_category";
    private static final String K_ERR_UNKNOWN_RULE = "command.entity_binder.error.unknown_rule";

    private static final String K_TOGGLE_CATEGORY = "command.entity_binder.toggle.category_label";
    private static final String K_TOGGLE_VALUE = "command.entity_binder.toggle.value_label";
    private static final String K_TOGGLE_SUCCESS = "command.entity_binder.toggle.success";
    private static final String K_TOGGLE_HOVER = "command.entity_binder.toggle.hover";

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
        StateSuggestionServer stateSuggester = rule.getStateSuggestionServer();
        if (stateSuggester != null) {
            return stateSuggester.getStateSuggester().getSuggestions(ctx, builder);
        }
        return SharedSuggestionProvider.suggest(rule.getQuickSwitches(), builder);
    };

    // ============================================================
    // 注册
    // ============================================================

    /**
     * 把「命令注册」挂到 {@link CommandRegistrationCallback} 上。
     *
     * <p>由集中初始化入口调用一次；重复调用只打 warn、不重复挂载。
     */
    public static synchronized void registerHook() {
        if (commandHookRegistered) {
            LOGGER.warn("CommandRegistrationCallback 重复注册被忽略");
            return;
        }
        commandHookRegistered = true;
        CommandRegistrationCallback.EVENT.register((dispatcher, _, _) -> INSTANCE.register(dispatcher));
    }

    /**
     * 往 Brigadier 的 {@link CommandDispatcher} 里注册整棵规则命令树。
     *
     * <p>由 {@link #registerHook()} 的回调在命令注册阶段调用一次；
     * 命令树的构建全部使用静态方法引用（{@code RuleCommand::xxx}），不依赖实例状态。
     */
    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(VillagerDetails.BASE_COMMAND)
                .executes(ctx -> {
                    if (!hasBasePermission(ctx.getSource())) {
                        return 0;
                    }
                    return helpRules(ctx);
                })
                .then(Commands.literal("list")
                        .requires(RuleCommand::hasBasePermission)
                        .executes(RuleCommand::listRules)
                        .then(Commands.argument("category", StringArgumentType.word())
                                .suggests(CATEGORY_SUGGESTER)
                                .executes(RuleCommand::listRulesByCategory))
                        .then(Commands.literal("enable")
                                .executes(ctx -> listRulesByStatus(ctx, true)))
                        .then(Commands.literal("disable")
                                .executes(ctx -> listRulesByStatus(ctx, false))))
                .then(buildCSubcommand())
                .then(Commands.argument("rule", StringArgumentType.word())
                        .requires(RuleCommand::hasBasePermission)
                        .suggests(RULE_SUGGESTER)
                        .executes(ctx -> toggleRule(ctx, null))
                        .then(Commands.argument("state", StringArgumentType.greedyString())
                                .suggests(STATE_SUGGESTER)
                                .executes(ctx -> toggleRule(ctx, StringArgumentType.getString(ctx, "state")))));
        dispatcher.register(root);
    }

    /**
     * 权限判定：执行命令的人是否拥有「规则命令」所需的权限等级。
     *
     * <p>等级来自规则 {@link RuleType#SETTING_EC_COMMAND_PERMISSION} 的配置值；
     * 配置为空或非法时回退到 {@link PermissionLevel#OWNERS}（管理员级别）。
     *
     * @param src 命令执行来源（玩家 / 命令方块 / 控制台）
     * @return {@code true} 表示有权执行
     */
    private static boolean hasBasePermission(CommandSourceStack src) {
        PermissionLevel permissionLevel = SwitchComponentType.permissionLevelOf(
                RuleCache.getState(SETTING_EC_COMMAND_PERMISSION));
        return src.checkPermission(PERM_BASE,
                permissionLevel != null ? permissionLevel : PermissionLevel.OWNERS);
    }

    // ============================================================
    // list 相关
    // ============================================================

    /**
     * 按启用/禁用状态列出规则（{@code /vd list enable}、{@code /vd list disable}）。
     *
     * <p>只保留状态匹配的规则；没有匹配项时发一条空列表提示。
     *
     * @param enabled {@code true} 列启用的规则，{@code false} 列禁用的规则
     * @return Brigadier 约定返回值：1 表示成功
     * @throws CommandSyntaxException 命令来源不是玩家时抛出
     */
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

    /**
     * 根命令（{@code /vd}）的默认行为：显示分类帮助。
     *
     * <p>只发一行分类按钮列表，玩家点击即可跳到对应分类的规则列表。
     */
    private static int helpRules(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        MutableComponent mutableComponent = Component.empty();
        mutableComponent.append(Component.translatable(K_HELP_CATEGORIES));
        mutableComponent.append(categoryDisplay(List.of(RuleCategoryType.values())));
        sendOrBroadcast(player, mutableComponent);
        return 1;
    }

    /**
     * 列出全部规则（{@code /vd list}）。
     *
     * <p>等价于不带分类参数的 {@link #listRulesByCategory(CommandContext, RuleCategoryType)}。
     */
    private static int listRules(CommandContext<CommandSourceStack> context) {
        return listRulesByCategory(context, null);
    }

    /**
     * 按分类列出规则（{@code /vd list <category>}）。
     *
     * <p>解析分类名 → 委托给双参版本；分类名不识别时发一条错误提示并返回 0。
     */
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

    /**
     * 逐条发送规则列表，每条都走 {@code SendMessengerUtils}。
     *
     * <p>每条规则发一行「规则名 + 快捷开关按钮」，玩家点击按钮即可直接切换状态。
     *
     * @param player       接收者
     * @param ruleTypeList 要展示的规则
     */
    public static void sendRuleList(ServerPlayer player, List<RuleType> ruleTypeList) {
        for (RuleType rule : ruleTypeList) {
            String currentState = RuleCache.getState(rule);
            Component line = Component.empty()
                    .append("\n")
                    .append(getRuleName(rule))
                    .append("\n")
                    .append(quickSwitchComponents(rule, currentState));
            sendOrBroadcast(player, line);
        }
    }

    /**
     * 按分类列出规则的实际实现。
     *
     * @param targetCategory {@code null} 表示列全部规则；非 null 表示只列该分类
     */
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

    /**
     * 显示或修改某条规则的状态（{@code /vd <rule>}、{@code /vd <rule> <state>}）。
     *
     * <ul>
     *     <li>{@code state == null}：只展示当前状态、分类、描述和快捷开关按钮，不修改；</li>
     *     <li>{@code state != null}：走 {@link RuleChangeService#change} 校验并写入，
     *         非法状态 / 非法选项会发错误提示并返回 0。</li>
     * </ul>
     *
     * <p>写操作和客户端界面改规则共用同一个入口，保证两边行为一致。
     *
     * @param state 目标状态字符串；{@code null} 表示只查询不修改
     * @return Brigadier 约定返回值：1 成功，0 参数非法
     */
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
            sendOrBroadcast(player, mc);
            return 1;
        }

        // 校验 + 写入：和收包端（客户端界面改规则）走同一个入口，两边不会一个拦一个放
        RuleSwitches.Canonical result = RuleChangeService.change(rule, state, context.getSource());
        if (result instanceof RuleSwitches.Canonical.BadState(String raw)) {
            sendOrBroadcast(player, Component.translatable(RULE_ERROR_INVALID_STATE, raw));
            return 0;
        }
        if (result instanceof RuleSwitches.Canonical.BadOption(String option)) {
            sendOrBroadcast(player, Component.translatable(RULE_ERROR_INVALID_OPTION, option));
            return 0;
        }
        String canonical = ((RuleSwitches.Canonical.Ok) result).state();

        sendOrBroadcast(player, Component.translatable(
                K_TOGGLE_SUCCESS,
                Component.translatable(rule.getDisplayName()),
                rule.getRegisterName(),
                Component.literal(canonical)));
        return 1;
    }

    // ============================================================
    // 组件构造
    // ============================================================

    /**
     * 构造一条规则的可点击名称组件：{@code [显示名 (registerName)]}。
     *
     * <p>悬停显示规则描述，点击执行 {@code /vd <registerName>} 直接查看该规则。
     */
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

    /**
     * 构造一个可点击按钮组件。
     *
     * @param text      按钮文字（含方括号，如 {@code [enable]}）
     * @param color     文字颜色（RGB）
     * @param command   点击后执行的命令
     * @param hoverText 悬停提示；{@code null} 或空串表示不加悬停
     */
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

    /**
     * 构造一条规则的整行快捷开关按钮，按钮之间用空格分隔。
     *
     * <p>按钮集合来自 {@link RuleType#getQuickSwitches()}，每个按钮的显示名 / 颜色
     * 都由 {@link RuleSwitches} 统一决定，与客户端界面保持一致。
     */
    private static MutableComponent quickSwitchComponents(RuleType rule, String currentState) {
        MutableComponent result = Component.empty();
        List<String> switches = rule.getQuickSwitches();
        for (int i = 0; i < switches.size(); i++) {
            if (i > 0) result.append(" ");
            result.append(switchComponent(rule, switches.get(i), currentState));
        }
        return result;
    }

    /**
     * 构造单个快捷开关按钮。
     *
     * <p>点击执行 {@code /vd <rule> <option>}；颜色 / 显示名 / 悬停文字全部来自
     * {@link RuleSwitches}，客户端界面（{@code ServerRuleRow}）用的是同一套 —— 两边永远一致。
     */
    private static MutableComponent switchComponent(RuleType rule, String option, String currentState) {
        String command = String.join(" ", COMMAND_BASE, rule.getRegisterName(), option);
        String hoverText = ServerTranslations.resolve(
                Component.translatable(K_TOGGLE_HOVER, RuleSwitches.currentDisplay(currentState)));
        return buildClickableButton("[" + RuleSwitches.displayNameOf(option) + "]",
                RuleSwitches.colorOf(option, currentState), command, hoverText);
    }

    /**
     * 构造分类按钮行：每个分类一个按钮，点击执行 {@code /vd list <category>}。
     *
     * @param ruleCategoryTypes 要展示的分类列表
     */
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

    /**
     * 构造一条分隔线（在「规则详情」输出里用，把上一次输出和本次输出隔开）。
     */
    private static MutableComponent divider() {
        return Component.literal("§6================================================\n")
                .withStyle(style -> style.withColor(TextColor.fromRgb(0x555555)));
    }

}