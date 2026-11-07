package com.villagerdetails.mixin.entity.villager.trader.tradeSearch;

import com.villagerdetails.handler.entity.villager.trade.VillagerTradePipeline;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 玩家<b>打开</b>村民交易界面时，顺手让交易索引抓一次快照。
 *
 * <p>注入点选 {@code AbstractVillager#setTradingPlayer} 而不是 {@code Villager#startTrading}：
 * <ul>
 *     <li>{@code setTradingPlayer} 是 {@code Merchant} 接口的方法，村民 / 流浪商人都走它，
 *         而且原版 {@code startTrading} 的第一件事就是调它 —— 此刻交易表一定已经就绪；</li>
 *     <li>{@code startTrading} 在 {@code Villager} 里是 {@code private}，注入它要跟具体实现绑定；
 *         走接口方法更稳，也顺手覆盖了「其它模组直接调 setTradingPlayer」的路径。</li>
 * </ul>
 *
 * <p><b>只在服务端抓</b>：{@code mobInteract} 两端都会跑，客户端那份如果也去抓，
 * 等于在客户端线程上读交易表 + 写服务端缓存。这里用 {@code instanceof ServerLevel} 挡住，
 * 万一将来注入点被客户端触发也不会跨端写数据（{@code onTradeOpen} 里还有第二次同样的判断）。
 *
 * <p>防抖（100 tick）与规则开关都在 {@link VillagerTradePipeline#onTradeOpen(Villager)} 里，
 * 本 Mixin 只负责「什么时候该看一眼」。
 */
@Mixin(AbstractVillager.class)
public abstract class TradeOpenMixin {

    @Inject(method = "setTradingPlayer", at = @At("HEAD"))
    private void villagerdetails$captureOnTradeOpen(Player player, CallbackInfo ci) {
        // null = 关闭界面（stopTrading 会把 tradingPlayer 置空），不是「打开」
        if (player == null) {
            return;
        }
        // 只关心村民；流浪商人等其它 AbstractVillager 子类不参与交易索引。
        // （Mixin 类在编译期并不是 AbstractVillager 的子类型，所以先转成 Object 再做 instanceof）
        AbstractVillager self = (AbstractVillager) (Object) this;
        if (!(self instanceof Villager villager)) {
            return;
        }
        // 必须只在服务端执行
        if (!(villager.level() instanceof ServerLevel)) {
            return;
        }
        VillagerTradePipeline.onTradeOpen(villager);
    }
}
