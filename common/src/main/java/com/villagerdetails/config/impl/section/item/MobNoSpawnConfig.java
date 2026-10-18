package com.villagerdetails.config.impl.section.item;

import com.villagerdetails.config.impl.section.base.IdSetConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;

import static com.villagerdetails.rule.type.RuleType.MOB_NO_SPAWN;

/**
 * 禁止生成配置：被配置成「不许生成」的实体类型名单。
 *
 * <p>名单里的实体类型不会再被创建出来 —— 自然生成、刷怪笼、刷怪蛋、{@code /summon}、
 * 繁殖、结构生成等等全部拦住（拦截点见 {@code NoSpawnHandler}）。
 *
 * <p>和「无挤压」共用同一套存储（{@link IdSetConfig}）：统一 {@code config.json} 落盘 +
 * 同步字符串，服务端和客户端读的是同一份实现。
 */
public final class MobNoSpawnConfig extends IdSetConfig {

    private MobNoSpawnConfig() {
        super(MOB_NO_SPAWN.getRegisterName());
    }

    public static final MobNoSpawnConfig INSTANCE = new MobNoSpawnConfig();

    @Override
    public String configName() {
        return MOB_NO_SPAWN.getRegisterName();
    }

    @Override
    public void reload(MinecraftServer server) {
        loadStorage();
    }

    /**
     * 判断某种实体类型是否在名单里
     */
    public boolean contains(EntityType<?> type) {
        if (type == null) return false;
        return containsId(BuiltInRegistries.ENTITY_TYPE.getKey(type));
    }
}
