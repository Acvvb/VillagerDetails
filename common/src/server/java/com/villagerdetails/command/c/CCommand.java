package com.villagerdetails.command.c;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import com.villagerdetails.command.c.impl.reload.ReloadMapping;
import com.villagerdetails.command.c.handler.PermissionAwareLiteralArgumentBuilder;
import com.villagerdetails.command.c.server.RegisterServer;
import com.villagerdetails.command.c.server.RegisterTypeServer;
import com.villagerdetails.event.type.ListenerType;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

public class CCommand {

    public static LiteralArgumentBuilder<CommandSourceStack> buildCSubcommand() {
        LiteralArgumentBuilder<CommandSourceStack> c = Commands.literal("c");

        c = c.then(permissionAware(ReloadMapping.INSTANCE.register()));

        List<RegisterTypeServer> all = new ArrayList<>();
        all.addAll(Arrays.asList(ListenerType.values()));
        all.addAll(Arrays.asList(RuleType.values()));

        for (RegisterTypeServer item : all) {
            RegisterServer rs = item.getCommandObject();
            if (rs == null) continue;
            LiteralArgumentBuilder<CommandSourceStack> sub = rs.register();
            Predicate<CommandSourceStack> req = item.requirement();
            if (req != null) sub = sub.requires(req);
            // 补全也要按权限过滤：没权限的规则 id 连名字都不该出现在 /ec c 的补全里
            c = c.then(permissionAware(sub));
        }

        return c;
    }

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
