package com.villagerdetails.command.c.handler;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.RedirectModifier;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;

import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * 会按 {@link #canUse(Object)} 过滤补全的字面量节点。
 *
 * <p>Brigadier 原版的 {@link LiteralCommandNode#listSuggestions} <b>不看权限</b>：
 * 只要父节点被走到，字面量本身就会被补全出来，哪怕它的 requirement 为 false ——
 * 于是玩家能看到 {@code /ec c <没有权限的规则>}，但敲进去执行不了、后面也没有补全。
 *
 * <p>这里在补全这一层再判一次：没权限的字面量<b>连名字都不出现在补全列表里</b>，
 * 解析/执行仍然由 requirement 把关，两边结论一致。
 */
public final class PermissionAwareLiteralCommandNode<S> extends LiteralCommandNode<S> {

    public PermissionAwareLiteralCommandNode(String literal, Command<S> command, Predicate<S> requirement,
                                             CommandNode<S> redirect, RedirectModifier<S> modifier, boolean forks) {
        super(literal, command, requirement, redirect, modifier, forks);
    }

    @Override
    public CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
        if (!canUse(context.getSource())) {
            return Suggestions.empty();
        }
        return super.listSuggestions(context, builder);
    }
}
