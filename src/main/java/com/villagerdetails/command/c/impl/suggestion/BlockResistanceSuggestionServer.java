package com.villagerdetails.command.c.impl.suggestion;

import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.command.c.server.StateSuggestionServer;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.Identifier;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 「挖掘抗性」规则的补全：填写格式为 {@code 方块注册id=抗性}，多个用英文逗号分隔。
 * <p>
 * 在 {@code =} 之前补全「可采集」的方块 id（挖了会掉落的方块），输入完整 id 后提示 {@code =}；
 * 在 {@code =} 之后补全常用抗性数值，输入合法数值后提示逗号继续下一个。
 * <p>
 * 可采集方块集合 = 原版可采集方块 ∪ 当前「可采集」规则已配置的方块，从而支持「先把基岩设为可采集，再给它设抗性」。
 */
public final class BlockResistanceSuggestionServer implements StateSuggestionServer {

    public static final BlockResistanceSuggestionServer INSTANCE = new BlockResistanceSuggestionServer();

    private BlockResistanceSuggestionServer() {
    }

    private static final List<String> VALUE_SUGGESTIONS = List.of("0.5", "1.0", "2.0", "3.0", "5.0", "10.0", "50.0");

    private static final SuggestionProvider<CommandSourceStack> SUGGESTER = (ctx, builder) -> {
        String remaining = builder.getRemaining();

        // 以最后一个逗号为界，拆出「已确认的前缀」与「当前正在输入的 token」
        int lastComma = remaining.lastIndexOf(',');
        String prefix = lastComma >= 0 ? remaining.substring(0, lastComma + 1) : "";
        String current = remaining.substring(lastComma + 1);

        int eq = current.indexOf('=');
        if (eq < 0) {
            suggestBlockId(builder, remaining, prefix, current);
        } else {
            suggestValue(builder, remaining, prefix, current, eq);
        }

        return builder.buildFuture();
    };

    /** 在 {@code =} 之前：补全可采集方块 id，完整 id 后提示 {@code =}。 */
    private static void suggestBlockId(com.mojang.brigadier.suggestion.SuggestionsBuilder builder,
                                       String remaining, String prefix, String current) {
        String currentLower = current.toLowerCase(Locale.ROOT);
        Set<String> ids = eligibleIds();

        for (String id : ids) {
            if (!id.toLowerCase(Locale.ROOT).startsWith(currentLower)) continue;
            String full = prefix + id;
            if (!full.equals(remaining)) {
                builder.suggest(full);
            }
        }

        if (ids.contains(currentLower)) {
            builder.suggest(remaining + "=");
        }
    }

    /** 在 {@code =} 之后：补全常用抗性数值，合法数值后提示逗号继续。 */
    private static void suggestValue(com.mojang.brigadier.suggestion.SuggestionsBuilder builder,
                                     String remaining, String prefix, String current, int eq) {
        String blockPart = current.substring(0, eq).trim();
        String valuePart = current.substring(eq + 1);

        if (!isEligibleBlock(blockPart)) return;

        for (String v : VALUE_SUGGESTIONS) {
            if (v.startsWith(valuePart)) {
                builder.suggest(prefix + blockPart + "=" + v);
            }
        }

        if (isFloat(valuePart)) {
            builder.suggest(remaining + ",");
        }
    }

    /** 可采集方块 id 集合（小写），含原版可采集方块与「可采集」规则当前配置的方块。 */
    private static Set<String> eligibleIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (String id : BlockSuggestionData.collectableIds()) {
            ids.add(id.toLowerCase(Locale.ROOT));
        }
        String collectableState = RuleCache.getState(RuleType.BLOCK_COLLECTABLE);
        if (collectableState != null) {
            for (String part : collectableState.split(",")) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) continue;
                Identifier id = Identifier.tryParse(trimmed);
                if (id != null) {
                    ids.add(id.toString().toLowerCase(Locale.ROOT));
                }
            }
        }
        return ids;
    }

    private static boolean isEligibleBlock(String blockPart) {
        Identifier id = Identifier.tryParse(blockPart);
        if (id == null) return false;
        return eligibleIds().contains(id.toString().toLowerCase(Locale.ROOT));
    }

    private static boolean isFloat(String text) {
        if (text == null || text.isEmpty()) return false;
        try {
            Float.parseFloat(text);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @Override
    public SuggestionProvider<CommandSourceStack> getStateSuggester() {
        return SUGGESTER;
    }
}
