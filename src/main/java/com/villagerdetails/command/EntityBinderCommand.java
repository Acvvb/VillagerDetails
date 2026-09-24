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
import com.villagerdetails.config.WorldBindingConfig;
import com.villagerdetails.rule.type.RuleCategoryType;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.*;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static com.villagerdetails.util.SendMessengerUtils.sendOrBroadcast;
import static com.villagerdetails.command.SwitchComponentType.DISABLE;
import static com.villagerdetails.command.SwitchComponentType.ENABLE;

public class EntityBinderCommand {

    //注意拼装结果
    public final static String COMMAND_BASE = "/" + VillagerDetails.MOD_ID;

    /** 规则名称的 Tab 补全，使用 registerName 作为补全源 */
    private static final SuggestionProvider<CommandSourceStack> RULE_SUGGESTER = (_, builder) ->
            SharedSuggestionProvider.suggest(
                    Arrays.stream(RuleType.values())
                            .map(RuleType::getRegisterName)
                            .collect(Collectors.toList()),
                    builder
            );

    /** 分类名称的 Tab 补全，使用 registerName 作为补全源 */
    private static final SuggestionProvider<CommandSourceStack> CATEGORY_SUGGESTER = (_, builder) ->
            SharedSuggestionProvider.suggest(
                    Arrays.stream(RuleCategoryType.values())
                            .map(RuleCategoryType::getRegisterName)
                            .collect(Collectors.toList()),
                    builder
            );
    private static final Logger log = LogManager.getLogger(EntityBinderCommand.class);

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(VillagerDetails.MOD_ID)
                // 无参数时，列出所有规则
                .executes(EntityBinderCommand::helpRules)
                .then(Commands.literal("list")
                        .executes(EntityBinderCommand::listRules)  // 不带分类参数，列出全部
                        .then(Commands.argument("category", StringArgumentType.word())
                                .suggests(CATEGORY_SUGGESTER)
                                .executes(EntityBinderCommand::listRulesByCategory)
                        )
                        .then(Commands.literal("enable")
                                .executes(ctx -> listRulesByStatus(ctx,true))
                        )
                        .then(Commands.literal("disable")
                                .executes(ctx -> listRulesByStatus(ctx,false))
                        )
                )
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
     * 列出指定状态的规则
     * @param ctx 命令上下文
     * @param enabled true 表示列出已开启的规则，false 表示列出已关闭的规则
     */
    private static int listRulesByStatus(CommandContext<CommandSourceStack> ctx, boolean enabled) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        List<RuleType> filteredRules = Arrays.stream(RuleType.values())
                .filter(rule -> RuleCache.isEnabled(rule) == enabled)
                .collect(Collectors.toList());
        String statusText = enabled ? "已经开启" : "已经关闭";
        sendOrBroadcast(player, Component.literal("§6============== " + statusText + " 规则列表 =============== "));
        if (filteredRules.isEmpty()) {
            sendOrBroadcast(player, Component.nullToEmpty(("没有更多"+ statusText + "规则")));
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
        sendOrLog(player,mutableComponent);
        return 1;
    }

    /** 列出所有规则（不带分类参数） */
    private static int listRules(CommandContext<CommandSourceStack> context) {
        return listRulesByCategory(context, null);
    }

    /** 按分类列出规则（带分类参数） */
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
                    .append(switchComponent(rule,ENABLE,currentState))
                    .append(" ")
                    .append(switchComponent(rule,DISABLE,currentState))
            );
        }
    }

    /** 核心方法：按分类过滤规则列表 */
    private static int listRulesByCategory(CommandContext<CommandSourceStack> context, RuleCategoryType targetCategory) {
        ServerPlayer player = context.getSource().getPlayer();
        if (targetCategory == null) {
            sendOrBroadcast(player,Component.literal("§6========== 控制器规则列表（全部） =========="));
            sendRuleList(player, List.of(RuleType.values()));
        } else {
            sendOrBroadcast(player,Component.literal("§6========== 规则列表: " + targetCategory.getDisplayName() + " =========="));
            sendRuleList(player,RuleType.getRuleTypeListByRuleCategoryType(targetCategory));
        }
        return 1;
    }

    private static int toggleRule(CommandContext<CommandSourceStack> context, Boolean state) {

        ServerPlayer player = context.getSource().getPlayer();
        String ruleName = StringArgumentType.getString(context, "rule");
        RuleType rule = RuleType.getRuleTypeByRegisterName(ruleName);

        if (rule == null) {
            sendOrBroadcast(player,Component.literal("§c未知的规则名称: " + ruleName));
            return 0;
        }

        // 未指定 state 时，显示规则详情而非切换开关
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
                    .append(switchComponent(rule,ENABLE,currentState))
                    .append(" ")
                    .append(switchComponent(rule,DISABLE,currentState));
            sendOrLog(player,mutableComponent);
            return 1;
        }

        boolean targetState = state;
        RuleCache.setEnabled(rule, targetState);

        WorldBindingConfig config = WorldBindingConfig.getOrCreate(context.getSource().getServer());
        config.setBindingState(rule.getRegisterName(), targetState);

        sendOrBroadcast(player,Component.literal(
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

    // ==================== 公共构建方法 ====================

    /**
     * 构建一个可点击的文本组件（按钮）
     *
     * @param text       显示的文本
     * @param color      文本颜色（RGB 整数值）
     * @param command    点击时执行的命令
     * @param hoverText  悬停时显示的提示文本（可为 null）
     * @return 构建好的 MutableComponent
     */
    private static MutableComponent buildClickableButton(String text, int color, String command, String hoverText) {
        MutableComponent component = Component.literal(text)
                .withStyle(style -> style.withColor(TextColor.fromRgb(color)))
                .withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand(command)));

        // 悬停文本可选
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
            color = targetState ? 0x2ecc71 : 0xe74c3c; // 匹配时：开启绿 / 关闭红
        } else {
            color = 0xAAAAAA; // 不匹配时：灰色
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
                    0x5dade2,   // 统一绿色
                    command,
                    null        // 不需要悬停提示
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
        }else {
            log.info(mutableComponent.toString());
        }
    }

}