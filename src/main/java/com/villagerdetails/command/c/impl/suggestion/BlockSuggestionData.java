package com.villagerdetails.command.c.impl.suggestion;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * 方块注册 id 的静态缓存，供补全服务器复用。
 * <p>
 * 一次性遍历方块注册表，把方块分成两类：
 *   · 可采集（collectable）：可被破坏（破坏时间 &gt;= 0）且拥有战利品表（挖掉会掉落东西）。
 *   · 不可采集（uncollectable）：其余方块——挖不掉的（如基岩，破坏时间 &lt; 0）或原版挖了不掉的（无战利品表，如刷怪笼）。
 * <p>
 * 空气与流体（水/岩浆）不属于可放置采集的实体方块，直接过滤掉。
 */
public final class BlockSuggestionData {

    private BlockSuggestionData() {
    }

    private static volatile boolean initialized = false;
    private static volatile List<String> collectable = List.of();
    private static volatile List<String> uncollectable = List.of();

    /** 可采集方块 id（用于挖掘抗性规则的补全）。 */
    public static List<String> collectableIds() {
        ensureInit();
        return collectable;
    }

    /** 不可采集方块 id（用于可采集规则的补全）。 */
    public static List<String> uncollectableIds() {
        ensureInit();
        return uncollectable;
    }

    private static synchronized void ensureInit() {
        if (initialized) return;

        List<String> col = new ArrayList<>();
        List<String> uncol = new ArrayList<>();

        for (Identifier id : BuiltInRegistries.BLOCK.keySet()) {
            var ref = BuiltInRegistries.BLOCK.get(id);
            if (ref.isEmpty()) continue;
            Block block = ref.get().value();

            BlockState def = block.defaultBlockState();
            // 过滤空气与流体，避免把 air / water / lava 当成可采集方块补全
            if (def.isAir() || !def.getFluidState().isEmpty()) continue;

            boolean breakable = block.defaultDestroyTime() >= 0f;
            boolean hasLoot = block.getLootTable().isPresent();

            if (breakable && hasLoot) {
                col.add(id.toString());
            } else {
                uncol.add(id.toString());
            }
        }

        collectable = List.copyOf(col);
        uncollectable = List.copyOf(uncol);
        initialized = true;
    }
}
