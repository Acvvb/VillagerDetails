package com.villagerdetails.handler.entity.villager.trader.refresh;

import net.minecraft.core.HolderSet;
import net.minecraft.world.item.trading.TradeSet;
import net.minecraft.world.item.trading.VillagerTrade;

/**
 * {@link TradeSet} 的版本适配：<b>26.2</b> 是普通类，取交易列表用 {@code getTrades()}；
 * 26.3 改成 record，改名成 {@code trades()}（实现在同名类里）。
 *
 * <p>只为了「Tab 补全扫村民职业的交易集」这一处调用，不值得为此加 mixin。
 */
public final class TradeSetAdapter {

    private TradeSetAdapter() {
    }

    /**
     * 这个交易集里引用的全部交易。
     */
    public static HolderSet<VillagerTrade> trades(TradeSet set) {
        return set == null ? null : set.getTrades();
    }
}
