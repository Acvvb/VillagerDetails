package com.villagerdetails.command.c.impl.suggestion;

import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.villagerdetails.command.c.server.StateSuggestionServer;
import net.minecraft.commands.CommandSourceStack;

import java.util.List;
import java.util.Locale;

/**
 * 「可采集」规则的补全：按逗号分隔的多选值，逐段补全「不可采集」的方块注册 id
 * （挖不掉的方块，如基岩；或原版挖了不掉的方块，如刷怪笼）。
 * <p>
 * 补全方式与 {@link EntityIdSuggestionServer} 一致：输入完整 id 后提示逗号继续输入下一个。
 */
public final class UncollectableBlockSuggestionServer implements StateSuggestionServer {

    public static final UncollectableBlockSuggestionServer INSTANCE = new UncollectableBlockSuggestionServer();

    private UncollectableBlockSuggestionServer() {
    }

    private static final SuggestionProvider<CommandSourceStack> SUGGESTER = (ctx, builder) -> {
        String remaining = builder.getRemaining();

        // 以最后一个逗号为界，拆出「已确认的前缀」与「当前正在输入的 token」
        int lastComma = remaining.lastIndexOf(',');
        String prefix = lastComma >= 0 ? remaining.substring(0, lastComma + 1) : "";
        String current = remaining.substring(lastComma + 1);
        String currentLower = current.toLowerCase(Locale.ROOT);

        List<String> ids = BlockSuggestionData.uncollectableIds();

        // 1) 方块注册 id 补全：按当前 token 前缀匹配
        for (String id : ids) {
            if (!id.toLowerCase(Locale.ROOT).startsWith(currentLower)) continue;
            String full = prefix + id;
            if (!full.equals(remaining)) {
                builder.suggest(full);
            }
        }

        // 2) 当前 token 已是完整方块 id → 补一个逗号，方便继续输入下一个
        if (ids.contains(currentLower)) {
            builder.suggest(remaining + ",");
        }

        return builder.buildFuture();
    };

    @Override
    public SuggestionProvider<CommandSourceStack> getStateSuggester() {
        return SUGGESTER;
    }
}
