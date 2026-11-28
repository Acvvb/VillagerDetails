package com.villagerdetails.command.c.impl.suggestion;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.villagerdetails.util.piston.VanillaPistonRules;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 方块注册 id 的静态缓存与「原版值」补全，供 /ec c 方块命令复用。
 */
public final class BlockSuggestionData {

    private BlockSuggestionData() {
    }

    private static volatile boolean initialized = false;
    private static volatile List<String> allBlockIds = List.of();

    /**
     * 所有方块注册 id（不做任何可用性过滤）。
     */
    public static List<String> allBlockIds() {
        ensureInit();
        return allBlockIds;
    }

    public static final SuggestionProvider<CommandSourceStack> ALL_BLOCK_ID_SUGGESTER =
            (_, builder) -> SharedSuggestionProvider.suggest(allBlockIds(), builder);

    // ==============================================================
    // 原版值补全（/ec c <rule> <block> <value> 的 value 位置）
    // 仅补全「原版对应的值」：
    //   可采集     → true / false
    //   挖掘抗性   → 方块id=原版破坏时间
    //   爆炸抗性   → 方块id=原版爆炸抗性
    // ==============================================================

    /**
     * 方块可采集：原版可破坏（defaultDestroyTime &gt;= 0）就补全 true，否则 false。
     */
    public static final SuggestionProvider<CommandSourceStack> VANILLA_COLLECTABLE_SUGGESTER =
            vanillaValue(block -> String.valueOf(block.defaultDestroyTime() >= 0f));

    /**
     * 方块挖掘抗性：补全 {@code 方块id=原版破坏时间}。
     */
    public static final SuggestionProvider<CommandSourceStack> VANILLA_DESTROY_TIME_SUGGESTER =
            vanillaValue(block -> blockKey(block) + "=" + block.defaultDestroyTime());

    /**
     * 方块爆炸抗性：补全 {@code 方块id=原版爆炸抗性}。
     */
    public static final SuggestionProvider<CommandSourceStack> VANILLA_EXPLOSION_RESISTANCE_SUGGESTER =
            vanillaValue(block -> blockKey(block) + "=" + block.getExplosionResistance());

    /**
     * 方块活塞搬运：补全「原版能不能被活塞搬动这个方块」。
     *
     * <p>和可采集那条一个用处：设成这个值等于「和原版一致」，命令会直接把配置删掉。
     *
     * <p>用 {@link VanillaPistonRules#isPullable} 而不是 {@code isPushable}：这条规则的名单同时
     * 管「推 / 拉 / 被粘性方块带走」，而「只推不拉」（{@code PUSH_ONLY} / {@code PUSH}）和
     * 「一推就碎」（{@code DESTROY}）的方块推得动却带不走，只有前者才能代表「原版能搬动它」。
     */
    public static final SuggestionProvider<CommandSourceStack> VANILLA_PISTON_MOVABLE_SUGGESTER =
            vanillaValue(block -> String.valueOf(VanillaPistonRules.isPullable(block)));

    private static SuggestionProvider<CommandSourceStack> vanillaValue(Function<Block, String> formatter) {
        return (ctx, builder) -> {
            Block block = resolveBlock(ctx);
            if (block != null) {
                String text = formatter.apply(block);
                if (text != null && !text.isEmpty()) {
                    builder.suggest(text);
                }
            }
            return builder.buildFuture();
        };
    }

    /**
     * 读取前一个「block」参数并解析为已注册的方块；解析失败返回 null。
     */
    private static Block resolveBlock(CommandContext<CommandSourceStack> ctx) {
        try {
            Identifier id = IdentifierArgument.getId(ctx, "block");
            if (!BuiltInRegistries.BLOCK.containsKey(id)) return null;
            return BuiltInRegistries.BLOCK.getValue(id);
        } catch (Exception e) {
            return null;
        }
    }

    private static String blockKey(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }

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
