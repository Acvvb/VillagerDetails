package com.villagerdetails.config.impl.section.item;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 方块强充能配置。
 * 存储被配置为"红石导体"的方块。
 * 加入列表的方块会表现得像泥土/石头一样：
 * 被红石火把、中继器、比较器充能后，把充能状态传递给相邻的红石线。
 */
public final class BlockStrongPowerConfig {

    public static final BlockStrongPowerConfig INSTANCE = new BlockStrongPowerConfig();

    private final Set<Identifier> ids = new LinkedHashSet<>();

    private BlockStrongPowerConfig() {
    }

    public void add(Identifier id) {
        ids.add(id);
    }

    public void remove(Identifier id) {
        ids.remove(id);
    }

    public void reset() {
        ids.clear();
    }

    public List<Identifier> snapshot() {
        return new ArrayList<>(ids);
    }

    /** 判断某个方块是否在列表中 */
    public boolean contains(Block block) {
        if (block == null || ids.isEmpty()) return false;
        Identifier blockId = BuiltInRegistries.BLOCK.getKey(block);
        return ids.contains(blockId);
    }

    /** 只读视图，便于调试 */
    public Set<Identifier> getIds() {
        return Collections.unmodifiableSet(ids);
    }

    /** 序列化成同步字符串：id1,id2,id3 */
    public String toSyncString() {
        StringBuilder sb = new StringBuilder();
        for (Identifier id : ids) {
            if (!sb.isEmpty()) sb.append(',');
            sb.append(id);
        }
        return sb.toString();
    }

    /** 从同步字符串反序列化（整份替换） */
    public void applyFromSyncString(String data) {
        ids.clear();
        if (data == null || data.isEmpty()) return;
        for (String part : data.split(",")) {
            String s = part.trim();
            if (s.isEmpty()) continue;
            Identifier id = Identifier.tryParse(s);
            if (id != null) ids.add(id);
        }
    }
}