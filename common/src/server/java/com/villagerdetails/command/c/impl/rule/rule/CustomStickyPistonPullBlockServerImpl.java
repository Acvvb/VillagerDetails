package com.villagerdetails.command.c.impl.rule.rule;

import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.villagerdetails.command.c.impl.base.AbstractRuleCommand;
import com.villagerdetails.command.c.impl.suggestion.BlockSuggestionData;
import com.villagerdetails.config.impl.section.item.CustomStickyPistonPullBlockConfig;
import com.villagerdetails.handler.block.pistonPush.VanillaPistonRules;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import java.util.List;

/**
 * /ec c CustomStickyPistonPullBlock &lt;方块id&gt; true|false —— 让这个方块被粘性活塞<b>黏回</b>的行为和原版相反。
 *
 * <p>和「活塞推动控制」（{@code CustomPistonPushBlockServerImpl}）完全对称，只是作用于<b>缩回</b>：
 * 名单只记「与原版不同」的方块，设置的值如果和这个方块的原版行为一致，就<b>直接把配置删掉</b>。
 *
 * <ul>
 *     <li>{@code true}  —— 想让它被黏回：原版黏不回来才需要记，记下来运行期取反 = 能黏回来；</li>
 *     <li>{@code false} —— 想让它黏不住：原版能黏回来才需要记，记下来运行期取反 = 黏不住；</li>
 *     <li>值等于原版 —— 不需要配置，条目直接删除（= 恢复原版）。</li>
 * </ul>
 *
 * /ec c CustomStickyPistonPullBlock reset —— 清空所有配置。
 * /ec c CustomStickyPistonPullBlock show  —— 显示当前名单（带是 / 否）。
 */
public final class CustomStickyPistonPullBlockServerImpl extends AbstractRuleCommand {

    public static final CustomStickyPistonPullBlockServerImpl INSTANCE = new CustomStickyPistonPullBlockServerImpl();

    private CustomStickyPistonPullBlockServerImpl() {
    }

    private static final String K_SET = "command.block.custom_sticky_piston_pull_block.set";
    private static final String K_BLOCKED = "command.block.custom_sticky_piston_pull_block.set_blocked";
    private static final String K_REMOVE = "command.block.custom_sticky_piston_pull_block.remove";
    private static final String K_RESET = "command.block.custom_sticky_piston_pull_block.reset";
    private static final String K_SHOW_HEADER = "command.block.custom_sticky_piston_pull_block.show.header";
    private static final String K_SHOW_EMPTY = "command.block.custom_sticky_piston_pull_block.show.empty";
    private static final String K_INVALID_VALUE = "command.block.custom_sticky_piston_pull_block.invalid_value";

    @Override
    protected String literalName() {
        return "CustomStickyPistonPullBlock";
    }

    @Override
    protected String argName() {
        return "block";
    }

    @Override
    protected SuggestionProvider<CommandSourceStack> idSuggester() {
        return BlockSuggestionData.ALL_BLOCK_ID_SUGGESTER;
    }

    @Override
    protected SuggestionProvider<CommandSourceStack> valueSuggester() {
        return BlockSuggestionData.VANILLA_PISTON_PUSHABLE_SUGGESTER;
    }

    /**
     * 核心：想要的黏回行为和原版一致就不落库（删条目），不一致才记进名单。
     *
     * @return 这次操作该回的提示 key
     */
    @Override
    protected String applyValue(Identifier id, String value) {
        Block block = BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
        if (block == null) {
            // id 合法但注册表里没有（模组被移除之类）：留着也没意义，直接删
            remove(id);
            return K_REMOVE;
        }

        boolean desired = "true".equals(value);
        // 原版「能不能被黏回来」和「能不能被推」是同一个判定（都是 isPushable）
        boolean vanilla = VanillaPistonRules.isPushable(block);
        if (desired == vanilla) {
            // 和原版一致 → 不需要配置
            remove(id);
            return K_REMOVE;
        }

        add(id);
        return desired ? K_SET : K_BLOCKED;
    }

    /**
     * {@code show} 每行带上实际生效的值：名单里存的是「取反」，光看 id 看不出黏不黏得住。
     */
    @Override
    protected Component describeId(Identifier id) {
        Block block = BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
        boolean sticky = block != null && !VanillaPistonRules.isPushable(block);
        return Component.literal("  " + id + " = ")
                .append(Component.translatable(sticky ? "villagerdetails.ui.yes" : "villagerdetails.ui.no"));
    }

    @Override
    protected void add(Identifier id) {
        CustomStickyPistonPullBlockConfig.INSTANCE.add(id);
    }

    @Override
    protected void remove(Identifier id) {
        CustomStickyPistonPullBlockConfig.INSTANCE.remove(id);
    }

    @Override
    protected void resetConfig() {
        CustomStickyPistonPullBlockConfig.INSTANCE.reset();
    }

    @Override
    protected List<Identifier> snapshot() {
        return CustomStickyPistonPullBlockConfig.INSTANCE.snapshot();
    }

    @Override
    protected String kSet() {
        return K_SET;
    }

    @Override
    protected String kRemove() {
        return K_REMOVE;
    }

    @Override
    protected String kReset() {
        return K_RESET;
    }

    @Override
    protected String kShowHeader() {
        return K_SHOW_HEADER;
    }

    @Override
    protected String kShowEmpty() {
        return K_SHOW_EMPTY;
    }

    @Override
    protected String kInvalidValue() {
        return K_INVALID_VALUE;
    }
}
