package com.villagerdetails.config.impl.section.item;

import com.villagerdetails.config.impl.section.base.IdSetConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;

import static com.villagerdetails.rule.type.RuleType.BLOCK_STRONG_POWER;

/**
 * 方块强充能配置：被配置成「红石导体」的方块名单。
 *
 * <p>加入名单的方块会表现得像泥土 / 石头一样：被红石火把、中继器、比较器充能后，
 * 把充能状态传递给相邻的红石线。
 *
 * <p>和「方块可采集」共用同一套存储（{@link IdSetConfig}）：统一 {@code config.json} 落盘 +
 * 同步字符串，服务端和客户端读的是同一份实现。这一点很重要 —— {@code BlockStateBaseConductorMixin}
 * 在客户端也会跑，本地镜像拿不到名单就会和服务端的判定对不上。
 */
public final class BlockStrongPowerConfig extends IdSetConfig {

    private BlockStrongPowerConfig() {
        super(BLOCK_STRONG_POWER.getRegisterName());
    }

    public static final BlockStrongPowerConfig INSTANCE = new BlockStrongPowerConfig();

    @Override
    public String configName() {
        return BLOCK_STRONG_POWER.getRegisterName();
    }

    @Override
    public void reload(MinecraftServer server) {
        loadStorage();
    }

    /**
     * 判断某个方块是否在名单里
     */
    public boolean contains(Block block) {
        return block != null && containsId(BuiltInRegistries.BLOCK.getKey(block));
    }
}
