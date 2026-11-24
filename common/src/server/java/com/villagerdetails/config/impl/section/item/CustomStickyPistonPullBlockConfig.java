package com.villagerdetails.config.impl.section.item;

import com.villagerdetails.config.impl.section.base.IdSetConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;

import static com.villagerdetails.rule.type.RuleType.CUSTOM_STICKY_PISTON_PULL_BLOCK;

/**
 * 「粘性活塞黏回控制」：<b>与原版判定不同</b>的方块名单（只管缩回那一段）。
 *
 * <p>和 {@link CustomPistonPushBlockConfig} 完全对称，只是作用于缩回：名单里的方块
 * 运行期把原版「能不能被黏回来」的结果<b>取反</b> —— 原版黏不回来的能黏回来，
 * 原版能黏回来的黏不住。一份 {@code IdSetConfig} 同时表达两种方向。
 *
 * <p>和原版一致的配置不落库：{@code /ec c} 里设置的值如果等于这个方块的原版行为，
 * 命令直接把条目删掉（见 {@code VanillaPistonRules}）。
 */
public final class CustomStickyPistonPullBlockConfig extends IdSetConfig {

    private CustomStickyPistonPullBlockConfig() {
        super(CUSTOM_STICKY_PISTON_PULL_BLOCK.getRegisterName());
    }

    public static final CustomStickyPistonPullBlockConfig INSTANCE = new CustomStickyPistonPullBlockConfig();

    @Override
    public String configName() {
        return CUSTOM_STICKY_PISTON_PULL_BLOCK.getRegisterName();
    }

    @Override
    public void reload(MinecraftServer server) {
        loadStorage();
    }

    /**
     * 这个方块是不是被配置成「与原版相反」
     */
    public boolean contains(Block block) {
        return block != null && containsId(BuiltInRegistries.BLOCK.getKey(block));
    }
}
