package com.villagerdetails.handler.entity.villager.trade;

import com.villagerdetails.cache.impl.VillagerTradeCache;
import com.villagerdetails.config.impl.IdTranslationConfig;
import com.villagerdetails.pojo.dto.TradeItemInfo;
import com.villagerdetails.pojo.dto.VillagerTradeInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;

import static com.villagerdetails.util.SendMessengerUtils.sendOrBroadcast;

/**
 * {@code /ec c TradeSearch} 的业务实现：<b>只查内存缓存</b>，不遍历世界。
 *
 * <p>结果按「最多 {@value #MAX_RESULTS} 个村民、每个村民最多 {@value #MAX_OFFERS_PER_ENTRY} 笔
 * <b>命中的</b>交易」截断，防止聊天栏刷屏；每个村民都会带上 UUID（点一下复制完整值），
 * 坐标同样可点击复制（<b>不再提供传送</b>：搜索只要求规则档位权限，传送属于另一套权限语义，
 * 需要的话玩家自己拿坐标传送）。
 *
 * <p><b>服务端不拼物品名</b>：索引里只有 id，展示全部用
 * {@code Component.translatable("item.<ns>.<path>")} 发下去，由客户端按自己的语言渲染。
 * 这样中文客户端看到「书架」、英文客户端看到 {@code Bookshelf}，而服务端不需要任何语言表。
 */
public final class VillagerTradeSearchHandler {

    private VillagerTradeSearchHandler() {
    }

    /**
     * 一次最多显示多少条村民（防刷屏）。
     */
    public static final int MAX_RESULTS = 10;

    /**
     * 一条结果里最多列几笔<b>命中的</b>交易，多出来的折叠成「另有 N 件」。
     */
    public static final int MAX_OFFERS_PER_ENTRY = 6;

    private static final String K_USAGE = "command.villager_trade_search.usage";
    private static final String K_EMPTY = "command.villager_trade_search.empty";
    private static final String K_NO_MATCH = "command.villager_trade_search.no_match";
    private static final String K_HEADER = "command.villager_trade_search.header";
    private static final String K_TRUNCATED = "command.villager_trade_search.truncated";
    private static final String K_ENTRY_USES = "command.villager_trade_search.entry.uses";
    private static final String K_MORE_OFFERS = "command.villager_trade_search.entry.more";
    private static final String K_HOVER_COPY_COORDS = "command.villager_trade_search.hover.copy_coords";
    private static final String K_HOVER_COPY_UUID = "command.villager_trade_search.hover.copy_uuid";

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

        List<VillagerTradeCache.SearchResult> matches = VillagerTradeCache.INSTANCE.search(trimmed);
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
        // 玩家：物品名发 translatable，客户端自己本地化；控制台 / 命令方块：没有客户端语言表，
        // 用服务端别名表（trade_index.json + 内置）兜底，免得日志里全是 item.minecraft.xxx
        boolean console = player == null;
        MutableComponent out = Component.translatable(
                K_HEADER, displayKeyword, String.valueOf(matches.size()), String.valueOf(shown));
        for (int i = 0; i < shown; i++) {
            out.append("\n").append(entry(matches.get(i), i + 1, console));
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
     * 一条结果：
     * <pre>
     * #1 主世界 [305, 64, 227] UUID: c49fcd79
     *   9 绿宝石 -> 1 书架（可交易 12 次）
     *   18 绿宝石 + 1 书 -> 1 附魔书[经验修补]（可交易 12 次）
     * </pre>
     *
     * <p>只打<b>命中的那几笔交易</b>（{@code matchedOffers}），不再把整张交易表倒出来 ——
     * 搜「绿宝石」时一个商人十几笔没命中的泥土 / 面包会把重点埋掉。
     */
    private static MutableComponent entry(VillagerTradeCache.SearchResult result, int index, boolean console) {
        VillagerTradeInfo info = result.info();
        MutableComponent line = Component.literal("§7#" + index + " ")
                .append(dimensionName(info.dimension()))
                .append(Component.literal(" §7"))
                .append(coordinateText(info.pos()))
                .append(Component.literal(" §7"))
                .append(uuidText(info.uuid()));

        List<TradeItemInfo> offers = result.matchedOffers();
        int limit = Math.min(MAX_OFFERS_PER_ENTRY, offers.size());
        for (int i = 0; i < limit; i++) {
            // 同一村民的多笔命中交易逐行打印（搜「绿宝石」时「收绿宝石」和「卖绿宝石」各一行）
            line.append("\n  ").append(offerLine(offers.get(i), console));
        }
        if (offers.size() > limit) {
            line.append("\n  ").append(Component.translatable(K_MORE_OFFERS, String.valueOf(offers.size() - limit)));
        }
        return line;
    }

    /**
     * 村民 UUID：显示前 8 位，<b>点击复制完整 UUID</b>（悬停提示）。
     *
     * <p>缩短只是为了让第一行别被 36 位 UUID 撑得太长；真正要用的时候点一下就拿到全的。
     */
    private static MutableComponent uuidText(UUID uuid) {
        String full = uuid.toString();
        String shortId = full.substring(0, 8);
        return Component.literal("UUID: " + shortId)
                .withStyle(style -> style.withColor(TextColor.fromRgb(0x9CC2E5)))
                .withStyle(style -> style.withClickEvent(new ClickEvent.CopyToClipboard(full)))
                .withStyle(style -> style.withHoverEvent(
                        new HoverEvent.ShowText(Component.translatable(K_HOVER_COPY_UUID))));
    }

    /**
     * 一笔交易 → 组件：{@code 1 绿宝石 + 1 书 -> 1 附魔书[经验修补]（可交易 12 次）}。
     *
     * <p><b>玩家看到的名字一律发 {@code Component.translatable}</b>，由<b>客户端</b>按自己的语言表
     * 解析：中文客户端显示「书架」，英文客户端显示 {@code Bookshelf} —— 服务端一行语言表都不需要，
     * 而且旧索引记录也会立刻跟着客户端语言走（服务端只存 id，不存名字）。
     *
     * @param console 控制台 / 命令方块等没有客户端语言表的调用方；此时改用服务端别名表兜底
     */
    private static MutableComponent offerLine(TradeItemInfo offer, boolean console) {
        MutableComponent line = Component.literal("§f")
                .append(Component.literal(offer.price1Count() + " "))
                .append(itemName(offer.price1Id(), console));
        if (offer.hasSecondPrice()) {
            line.append(Component.literal(" + " + offer.price2Count() + " "))
                    .append(itemName(offer.price2Id(), console));
        }
        line.append(Component.literal(" -> " + offer.resultCount() + " "))
                .append(itemName(offer.resultItemId(), console));

        if (!offer.enchantments().isEmpty()) {
            line.append("[");
            for (int i = 0; i < offer.enchantments().size(); i++) {
                if (i > 0) {
                    line.append(", ");
                }
                line.append(enchantmentName(offer.enchantments().get(i), console));
            }
            line.append("]");
        }

        if (offer.maxUses() > 0) {
            line.append(" ").append(Component.translatable(K_ENTRY_USES, String.valueOf(offer.maxUses())));
        }
        return line;
    }

    /**
     * 物品 id → 展示名。
     *
     * <ul>
     *     <li>玩家：拿注册表里的 {@link Item} 实例，用原版 {@link Item#getDescriptionId()} 取
     *         <b>正确的翻译键</b> —— 方块类物品（{@code BlockItem}，例如书架、带釉陶瓦）用的是
     *         {@code block.minecraft.*}，普通物品才是 {@code item.minecraft.*}。手拼 {@code item.}
     *         前缀会让方块类物品在客户端显示成原始键，这正是「白色带釉陶瓦显示
     *         {@code item.minecraft.white_glazed_terracotta}」的原因；</li>
     *     <li>控制台：走服务端别名表（{@code trade_index.json} + 内置兜底），保证日志里是
     *         「绿宝石」而不是 {@code item.minecraft.emerald}。</li>
     * </ul>
     *
     * <p>id 写坏 / 注册表里没有（模组物品被卸载）时退回手拼 {@code item.<ns>.<path>}，
     * 不让一行结果炸掉整条消息。
     */
    private static MutableComponent itemName(String itemId, boolean console) {
        Identifier id = itemId == null ? null : Identifier.tryParse(itemId);
        if (id == null) {
            return Component.literal(String.valueOf(itemId));
        }
        if (!console) {
            Item item = BuiltInRegistries.ITEM.getValue(id);
            if (item != null && item != Items.AIR) {
                // 原版自己知道该用 item. 还是 block. 前缀（BlockItem 走 block.minecraft.*）
                return Component.translatable(item.getDescriptionId());
            }
            // 注册表里找不到：退回手拼
            return Component.translatable("item." + id.getNamespace() + "." + id.getPath());
        }
        String alias = IdTranslationConfig.displayNameOf(id);
        return Component.literal(alias != null && !alias.isBlank() ? alias : itemId);
    }

    /**
     * 附魔 → 展示名：名称用 {@code enchantment.<ns>.<path>}，等级 &gt; 1 时追加
     * {@code enchantment.level.N}（原版自带 1~10，客户端渲染成 I / II / III）。
     *
     * <p><b>为什么这里手拼键、不走注册表</b>：1.21.9+ 附魔已经是数据驱动的动态注册表，
     * {@code BuiltInRegistries} 里<b>没有</b> {@code ENCHANTMENT} 入口（只有 {@code ITEM}），
     * 要拿实例得走 {@code server.registryAccess()}。而附魔的翻译键恒为
     * {@code enchantment.<ns>.<path>}（原版与模组都遵守这个约定），手拼是安全的 ——
     * 真正有「两套前缀」歧义的只有物品（item / block）。
     */
    private static MutableComponent enchantmentName(TradeItemInfo.EnchantmentEntry enchantment, boolean console) {
        Identifier id = Identifier.tryParse(enchantment.id());
        MutableComponent name;
        if (!console) {
            name = id == null
                    ? Component.literal(enchantment.id())
                    : Component.translatable("enchantment." + id.getNamespace() + "." + id.getPath());
        } else {
            // 控制台：别名表里附魔的中文叫法（trade_index.json 的默认表就是一张附魔表）
            String alias = id == null ? null : IdTranslationConfig.displayNameOf(id);
            name = Component.literal(alias != null && !alias.isBlank() ? alias : enchantment.id());
        }
        if (enchantment.level() > 1) {
            name.append(" ").append(console
                    ? Component.literal(String.valueOf(enchantment.level()))
                    : Component.translatable("enchantment.level." + enchantment.level()));
        }
        return name;
    }

    /**
     * 坐标：按普通文本显示成 {@code [x, y, z]}，悬停提示、点击把 {@code x, y, z} 复制到剪贴板。
     *
     * <p>悬停提示也发 {@code Component.translatable}（而不是服务端解析好的字符串），
     * 这样它同样跟随客户端语言。
     *
     * <p>只是复制，不执行任何命令 —— 结果行里不会再挂 {@code /ec c TradeSearch ...} 这种
     * 「点了就跑」的命令（传送功能已移除）。
     */
    private static MutableComponent coordinateText(BlockPos pos) {
        String coords = formatPos(pos);

        return Component.literal("[" + coords + "]")
                .withStyle(style -> style.withColor(TextColor.fromRgb(0x9CC2E5)))
                .withStyle(style -> style.withClickEvent(new ClickEvent.CopyToClipboard(coords)))
                .withStyle(style -> style.withHoverEvent(
                        new HoverEvent.ShowText(Component.translatable(K_HOVER_COPY_COORDS))));
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
