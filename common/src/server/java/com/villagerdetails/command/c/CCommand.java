package com.villagerdetails.command.c;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import com.villagerdetails.command.c.handler.PermissionAwareLiteralArgumentBuilder;
import com.villagerdetails.command.c.impl.reload.ReloadMapping;
import com.villagerdetails.command.c.server.RegisterServer;
import com.villagerdetails.event.type.ListenerType;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * {@code /ec c} 子命令树的构建者。
 *
 * <p>树的内容全部来自「注册表」而不是手写名单：{@link RuleType} 与 {@link ListenerType}
 * 各自实现 {@code RegisterTypeServer#getCommandObject()}，返回 {@code null} 表示这条规则
 * <b>没有独立子命令</b>（只是 {@code /ec <规则> <状态>} 的一个开关，例如
 * {@code PortalBindingEnhanced}）——这种直接跳过，不会在树里留下空节点。
 *
 * <p><b>为什么要在构建前自检</b>：Brigadier 的 {@code CommandNode#addChild} 遇到同名节点
 * 时会「静默合并」——只覆盖回调、合并子节点，而 {@code requirement} 是 final 字段、
 * 保留先注册的那份。于是两个对象返回同一个字面量时：后者的命令不会生效、requirement 被丢，
 * 现场表现为「某条规则的命令莫名消失 / 权限判错」，而且没有任何报错。
 * {@link #validateSubcommands(List)} 就是为了让这种情况在<b>启动期</b>直接炸掉，
 * 而不是等到玩家敲命令时才发现。
 */
public class CCommand {

    private static final Logger log = LogManager.getLogger(CCommand.class);

    /**
     * 一条要挂到 {@code /ec c} 下的子命令。
     *
     * @param owner        来源描述（枚举常量名），只用于日志
     * @param registerName 规则的注册名（{@link RuleType#getRegisterName()}）；
     *                     {@code null} 表示没有「注册名 ↔ 字面量」的对应关系（{@code reload}、
     *                     {@link ListenerType} 这类工具命令），跳过一致性检查
     * @param builder      已经构建好的字面量子树
     * @param requirement  规则自己的准入条件；{@code null} 表示不额外加条件
     */
    private record Subcommand(String owner,
                              String registerName,
                              LiteralArgumentBuilder<CommandSourceStack> builder,
                              Predicate<CommandSourceStack> requirement) {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> buildCSubcommand() {
        List<Subcommand> subcommands = collectSubcommands();

        // 先自检再挂树：重复字面量是启动期致命错误，不能等到 Brigadier 悄悄合并
        validateSubcommands(subcommands);

        LiteralArgumentBuilder<CommandSourceStack> c = Commands.literal("c");
        for (Subcommand sub : subcommands) {
            LiteralArgumentBuilder<CommandSourceStack> subBuilder = sub.builder();
            if (sub.requirement() != null) {
                subBuilder = subBuilder.requires(sub.requirement());
            }
            // 补全也要按权限过滤：没权限的规则 id 连名字都不该出现在 /ec c 的补全里
            c = c.then(permissionAware(subBuilder));
        }

        log.info("[CommandTree] /ec c 已挂载 {} 条子命令：{}", subcommands.size(),
                subcommands.stream().map(sub -> sub.builder().getLiteral()).toList());
        return c;
    }

    // ============================================================
    // 收集
    // ============================================================

    /**
     * 按「reload → RuleType → ListenerType」的固定顺序收集子命令，保证补全顺序稳定。
     *
     * <p>每个命令对象只调一次 {@code register()}：同一个构建器既用于自检、也直接挂进树里，
     * 不会为了校验把整棵子树建两遍。
     */
    private static List<Subcommand> collectSubcommands() {
        List<Subcommand> result = new ArrayList<>();

        // reload 不是规则：没有 registerName，跳过「注册名 ↔ 字面量」一致性检查
        result.add(new Subcommand("reload", null, ReloadMapping.INSTANCE.register(), null));

        for (RuleType type : RuleType.values()) {
            RegisterServer command = type.getCommandObject();
            if (command == null) {
                // 纯开关规则（例如 PortalBindingEnhanced）：没有独立子命令，跳过
                continue;
            }
            result.add(new Subcommand(type.name(), type.getRegisterName(),
                    command.register(), type.requirement()));
        }

        for (ListenerType type : ListenerType.values()) {
            RegisterServer command = type.getCommandObject();
            if (command == null) {
                continue;
            }
            result.add(new Subcommand(type.name(), null, command.register(), type.requirement()));
        }

        return result;
    }

    // ============================================================
    // 启动期自检
    // ============================================================

    /**
     * 子命令树自检（每次命令树重建都会跑：服务器启动、数据包重载、{@code /reload}）：
     *
     * <ol>
     *     <li><b>字面量唯一</b> —— 两个命令对象返回同一个字面量时，Brigadier 会把后一个节点
     *         静默合并进先注册的那个（只覆盖回调，{@code requirement} 保留先注册者）。
     *         这种「命令看起来消失了 / 权限判错」的问题极难排查，所以这里直接抛
     *         {@link IllegalStateException} 让启动失败，并在日志里点名是哪两条规则。</li>
     *     <li><b>注册名 ↔ 字面量一致</b> —— {@code RuleType.getRegisterName()} 决定
     *         {@code /ec <规则>} 的名字，{@code register()} 的返回值决定 {@code /ec c <规则>}
     *         的名字；两者不一致就叫「同物异名」（曾经的 {@code VillagerBatchBedBinding}
     *         vs {@code batchBedBinding}），这里打 WARN 提醒改掉。</li>
     * </ol>
     */
    private static void validateSubcommands(List<Subcommand> subcommands) {
        Map<String, Subcommand> byLiteral = new LinkedHashMap<>();

        for (Subcommand sub : subcommands) {
            String literal = sub.builder().getLiteral();
            if (literal == null || literal.isBlank()) {
                String message = "命令字面量为空：" + sub.owner() + " 的 register() 没有返回字面量";
                log.error("[CommandTree] {}", message);
                throw new IllegalStateException(message);
            }

            Subcommand previous = byLiteral.putIfAbsent(literal, sub);
            if (previous != null) {
                String message = "命令字面量冲突：\"" + literal + "\" 被多个规则重复注册（"
                        + previous.owner() + " 与 " + sub.owner() + "）。"
                        + "Brigadier 会把后一个节点静默合并进先注册的那个：后者的 requirement 会失效，"
                        + "它的选项也可能被覆盖。请给其中一个换独立字面量，或把它的 commandObject 置为 null。";
                log.error("[CommandTree] {}", message);
                throw new IllegalStateException(message);
            }

            if (sub.registerName() != null && !sub.registerName().equals(literal)) {
                log.warn("[CommandTree] 规则 {} 的注册名与命令字面量不一致：registerName=\"{}\", literal=\"{}\"；"
                                + "/ec {} 与 /ec c {} 会是两个不同的名字（补全里会各出现一次）",
                        sub.owner(), sub.registerName(), literal, sub.registerName(), literal);
            }
        }
    }

    // ============================================================
    // 节点包装
    // ============================================================

    /**
     * 把普通字面量构建器换成「补全也按权限过滤」的版本。
     *
     * <p>Brigadier 原版的字面量补全<b>不判 requirement</b>：父节点走得到、字面量名字对得上，
     * 它就会被补全出来 —— 于是没权限的玩家能看到 {@code /ec c <规则 id>}，
     * 但敲进去执行不了、后面也没有补全。换成
     * {@link PermissionAwareLiteralArgumentBuilder} 构建的节点后，
     * 没权限的字面量连名字都不会出现在补全列表里（执行仍然由 requirement 把关）。
     *
     * <p>命令回调、子命令、requirement、forward 原样带过去，行为与原来完全一致。
     */
    private static LiteralArgumentBuilder<CommandSourceStack> permissionAware(
            LiteralArgumentBuilder<CommandSourceStack> source) {
        PermissionAwareLiteralArgumentBuilder<CommandSourceStack> copy =
                PermissionAwareLiteralArgumentBuilder.literal(source.getLiteral());
        copy.requires(source.getRequirement());
        if (source.getCommand() != null) {
            copy.executes(source.getCommand());
        }
        if (source.getRedirect() != null) {
            copy.forward(source.getRedirect(), source.getRedirectModifier(), source.isFork());
        }
        for (CommandNode<CommandSourceStack> child : source.getArguments()) {
            copy.then(child);
        }
        return copy;
    }
}
