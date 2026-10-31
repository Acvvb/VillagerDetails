package com.villagerdetails.command.c.impl.rule.region;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.villagerdetails.command.c.impl.base.AbstractRegionBindingCommand;
import com.villagerdetails.config.impl.section.item.PortalBindingConfig;
import com.villagerdetails.handler.binding.block.portalbinding.PortalBindingHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * /ec c PortalBinding —— 传送门绑定指令。
 *
 * <p>用法（{@code <id>} 就是 {@code show} 列表里每个条目最前面的 {@code #ID}）：
 * <pre>
 *   /ec c PortalBinding set          → 把当前维度的拴绳选区写入缓存
 *   /ec c PortalBinding bind         → 把主世界 + 地狱两个缓存正式绑定
 *   /ec c PortalBinding show         → 列出所有绑定（点坐标行即高亮，另有 [刷新] / [删除]）
 *   /ec c PortalBinding view &lt;id&gt;     → 把某条绑定推去客户端高亮
 *   /ec c PortalBinding delete &lt;id&gt;   → 先刷掉该条两侧的缓存门坐标，再删除绑定（客户端高亮一并清掉）
 *   /ec c PortalBinding refresh &lt;id&gt;  → 清空该绑定两侧的缓存门坐标并立即重扫
 * </pre>
 *
 * <p>命令树、参数解析、非玩家提示、ID 的 Tab 补全都在
 * {@link AbstractRegionBindingCommand}；业务（缓存、绑定、列表、高亮、删除、刷新）在
 * {@link PortalBindingHandler}。这个类只负责把两者接起来，不写任何业务。
 *
 * <p>两侧配对形态：{@code set} 走两次（主世界一次、地狱一次）再 {@code bind}，
 * 所以只挂 {@code set} helper、不挂 {@code clear} 子命令。
 *
 * <p>{@code refresh} 是本类独有的子命令，通过
 * {@link #addExtraSubcommands(LiteralArgumentBuilder)} 自行挂载——
 * 基类不认识它，其它区域绑定规则也不会有这个子命令。它同样按 {@code <id>} 定位
 * （ID 只增不减、删除不回收，不需要再带列表版本去校验），并按 Tab 补全。
 *
 * <p>{@code refresh} 只重新按「离区域中心最近」选门，不保证换成玩家想要的那扇 ——
 * 想强制某扇门，请拆掉当前缓存的那扇，或把选区框到目标门附近。
 */
public final class PortalBindingServerImpl extends AbstractRegionBindingCommand {

    public static final PortalBindingServerImpl INSTANCE = new PortalBindingServerImpl();

    private PortalBindingServerImpl() {
    }

    @Override
    protected String literalName() {
        return "PortalBinding";
    }

    @Override
    protected String kNotPlayer() {
        return "command.portal_binding.failure.not_player";
    }

    /**
     * Tab 补全的数据源：传送门绑定当前的全部 ID。
     */
    @Override
    protected List<String> availableIds() {
        return PortalBindingConfig.INSTANCE.getIds();
    }

    @Override
    protected int set(ServerPlayer player) {
        return PortalBindingHandler.set(player);
    }

    @Override
    protected int bind(ServerPlayer player) {
        return PortalBindingHandler.bind(player);
    }

    @Override
    protected int show(ServerPlayer player) {
        return PortalBindingHandler.show(player);
    }

    @Override
    protected int view(ServerPlayer player, String id) {
        return PortalBindingHandler.view(player, id);
    }

    @Override
    protected int delete(ServerPlayer player, String id) {
        return PortalBindingHandler.delete(player, id);
    }

    /**
     * 声明本类要挂的子命令：
     * <ul>
     *     <li>{@code set} —— 调 {@link #addSetCommand}，基类负责非玩家提示；</li>
     *     <li>{@code refresh <id>} —— 本类独有，自己往 {@code root} 上挂；
     *         ID 补全复用基类的 {@link #idSuggestions()}。</li>
     * </ul>
     *
     * <p>{@code refresh} 只有 PortalBinding 需要「清缓存 + 重扫」，所以放在本类而不是基类。
     * 其它区域绑定规则（PortalPiglinNoSpawn 之类）不会有这个子命令。
     *
     * <p>每一步都 <b>接住返回值</b>（{@code root = ...}）再往下传、最后返回：
     * 命令树以返回值 {@code root} 为准，忽略 {@code then} / helper 的返回值就等于把刚挂上的
     * {@code set} / {@code refresh} 丢掉。
     */
    @Override
    protected LiteralArgumentBuilder<CommandSourceStack> addExtraSubcommands(
            LiteralArgumentBuilder<CommandSourceStack> root) {
        root = addSetCommand(root);

        root = root.then(Commands.literal("refresh")
                .then(Commands.argument("id", StringArgumentType.word())
                        .suggests(idSuggestions())
                        .executes(this::refreshCmd)));

        return root;
    }

    /**
     * {@code refresh <id>}：清空该绑定两侧缓存的门坐标，再按当前区域状态重扫回写。
     */
    private int refreshCmd(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = playerOrNull(ctx.getSource());
        if (player == null) {
            return 0;
        }
        return PortalBindingHandler.refresh(player, StringArgumentType.getString(ctx, "id"));
    }
}
