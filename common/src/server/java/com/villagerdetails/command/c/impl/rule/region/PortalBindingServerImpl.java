package com.villagerdetails.command.c.impl.rule.region;

import com.villagerdetails.command.c.impl.base.AbstractRegionBindingCommand;
import com.villagerdetails.handler.binding.block.portalbinding.PortalBindingHandler;
import net.minecraft.server.level.ServerPlayer;

/**
 * /ec c PortalBinding —— 传送门绑定指令。
 *
 * <p>用法：
 * <pre>
 *   /ec c PortalBinding set    → 把当前维度的拴绳选区写入缓存
 *   /ec c PortalBinding bind   → 把主世界 + 地狱两个缓存正式绑定
 *   /ec c PortalBinding show   → 列出所有绑定（点坐标行即高亮，另有 [删除]）
 *   /ec c PortalBinding view &lt;序号&gt;   → 把某条绑定推去客户端高亮
 *   /ec c PortalBinding delete &lt;序号&gt; → 删除某条绑定
 * </pre>
 *
 * <p>命令树、参数解析、非玩家提示都在 {@link AbstractRegionBindingCommand}；
 * 业务（缓存、绑定、列表、高亮、删除）在
 * {@link PortalBindingHandler}。这个类只负责把两者接起来，不写任何业务。
 *
 * <p>两侧配对形态：{@code set} 走两次（主世界一次、地狱一次）再 {@code bind}，
 * 所以没有 {@code clear} 子命令。
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
}
