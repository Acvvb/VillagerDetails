package com.villagerdetails.config.impl.section.item;

import com.villagerdetails.config.impl.section.base.IdSetConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;

import static com.villagerdetails.rule.type.RuleType.ENDER_PORTAL_GRAVITY_BLOCK;

/**
 * 末地门重力方块配置：被配置的方块如果在末地传送门水平相邻且下方为空气，会获得类似沙子的掉落特性。
 *
 * <p>和「方块可采集」共用同一套存储（{@link IdSetConfig}）：统一 {@code config.json} 落盘 +
 * 同步字符串，服务端和客户端读的是同一份实现。
 */
public final class EndPortalGravityBlockConfig extends IdSetConfig {

    private EndPortalGravityBlockConfig() {
        super(ENDER_PORTAL_GRAVITY_BLOCK.getRegisterName());
    }

    public static final EndPortalGravityBlockConfig INSTANCE = new EndPortalGravityBlockConfig();

    @Override
    public String configName() {
        return ENDER_PORTAL_GRAVITY_BLOCK.getRegisterName();
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