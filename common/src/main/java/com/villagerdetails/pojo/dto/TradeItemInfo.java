package com.villagerdetails.pojo.dto;

import java.util.List;

/**
 * 一笔村民交易的<b>纯数据快照</b>：卖什么、要什么价、能换几次。
 *
 * <p>这是整条异步链路里唯一跨越线程边界的东西，所以<b>只允许</b>放不可变的基础类型：
 * 不持有 {@code ItemStack}、{@code MerchantOffer}、{@code Holder<Enchantment>} 等任何原版对象。
 * 子线程只做数据搬运，主线程继续改交易也不会出现 {@code ConcurrentModificationException}。
 *
 * <p><b>为什么只存 id、不存展示文本</b>：展示名是「语言相关」的，而存档 / 索引是跨语言的。
 * 以前在这里烘焙 {@code display} / {@code search} 字符串，直接后果是中英混排，而且旧记录
 * 永远不会跟随语言或别名表变化。现在展示交给 {@code Component.translatable("item.<ns>.<path>")}
 * 在<b>客户端</b>本地化，搜索靠 id + 别名表兜底 —— 一份数据对任何语言都成立。
 *
 * <p>同时也是落盘格式的载体（见 {@code VillagerTradeStorage}，{@code format: 2}）：
 * 所有字段都是可序列化的标量 / 小数组，存盘 / 读盘不需要任何额外的转换层。
 *
 * @param resultItemId 卖出的物品 id，例如 {@code minecraft:enchanted_book}
 * @param resultCount  卖出数量
 * @param price1Id     价格 1 的物品 id，例如 {@code minecraft:emerald}
 * @param price1Count  价格 1 的数量（<b>已计入</b>供需涨价与声望折扣）
 * @param price2Id     价格 2 的物品 id；原版交易不需要第二样东西时是空串 {@code ""}
 * @param price2Count  价格 2 的数量；没有第二样东西时是 {@code 0}
 * @param maxUses      这笔交易最多能换几次（{@code MerchantOffer#getMaxUses()}）
 * @param enchantments 卖出物携带的附魔（普通附魔 + 附魔书的存储附魔）；没有就是空列表
 */
public record TradeItemInfo(
        String resultItemId,
        int resultCount,
        String price1Id,
        int price1Count,
        String price2Id,
        int price2Count,
        int maxUses,
        List<EnchantmentEntry> enchantments
) {

    /**
     * 一个附魔：只存 id 和等级，展示名同样由客户端本地化。
     *
     * @param id    附魔注册 id，例如 {@code minecraft:mending}；解析不出来时是空串
     * @param level 等级（{@code >= 1}）
     */
    public record EnchantmentEntry(String id, int level) {
        public EnchantmentEntry {
            id = id == null ? "" : id;
            if (level < 1) {
                level = 1;
            }
        }
    }

    public TradeItemInfo {
        resultItemId = resultItemId == null ? "" : resultItemId;
        price1Id = price1Id == null ? "" : price1Id;
        price2Id = price2Id == null ? "" : price2Id;
        if (resultCount < 0) {
            resultCount = 0;
        }
        if (price1Count < 0) {
            price1Count = 0;
        }
        if (price2Count < 0) {
            price2Count = 0;
        }
        // 「没有第二样价格」统一成 ("", 0)：落盘时不写，比较时不用到处判空
        if (price2Id.isBlank() || price2Count <= 0) {
            price2Id = "";
            price2Count = 0;
        }
        if (maxUses < 0) {
            maxUses = 0;
        }
        enchantments = enchantments == null ? List.of() : List.copyOf(enchantments);
    }

    /**
     * 这笔交易有没有「第二样价格」。
     */
    public boolean hasSecondPrice() {
        return !price2Id.isBlank() && price2Count > 0;
    }

    /**
     * 关键词是否命中这笔交易的<b>原始 id</b>（结果物 / 价格物 / 附魔）。
     *
     * <p>只认 id：名字匹配是「语言相关」的事，而 {@link TradeItemInfo} / {@link VillagerTradeInfo}
     * 是两端共用的 DTO，不能依赖只存在于服务端的别名表。服务端在
     * {@code VillagerTradeCache.search} 里补上「别名表中文名兜底」这一层。
     *
     * @param lowerKeyword 已经 {@code trim + toLowerCase} 过的关键词
     */
    public boolean matchesId(String lowerKeyword) {
        if (lowerKeyword == null || lowerKeyword.isEmpty()) {
            return false;
        }
        if (resultItemId.contains(lowerKeyword)) {
            return true;
        }
        if (price1Id.contains(lowerKeyword)) {
            return true;
        }
        if (!price2Id.isBlank() && price2Id.contains(lowerKeyword)) {
            return true;
        }
        for (EnchantmentEntry enchantment : enchantments) {
            if (!enchantment.id().isBlank() && enchantment.id().contains(lowerKeyword)) {
                return true;
            }
        }
        return false;
    }
}
