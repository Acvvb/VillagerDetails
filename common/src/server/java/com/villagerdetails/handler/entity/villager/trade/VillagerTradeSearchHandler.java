package com.villagerdetails.handler.entity.villager.trade;

import com.villagerdetails.cache.impl.VillagerTradeCache;
import com.villagerdetails.lang.ServerTranslations;
import com.villagerdetails.pojo.dto.TradeItemInfo;
import com.villagerdetails.pojo.dto.VillagerTradeInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;

import static com.villagerdetails.command.CommandConstants.COMMAND_BASE;
import static com.villagerdetails.util.SendMessengerUtils.sendOrBroadcast;

/**
 * {@code /ec c TradeSearch} 的业务实现：<b>只查内存缓存</b>，不遍历世界。
 *
 * <p>结果按「最多 {@value #MAX_RESULTS} 条、每条最多 {@value #MAX_OFFERS_PER_ENTRY} 件商品」截断，
 * 防止聊天栏刷屏；每条都带一个可点击的 {@code [传送]}，点了执行
 * {@code /ec c TradeSearch teleport <uuid>}。
 *
 * <p>为什么传送走自定义子命令而不是 {@code /tp}：搜索命令本身只要求规则档位权限，
 * 普通玩家也能用；如果按钮里塞原版 {@code /tp}，没 OP 的玩家点了会失败。
 * 自定义子命令在服务端直接调用 {@link ServerPlayer#teleportTo}，不额外要求 OP。
 */
public final class VillagerTradeSearchHandler {

    private VillagerTradeSearchHandler() {
    }

    /**
     * 一次最多显示多少条村民（防刷屏）。
     */
    public static final int MAX_RESULTS = 10;

    /**
     * 一条结果里最多列几件商品，多出来的折叠成「另有 N 件」。
     */
    public static final int MAX_OFFERS_PER_ENTRY = 6;

    private static final String CMD_PREFIX = COMMAND_BASE + " c TradeSearch";

    private static final String K_USAGE = "command.villager_trade_search.usage";
    private static final String K_EMPTY = "command.villager_trade_search.empty";
    private static final String K_NO_MATCH = "command.villager_trade_search.no_match";
    private static final String K_HEADER = "command.villager_trade_search.header";
    private static final String K_TRUNCATED = "command.villager_trade_search.truncated";
    private static final String K_ENTRY_OFFERS = "command.villager_trade_search.entry.offers";
    private static final String K_MORE_OFFERS = "command.villager_trade_search.entry.more";
    private static final String K_BUTTON_TELEPORT = "command.villager_trade_search.button.teleport";
    private static final String K_HOVER_TELEPORT = "command.villager_trade_search.hover.teleport";
    private static final String K_TELEPORT_SUCCESS = "command.villager_trade_search.teleport.success";
    private static final String K_TELEPORT_FAILURE = "command.villager_trade_search.teleport.failure";
    private static final String K_NOT_PLAYER = "command.villager_trade_search.failure.not_player";

    // ============================================================
    // 入口
    // ============================================================

    /**
     * 不带关键词：发用法提示，并顺带告诉玩家缓存里现在有多少条。
     */
    public static int showUsage(ServerPlayer player) {
        sendOrBroadcast(player, Component.translatable(
                K_USAGE, String.valueOf(VillagerTradeCache.INSTANCE.size())));
        return 1;
    }

    /**
     * {@code /ec c TradeSearch <关键词>}
     *
     * @return 实际显示的结果条数；0 表示没命中 / 缓存为空
     */
    public static int search(ServerPlayer player, String keyword) {
        String trimmed = keyword == null ? "" : keyword.trim();
        if (trimmed.isEmpty()) {
            return showUsage(player);
        }
        // 关键词会原样拼进聊天栏标题 / 提示：去掉 § 颜色码，免得玩家用关键词给整行染色
        String displayKeyword = trimmed.replace('§', ' ');

        List<VillagerTradeInfo> matches = VillagerTradeCache.INSTANCE.search(trimmed);
        if (matches.isEmpty()) {
            if (VillagerTradeCache.INSTANCE.size() == 0) {
                // 缓存空 = 还没积累到任何快照（区块卸载才会写缓存），单独给一句提示
                sendOrBroadcast(player, Component.translatable(K_EMPTY));
            } else {
                sendOrBroadcast(player, Component.translatable(K_NO_MATCH, displayKeyword));
            }
            return 0;
        }

        int shown = Math.min(MAX_RESULTS, matches.size());
        MutableComponent out = Component.translatable(
                K_HEADER, displayKeyword, String.valueOf(matches.size()), String.valueOf(shown));
        for (int i = 0; i < shown; i++) {
            out.append("\n").append(entry(matches.get(i), i + 1));
        }
        if (matches.size() > shown) {
            out.append("\n").append(Component.translatable(K_TRUNCATED, String.valueOf(matches.size() - shown)));
        }

        sendOrBroadcast(player, out);
        return shown;
    }

    /**
     * {@code /ec c TradeSearch teleport <uuid>}：把玩家送到缓存里记的村民坐标。
     */
    public static int teleport(ServerPlayer player, String rawUuid) {
        if (player == null) {
            sendOrBroadcast(null, Component.translatable(K_NOT_PLAYER));
            return 0;
        }

        UUID uuid;
        try {
            uuid = UUID.fromString(rawUuid == null ? "" : rawUuid.trim());
        } catch (IllegalArgumentException e) {
            sendOrBroadcast(player, Component.translatable(K_TELEPORT_FAILURE));
            return 0;
        }

        VillagerTradeInfo info = VillagerTradeCache.INSTANCE.get(uuid);
        if (info == null) {
            sendOrBroadcast(player, Component.translatable(K_TELEPORT_FAILURE));
            return 0;
        }

        MinecraftServer server = player.level().getServer();
        ServerLevel target = server == null ? null : server.getLevel(info.dimension());
        if (target == null) {
            sendOrBroadcast(player, Component.translatable(K_TELEPORT_FAILURE));
            return 0;
        }

        BlockPos pos = info.pos();
        boolean moved = player.teleportTo(
                target,
                pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D,
                Set.<Relative>of(), player.getYRot(), player.getXRot(), false);
        if (!moved) {
            sendOrBroadcast(player, Component.translatable(K_TELEPORT_FAILURE));
            return 0;
        }

        sendOrBroadcast(player, Component.translatable(K_TELEPORT_SUCCESS));
        return 1;
    }

    // ============================================================
    // 结果拼装
    // ============================================================

    /**
     * 一条结果：{@code #序号 维度 x,y,z [传送]} + 下一行的商品列表。
     */
    private static MutableComponent entry(VillagerTradeInfo info, int index) {
        MutableComponent line = Component.literal("§7#" + index + " ")
                .append(dimensionName(info.dimension()))
                .append(Component.literal(" §7" + formatPos(info.pos()) + " "))
                .append(teleportButton(info.uuid()));

        List<TradeItemInfo> offers = info.offers();
        if (offers.isEmpty()) {
            return line;
        }

        StringJoiner joiner = new StringJoiner("§8, ", "§f", "");
        int limit = Math.min(MAX_OFFERS_PER_ENTRY, offers.size());
        for (int i = 0; i < limit; i++) {
            joiner.add(offers.get(i).displayText());
        }
        MutableComponent offersText = Component.literal(joiner.toString());
        if (offers.size() > limit) {
            offersText.append(Component.translatable(K_MORE_OFFERS, String.valueOf(offers.size() - limit)));
        }

        return line.append("\n  ").append(Component.translatable(K_ENTRY_OFFERS, offersText));
    }

    /**
     * 可点击的 {@code [传送]} 按钮。点击执行本命令树的 teleport 分支。
     */
    private static MutableComponent teleportButton(UUID uuid) {
        String command = CMD_PREFIX + " teleport " + uuid;
        String hover = ServerTranslations.resolve(Component.translatable(K_HOVER_TELEPORT));

        return Component.translatable(K_BUTTON_TELEPORT)
                .withStyle(style -> style.withColor(TextColor.fromRgb(0x5DADE2)))
                .withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand(command)))
                .withStyle(style -> style.withHoverEvent(
                        new HoverEvent.ShowText(Component.literal(hover))));
    }

    /**
     * 维度显示名：三个原版维度用语言键，其它维度（模组维度）退回注册 id。
     */
    private static MutableComponent dimensionName(ResourceKey<Level> dimension) {
        if (Level.OVERWORLD.equals(dimension)) {
            return Component.translatable("dimension.minecraft.overworld");
        }
        if (Level.NETHER.equals(dimension)) {
            return Component.translatable("dimension.minecraft.the_nether");
        }
        if (Level.END.equals(dimension)) {
            return Component.translatable("dimension.minecraft.the_end");
        }
        return Component.literal(dimension.identifier().toString());
    }

    private static String formatPos(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }
}
