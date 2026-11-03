package com.villagerdetails.pojo.dto;

import java.util.Locale;

/**
 * 一笔村民交易的<b>纯字符串快照</b>：卖什么、卖多少、要什么价。
 *
 * <p>这是整条异步链路里唯一跨越线程边界的东西，所以<b>只允许</b>放不可变的基础类型：
 * 不持有 {@code ItemStack}、{@code MerchantOffer}、{@code Holder<Enchantment>} 等任何原版对象。
 * 子线程只做字符串拼接，主线程继续改交易也不会出现 {@code ConcurrentModificationException}。
 *
 * <p>同时也是落盘格式的载体（见 {@code VillagerTradeStorage}）：所有字段都是可序列化的标量，
 * 存盘 / 读盘不需要任何额外的转换层。
 *
 * @param resultItemId 卖出的物品 id，例如 {@code minecraft:enchanted_book}
 * @param resultCount  卖出数量
 * @param price1Id     价格 1 的物品 id，例如 {@code minecraft:emerald}
 * @param price1Count  价格 1 的数量
 * @param price2Id     价格 2 的物品 id；原版交易不需要第二样东西时是空串 {@code ""}
 * @param price2Count  价格 2 的数量；没有第二样东西时是 {@code 0}
 * @param displayText  给人看的整段文本，例如 {@code 10 Emerald -> 1 Enchanted Book[经验修补]}（直接发聊天栏）
 * @param searchText   <b>已经 lowercase</b> 的合并搜索文本（结果 / 价格 / 附魔的 id 与叫法 + 展示文本），
 *                     同时支持「mending」「经验修补」「enchanted_book」「绿宝石」这类关键词
 */
public record TradeItemInfo(
        String resultItemId,
        int resultCount,
        String price1Id,
        int price1Count,
        String price2Id,
        int price2Count,
        String displayText,
        String searchText
) {

    public TradeItemInfo {
        resultItemId = resultItemId == null ? "" : resultItemId;
        price1Id = price1Id == null ? "" : price1Id;
        price2Id = price2Id == null ? "" : price2Id;
        displayText = displayText == null ? "" : displayText;
        if (resultCount < 0) {
            resultCount = 0;
        }
        if (price1Count < 0) {
            price1Count = 0;
        }
        if (price2Count < 0) {
            price2Count = 0;
        }
        searchText = searchText == null || searchText.isBlank()
                ? defaultSearchText(resultItemId, price1Id, price2Id, displayText)
                : searchText.toLowerCase(Locale.ROOT);
    }

    /**
     * 这笔交易有没有「第二样价格」。
     */
    public boolean hasSecondPrice() {
        return !price2Id.isBlank() && price2Count > 0;
    }

    /**
     * 关键词是否命中这笔交易。
     *
     * <p>匹配范围是 {@link #searchText} 的包含关系：结果物品（id / 叫法 / 附魔 id 与叫法）、
     * 价格物品（id / 叫法）以及整段展示文本，所以
     * {@code mending} / {@code 经验修补} / {@code enchanted_book} / {@code 绿宝石} 都能命中。
     *
     * @param lowerKeyword 已经 {@code trim + toLowerCase} 过的关键词
     */
    public boolean matches(String lowerKeyword) {
        return lowerKeyword != null && !lowerKeyword.isEmpty() && searchText.contains(lowerKeyword);
    }

    private static String defaultSearchText(String resultItemId, String price1Id, String price2Id,
                                            String displayText) {
        return (resultItemId + " " + price1Id + " " + price2Id + " " + displayText)
                .toLowerCase(Locale.ROOT);
    }
}
