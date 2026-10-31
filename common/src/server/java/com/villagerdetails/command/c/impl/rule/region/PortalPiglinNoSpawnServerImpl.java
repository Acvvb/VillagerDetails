package com.villagerdetails.command.c.impl.rule.region;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.villagerdetails.command.c.impl.base.AbstractRegionBindingCommand;
import com.villagerdetails.config.impl.section.item.PortalPiglinNoSpawnConfig;
import com.villagerdetails.handler.entity.piglinNoSpawn.PortalPiglinNoSpawnHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * /ec c PortalPiglinNoSpawn bind|show|view|delete|clear —— 「禁用地狱门刷僵尸猪灵」的区域绑定指令。
 *
 * <p>命令树、参数解析、非玩家提示、ID 的 Tab 补全都在 {@link AbstractRegionBindingCommand}；
 * 业务（区域增删查、消息拼装、客户端高亮）在 {@link PortalPiglinNoSpawnHandler}。
 * 这个类只负责把两者接起来，不写任何业务。
 *
 * <p>{@code <id>} 就是 {@code show} 列表里每个条目最前面的 {@code #ID}。
 *
 * <p>单段绑定形态：没有「两次 set 再 bind」的配对，所以只挂 {@code clear} helper，
 * 不挂 {@code set} 子命令——命令树里根本不会出现 {@code set}。
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

    /**
     * Tab 补全的数据源：地狱门区域当前的全部 ID。
     */
    @Override
    protected List<String> availableIds() {
        return PortalPiglinNoSpawnConfig.INSTANCE.getIds();
    }

    /**
     * 声明本类要挂的子命令：只调 {@link #addClearCommand}，所以只有 {@code clear}。
     * 「不是玩家」的提示由 helper 统一处理。
     *
     * <p>接住返回值再返回：命令树以返回值 {@code root} 为准，
     * 忽略 {@code then} / helper 的返回值有丢子命令的风险（换包装构建器时尤其明显）。
     */
    @Override
    protected LiteralArgumentBuilder<CommandSourceStack> addExtraSubcommands(
            LiteralArgumentBuilder<CommandSourceStack> root) {
        root = addClearCommand(root);
        return root;
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
    protected int view(ServerPlayer player, String id) {
        return PortalPiglinNoSpawnHandler.view(player, id);
    }

    @Override
    protected int delete(ServerPlayer player, String id) {
        return PortalPiglinNoSpawnHandler.delete(player, id);
    }

    @Override
    protected int clear(ServerPlayer player) {
        return PortalPiglinNoSpawnHandler.clear(player);
    }
}
