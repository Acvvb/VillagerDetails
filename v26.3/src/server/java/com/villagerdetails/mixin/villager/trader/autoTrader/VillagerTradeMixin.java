package com.villagerdetails.mixin.villager.trader.autoTrader;

import net.minecraft.core.Holder;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.trading.TradeCost;
import net.minecraft.world.item.trading.VillagerTrade;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Optional;


@Mixin(VillagerTrade.class)
public interface VillagerTradeMixin {

    /**
     * 付什么（第一样）。{@code VillagerTrade} 在 26.x 里是<b>普通类</b>、字段全 {@code private}，
     * 而且没有任何 getter（只有 {@code getOffer(LootContext)}），所以只能靠 accessor 读。
     *
     * <p>给「Tab 补全」扫 {@code VILLAGER_TRADE} 注册表用：补全要的是交易涉及的物品 id，
     * 不需要真去滚一次交易。
     */
    @Accessor("wants")
    TradeCost getWants();

    /**
     * 可选的第二样付款物（原版大多数交易没有）。
     */
    @Accessor("additionalWants")
    Optional<TradeCost> getAdditionalWants();

    @Accessor("gives")
    ItemStackTemplate getGives();

    /**
     * 26.3 把这个字段从 {@code List<LootItemFunction> givenItemModifiers} 换成了
     * {@code Optional<Holder<LootItemFunction>> givenItemModifier}（单数 + Holder 化）。
     * 方法名保持不变，调用点用 {@code TradePoolChecker#givenModifiers} 抹平差异。
     */

    @Accessor("givenItemModifier")
    Optional<Holder<LootItemFunction>> getGivenItemModifiers();




}
