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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.StringJoiner;

import static com.villagerdetails.util.SendMessengerUtils.sendOrBroadcast;

/**
 * {@code /ec c TradeSearch} 的业务实现：<b>只查内存缓存</b>，不遍历世界。
 *
 * <p>结果按「最多 {@value #MAX_RESULTS} 条、每条最多 {@value #MAX_OFFERS_PER_ENTRY} 件商品」截断，
 * 防止聊天栏刷屏；每条都带一个可点击的 {@code [x, y, z]}，点了把坐标复制到剪贴板
 * （<b>不再提供传送</b>：搜索只要求规则档位权限，传送属于另一套权限语义，
 * 需要的话玩家自己拿坐标传送）。
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

    private static final String K_USAGE = "command.villager_trade_search.usage";
    private static final String K_EMPTY = "command.villager_trade_search.empty";
    private static final String K_NO_MATCH = "command.villager_trade_search.no_match";
    private static final String K_HEADER = "command.villager_trade_search.header";
    private static final String K_TRUNCATED = "command.villager_trade_search.truncated";
    private static final String K_ENTRY_OFFERS = "command.villager_trade_search.entry.offers";
    private static final String K_MORE_OFFERS = "command.villager_trade_search.entry.more";
    private static final String K_HOVER_COPY_COORDS = "command.villager_trade_search.hover.copy_coords";

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

    // ============================================================
    // 结果拼装
    // ============================================================

    /**
     * 一条结果：{@code #序号 维度 [x, y, z]} + 下一行的商品列表。
     */
    private static MutableComponent entry(VillagerTradeInfo info, int index) {
        MutableComponent line = Component.literal("§7#" + index + " ")
                .append(dimensionName(info.dimension()))
                .append(Component.literal(" §7"))
                .append(coordinateText(info.pos()));

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
     * 坐标：按普通文本显示成 {@code [x, y, z]}，悬停提示、点击把 {@code x, y, z} 复制到剪贴板。
     *
     * <p>只是复制，不执行任何命令 —— 结果行里不会再挂 {@code /ec c TradeSearch ...} 这种
     * 「点了就跑」的命令（传送功能已移除）。
     */
    private static MutableComponent coordinateText(BlockPos pos) {
        String coords = formatPos(pos);
        String hover = ServerTranslations.resolve(Component.translatable(K_HOVER_COPY_COORDS));

        return Component.literal("[" + coords + "]")
                .withStyle(style -> style.withColor(TextColor.fromRgb(0x9CC2E5)))
                .withStyle(style -> style.withClickEvent(new ClickEvent.CopyToClipboard(coords)))
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
        return new StringJoiner(" ")
                .add(String.valueOf(pos.getX()))
                .add(String.valueOf(pos.getY()))
                .add(String.valueOf(pos.getZ()))
                .toString();
    }
}
