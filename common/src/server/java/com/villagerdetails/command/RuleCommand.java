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
import com.villagerdetails.rule.RuleChangeService;
import com.villagerdetails.rule.RuleSwitches;
import com.villagerdetails.rule.type.RuleCategoryType;
import com.villagerdetails.rule.type.RuleType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
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
 *
 * <p>聊天组件（规则名、按钮、分隔线、整行列表 / 详情）的拼装全部在
 * {@link RuleMessageComponents}，这里只负责取数据、发消息。
 */
public class RuleCommand {

    private static final Logger log = LogManager.getLogger(RuleCommand.class);

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

    private static final String K_ERR_UNKNOWN_CATEGORY = "command.entity_binder.error.unknown_category";
    private static final String K_ERR_UNKNOWN_RULE = "command.entity_binder.error.unknown_rule";

    private static final String K_TOGGLE_SUCCESS = "command.entity_binder.toggle.success";

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
            log.warn("CommandRegistrationCallback 重复注册被忽略");
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
        sendOrBroadcast(player, RuleMessageComponents.helpCategories());
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
            sendOrBroadcast(player, RuleMessageComponents.ruleListLine(rule, RuleCache.getState(rule)));
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
            sendOrBroadcast(player, RuleMessageComponents.ruleDetail(rule, RuleCache.getState(rule)));
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
}
