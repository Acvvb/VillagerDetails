package com.villagerdetails.command.c.impl.suggestion;

import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * 方块注册 id 的静态缓存，供 /ec c 方块命令的补全复用。
 */
public final class BlockSuggestionData {

    private BlockSuggestionData() {
    }

    private static volatile boolean initialized = false;
    private static volatile List<String> allBlockIds = List.of();

    /** 所有方块注册 id（不做任何可用性过滤）。 */
    public static List<String> allBlockIds() {
        ensureInit();
        return allBlockIds;
    }

    public static final SuggestionProvider<CommandSourceStack> ALL_BLOCK_ID_SUGGESTER =
            (_, builder) -> SharedSuggestionProvider.suggest(allBlockIds(), builder);

    private static synchronized void ensureInit() {
        if (initialized) return;

        List<String> all = new ArrayList<>();
        for (Identifier id : BuiltInRegistries.BLOCK.keySet()) {
            all.add(id.toString());
        }
        allBlockIds = List.copyOf(all);
        initialized = true;
    }
}
