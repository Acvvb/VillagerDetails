package com.villagerdetails.command.c.impl.rule.region;

import com.villagerdetails.command.c.impl.base.AbstractRegionBindingCommand;
import com.villagerdetails.handler.entity.piglinNoSpawn.PortalPiglinNoSpawnHandler;
import net.minecraft.server.level.ServerPlayer;

/**
 * /ec c PortalPiglinNoSpawn bind|show|view|delete|clear —— 「禁用地狱门刷僵尸猪灵」的区域绑定指令。
 *
 * <p>命令树、参数解析、非玩家提示都在 {@link AbstractRegionBindingCommand}；
 * 业务（区域增删查、消息拼装、客户端高亮）在 {@link PortalPiglinNoSpawnHandler}。
 * 这个类只负责把两者接起来，不写任何业务。
 */
public final class PortalPiglinNoSpawnServerImpl extends AbstractRegionBindingCommand {

    public static final PortalPiglinNoSpawnServerImpl INSTANCE = new PortalPiglinNoSpawnServerImpl();

    private PortalPiglinNoSpawnServerImpl() {
    }

    @Override
    protected String literalName() {
        return "PortalPiglinNoSpawn";
    }

    @Override
    protected String kNotPlayer() {
        return "command.portal_piglin_no_spawn.failure.not_player";
    }

    @Override
    protected int bind(ServerPlayer player) {
        return PortalPiglinNoSpawnHandler.bind(player);
    }

    @Override
    protected int show(ServerPlayer player) {
        return PortalPiglinNoSpawnHandler.show(player);
    }

    @Override
    protected int view(ServerPlayer player, int displayIndex) {
        return PortalPiglinNoSpawnHandler.view(player, displayIndex);
    }

    @Override
    protected int delete(ServerPlayer player, int displayIndex) {
        return PortalPiglinNoSpawnHandler.delete(player, displayIndex);
    }

    @Override
    protected int clear(ServerPlayer player) {
        return PortalPiglinNoSpawnHandler.clear(player);
    }
}
