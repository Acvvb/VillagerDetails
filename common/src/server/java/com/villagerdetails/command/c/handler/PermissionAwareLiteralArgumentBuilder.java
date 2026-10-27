package com.villagerdetails.command.c.handler;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;

/**
 * 构建 {@link PermissionAwareLiteralCommandNode} 的字面量构建器。
 *
 * <p>只在「补全是否要按权限过滤」这一点上与原版 {@link LiteralArgumentBuilder} 不同，
 * 其余（子命令、命令回调、requirement、forward）行为完全一致。
 *
 * <p>用法：把普通的 {@code LiteralArgumentBuilder} 打包成这个构建器再挂到父节点上，
 * 见 {@code CCommand}。
 */
public final class PermissionAwareLiteralArgumentBuilder<S> extends LiteralArgumentBuilder<S> {

    private PermissionAwareLiteralArgumentBuilder(String literal) {
        super(literal);
    }

    public static <S> PermissionAwareLiteralArgumentBuilder<S> literal(String literal) {
        return new PermissionAwareLiteralArgumentBuilder<>(literal);
    }

    @Override
    public LiteralCommandNode<S> build() {
        PermissionAwareLiteralCommandNode<S> node = new PermissionAwareLiteralCommandNode<>(
                getLiteral(), getCommand(), getRequirement(), getRedirect(), getRedirectModifier(), isFork());
        for (CommandNode<S> child : getArguments()) {
            node.addChild(child);
        }
        return node;
    }
}
