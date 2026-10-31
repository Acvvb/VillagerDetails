package com.villagerdetails.command.c.impl.base;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * 「按 ID 补全」的通用 {@link SuggestionProvider}：按下 Tab 时把当前存在的 ID 列出来。
 *
 * <p>ID 用 {@link Supplier} 而不是固定列表传进来，因为命令树只在注册时搭一次，
 * 而 ID 集合会随着 {@code bind} / {@code delete} 变化 —— 每次按 Tab 都重新问一遍
 * {@code Config} 要最新的快照，删掉 {@code #2} 之后补全里就不会再有 {@code 2}。
 *
 * <p>过滤用的是 Brigadier 的 {@link SuggestionsBuilder#getRemaining()}：
 * 玩家已经敲了 {@code delete 1} 时只剩 {@code "1"}，只会补出以 1 开头的 ID；
 * 还没敲时 remaining 为空，全部列出。
 *
 * <p>返回值必须是 {@link CompletableFuture}：这套 API 允许异步补全（查数据库之类），
 * 我们只是内存里读个集合，所以直接 {@code buildFuture()} 立即完成。
 */
public class IdSuggestionProvider implements SuggestionProvider<CommandSourceStack> {

    private final Supplier<List<String>> idsSupplier;

    public IdSuggestionProvider(Supplier<List<String>> idsSupplier) {
        this.idsSupplier = idsSupplier;
    }

    @Override
    public CompletableFuture<Suggestions> getSuggestions(CommandContext<CommandSourceStack> context,
                                                         SuggestionsBuilder builder) {
        List<String> ids = idsSupplier.get();
        if (ids != null) {
            String remaining = builder.getRemaining();
            ids.stream()
                    .filter(id -> id != null && id.startsWith(remaining))
                    .forEach(builder::suggest);
        }
        return builder.buildFuture();
    }
}
