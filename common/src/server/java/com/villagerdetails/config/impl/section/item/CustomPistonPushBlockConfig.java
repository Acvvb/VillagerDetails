package com.villagerdetails.config.impl.section.item;

import com.villagerdetails.config.impl.section.base.IdSetConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;

import static com.villagerdetails.rule.type.RuleType.CUSTOM_PISTON_PUSH_BLOCK;

/**
 * 「活塞推动控制」：<b>与原版判定不同</b>的方块名单。
 *
 * <p>沿用「强充能 / 可采集」那套 {@link IdSetConfig}：名单只存<b>例外</b>，
 * 不在名单里的方块完全走原版；名单里的方块在运行期把原版结果<b>取反</b>
 * （原版能推的变不能推、原版不能推的变能推），所以一份名单同时表达「可阻止」和「可强制」。
 *
 * <p>和原版一致的配置不落库：{@code /ec c} 里设置的值如果等于这个方块的原版行为，
 * 命令直接把条目删掉（见 {@code CustomPistonPushBlockHandler#vanillaPushable}）。
 */
public final class CustomPistonPushBlockConfig extends IdSetConfig {

    private CustomPistonPushBlockConfig() {
        super(CUSTOM_PISTON_PUSH_BLOCK.getRegisterName());
    }

    public static final CustomPistonPushBlockConfig INSTANCE = new CustomPistonPushBlockConfig();

    @Override
    public String configName() {
        return CUSTOM_PISTON_PUSH_BLOCK.getRegisterName();
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
