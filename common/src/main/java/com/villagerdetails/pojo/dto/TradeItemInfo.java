package com.villagerdetails.pojo.dto;

import java.util.Locale;

/**
 * 村民交易里「卖出去的一件物品」的<b>纯字符串快照</b>。
 *
 * <p>这是整条异步链路里唯一跨越线程边界的东西，所以<b>只允许</b>放不可变的基础类型：
 * 不持有 {@code ItemStack}、{@code MerchantOffer}、{@code Holder<Enchantment>} 等任何原版对象。
 * 子线程只做字符串拼接，主线程继续改交易也不会出现 {@code ConcurrentModificationException}。
 *
 * @param itemId      结果物品的注册 id，例如 {@code minecraft:enchanted_book}
 * @param displayName 物品展示名，例如 {@code 附魔书} / {@code Enchanted Book}
 * @param displayText 格式化好的整段展示文本，例如 {@code 附魔书[经验修补] x1}（直接发聊天栏）
 * @param count       每次交易产出的数量
 * @param enchanted   是否带附魔（附魔书、附魔工具等）
 * @param searchText  已经 lowercase 的搜索文本（id + 展示名 + 附魔 id / 中英文名），
 *                    同时支持「mending」「经验修补」「enchanted_book」这类关键词
 */
public record TradeItemInfo(
        String itemId,
        String displayName,
        String displayText,
        int count,
        boolean enchanted,
        String searchText
) {

    public TradeItemInfo {
        itemId = itemId == null ? "" : itemId;
        displayName = displayName == null ? "" : displayName;
        displayText = displayText == null ? "" : displayText;
        if (count < 0) {
            count = 0;
        }
        if (searchText == null || searchText.isBlank()) {
            searchText = defaultSearchText(itemId, displayName, displayText);
        } else {
            searchText = searchText.toLowerCase(Locale.ROOT);
        }
    }

    /**
     * 只给「不需要额外搜索词」的调用方用的便捷构造。
     */
    public TradeItemInfo(String itemId, String displayName, String displayText, int count, boolean enchanted) {
        this(itemId, displayName, displayText, count, enchanted,
                defaultSearchText(itemId, displayName, displayText));
    }

    /**
     * 关键词是否命中这件物品。
     *
     * <p>匹配范围刻意放宽到「id + 展示名 + 整段展示文本 + 附魔 id / 中英文名」：
     * 玩家搜 {@code enchanted_book}（id）、{@code 附魔书}（展示名）、
     * {@code 经验修补} / {@code mending}（附魔）都应该能命中。
     *
     * @param lowerKeyword 已经 {@code trim + toLowerCase} 过的关键词
     */
    public boolean matches(String lowerKeyword) {
        return lowerKeyword != null && !lowerKeyword.isEmpty() && searchText.contains(lowerKeyword);
    }

    private static String defaultSearchText(String itemId, String displayName, String displayText) {
        return (itemId + " " + displayName + " " + displayText).toLowerCase(Locale.ROOT);
    }
}
