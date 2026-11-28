package com.villagerdetails.command.c.impl.rule.rule;

import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.villagerdetails.command.c.impl.base.AbstractRuleCommand;
import com.villagerdetails.command.c.impl.suggestion.BlockSuggestionData;
import com.villagerdetails.config.impl.section.item.CustomPistonPushBlockConfig;
import com.villagerdetails.util.piston.VanillaPistonRules;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import java.util.List;

/**
 * /ec c CustomPistonPushBlock &lt;方块id&gt; true|false —— 让这个方块被活塞搬运的行为和原版<b>相反</b>。
 *
 * <p>一条规则管三件事（原版都用 {@code PistonBaseBlock#isPushable} 判，所以一个「取反」就够）：
 * <ul>
 *     <li>活塞能不能<b>推动</b>它；</li>
 *     <li>粘性活塞能不能把它<b>黏回来</b>；</li>
 *     <li>蜂蜜块 / 史莱姆块能不能<b>黏住它、把它带走</b>。</li>
 * </ul>
 *
 * <p>名单只记<b>与原版不同</b>的方块，值等于原版就删条目：
 * <ul>
 *     <li>{@code true}  —— 想让它「能被搬」：原版搬不动的（黑曜石之类）才需要记，
 *         记下来运行期取反 = 像石头一样能被推、被黏回、被粘性方块带走；</li>
 *     <li>{@code false} —— 想让它「搬不动」：原版能搬的才需要记，
 *         记下来运行期取反 = 推不动、黏不回、也黏不住；</li>
 *     <li>值等于原版 —— 不需要配置，条目直接删除（= 恢复原版）。</li>
 * </ul>
 *
 * <p>「原版值」用 {@link VanillaPistonRules#isPullable}：方块级「能不能被活塞正常搬动」的那个集合
 * （能推能拉、且反应是可移动），也就是「会不会被粘性方块带走」的前提。
 * 它只决定这条配置要不要落库；真正的判定在注入点取原版对当前上下文的真实结果再翻转。
 * <p>
 * /ec c CustomPistonPushBlock reset —— 清空所有配置。
 * /ec c CustomPistonPushBlock show  —— 显示当前名单（带实际生效的「能不能被搬」）。
 */
public final class CustomPistonPushBlockServerImpl extends AbstractRuleCommand {

    public static final CustomPistonPushBlockServerImpl INSTANCE = new CustomPistonPushBlockServerImpl();

    private CustomPistonPushBlockServerImpl() {
    }

    private static final String K_SET = "command.block.custom_piston_push_block.set";
    private static final String K_BLOCKED = "command.block.custom_piston_push_block.set_blocked";
    private static final String K_REMOVE = "command.block.custom_piston_push_block.remove";
    private static final String K_RESET = "command.block.custom_piston_push_block.reset";
    private static final String K_SHOW_HEADER = "command.block.custom_piston_push_block.show.header";
    private static final String K_SHOW_EMPTY = "command.block.custom_piston_push_block.show.empty";
    private static final String K_INVALID_VALUE = "command.block.custom_piston_push_block.invalid_value";

    @Override
    protected String literalName() {
        return "CustomPistonPushBlock";
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
        return BlockSuggestionData.VANILLA_PISTON_MOVABLE_SUGGESTER;
    }

    /**
     * 核心：想要的搬运行为和原版一致就不落库（删条目），不一致才记进名单。
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
        boolean vanilla = VanillaPistonRules.isPullable(block);
        if (desired == vanilla) {
            // 和原版一致 → 不需要配置
            remove(id);
            return K_REMOVE;
        }

        add(id);
        return desired ? K_SET : K_BLOCKED;
    }

    /**
     * {@code show} 每行带上实际生效的值：名单里存的是「取反」，光看 id 看不出能不能被搬。
     */
    @Override
    protected Component describeId(Identifier id) {
        Block block = BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
        if (block == null) {
            return Component.literal("  " + id);
        }
        // 名单里的方块行为是反的；名单外走原版
        boolean movable = CustomPistonPushBlockConfig.INSTANCE.containsId(id) != VanillaPistonRules.isPullable(block);
        return Component.literal("  " + id + " = ")
                .append(Component.translatable(movable ? "villagerdetails.ui.yes" : "villagerdetails.ui.no"));
    }

    @Override
    protected void add(Identifier id) {
        CustomPistonPushBlockConfig.INSTANCE.add(id);
    }

    @Override
    protected void remove(Identifier id) {
        CustomPistonPushBlockConfig.INSTANCE.remove(id);
    }

    @Override
    protected void resetConfig() {
        CustomPistonPushBlockConfig.INSTANCE.reset();
    }

    @Override
    protected List<Identifier> snapshot() {
        return CustomPistonPushBlockConfig.INSTANCE.snapshot();
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
