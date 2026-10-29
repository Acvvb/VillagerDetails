package com.villagerdetails.command.c.impl.rule.region;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.villagerdetails.command.c.impl.base.AbstractRegionBindingCommand;
import com.villagerdetails.handler.binding.block.portalbinding.PortalBindingHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import static com.villagerdetails.util.SendMessengerUtils.sendOrBroadcast;

/**
 * /ec c PortalBinding —— 传送门绑定指令。
 *
 * <p>用法：
 * <pre>
 *   /ec c PortalBinding set    → 把当前维度的拴绳选区写入缓存
 *   /ec c PortalBinding bind   → 把主世界 + 地狱两个缓存正式绑定
 *   /ec c PortalBinding show   → 列出所有绑定（点坐标行即高亮，另有 [删除]）
 *   /ec c PortalBinding view &lt;序号&gt;     → 把某条绑定推去客户端高亮
 *   /ec c PortalBinding delete &lt;序号&gt;   → 删除某条绑定
 *   /ec c PortalBinding refresh &lt;序号&gt;  → 清空该绑定两侧的缓存门坐标并立即重扫
 * </pre>
 *
 * <p>命令树、参数解析、非玩家提示都在 {@link AbstractRegionBindingCommand}；
 * 业务（缓存、绑定、列表、高亮、删除、刷新）在
 * {@link PortalBindingHandler}。这个类只负责把两者接起来，不写任何业务。
 *
 * <p>两侧配对形态：{@code set} 走两次（主世界一次、地狱一次）再 {@code bind}，
 * 所以没有 {@code clear} 子命令。
 *
 * <p>{@code refresh} 是本类独有的子命令，通过
 * {@link #addExtraSubcommands(LiteralArgumentBuilder)} 自行挂载——
 * 基类不认识它，其它区域绑定规则也不会有这个子命令。
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

    @Override
    protected boolean supportsSet() {
        return true;
    }

    @Override
    protected boolean supportsClear() {
        return false;
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
    protected int view(ServerPlayer player, int displayIndex) {
        return PortalBindingHandler.view(player, displayIndex);
    }

    @Override
    protected int delete(ServerPlayer player, int displayIndex) {
        return PortalBindingHandler.delete(player, displayIndex);
    }

    /**
     * 挂载 {@code refresh <序号>} 子命令。
     *
     * <p>只有 PortalBinding 需要「清缓存 + 重扫」，所以放在本类而不是基类。
     * 其它区域绑定规则（PortalPiglinNoSpawn 之类）不会有这个子命令。
     */
    @Override
    protected void addExtraSubcommands(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("refresh")
                .then(Commands.argument("index", IntegerArgumentType.integer(1))
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayer();
                            if (player == null) {
                                sendOrBroadcast(null, Component.translatable(kNotPlayer()));
                                return 0;
                            }
                            return PortalBindingHandler.refresh(
                                    player, IntegerArgumentType.getInteger(ctx, "index"));
                        })));
    }
}