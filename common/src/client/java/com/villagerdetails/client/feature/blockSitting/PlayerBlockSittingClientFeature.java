package com.villagerdetails.client.feature.blockSitting;

import com.villagerdetails.client.feature.ClientFeature;
import com.villagerdetails.client.network.channel.ClientReceiver;
import com.villagerdetails.client.setting.type.ConfigEntryType;
import com.villagerdetails.entity.ModEntities;
import com.villagerdetails.network.sync.payload.serverbound.PlayerBlockSittingEnabledPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.entity.NoopRenderer;

/**
 * 客户端的「坐在楼梯 / 半砖上」：两件事 ——
 * <ol>
 *     <li>把「允许坐在楼梯 / 半砖上」（{@code PlayerBlockSitting}，默认开）上报给服务端；</li>
 *     <li>给那个隐形座位实体注册一个空渲染器（不注册会崩，原因见 {@link #register()}）。</li>
 * </ol>
 *
 * <p>能不能坐是服务端判定的（右键方块那条流程在服务端），客户端只用这一个开关表达意愿。
 * 每个 tick 读一次（一次 map 查表），只在「值变了」或「换了连接」时才发包，所以改配置立刻生效、
 * 也不会刷屏。对面是原版服务端时 {@code canSend} 为 false，自动跳过。
 *
 * <p><b>坐姿和位置不用客户端写</b>：座位是普通实体，原版 {@code ClientLevel#tickNonPassenger}
 * 会 tick 它的乘客（{@code Player#rideTick} → {@code positionRider}），位置和坐姿都是原版算的。
 *
 * <p>放在 {@code client.feature} 包下 = 被 {@code ClientFeatureScanner} 自动注册。
 */
public final class PlayerBlockSittingClientFeature implements ClientFeature {

    public static final PlayerBlockSittingClientFeature INSTANCE = new PlayerBlockSittingClientFeature();

    private PlayerBlockSittingClientFeature() {
    }

    /**
     * 上一次成功发出去的值；{@code null} = 这条连接还没发过
     */
    private static Boolean sentEnabled;

    /**
     * 上一次发包时用的连接：换连接（重进服务器 / 换世界）就当作没发过，
     * 因为服务端的记录是按连接存的
     */
    private static ClientPacketListener sentOn;

    @Override
    @SuppressWarnings("deprecation") // EntityRendererRegistry 是 Fabric 目前唯一的实体渲染器注册入口（这版原版 EntityRenderers 没有 register）
    public void register() {
        ClientTickEvents.END_CLIENT_TICK.register(PlayerBlockSittingClientFeature::onEndTick);

        // 座位是隐形实体（尺寸 0×0、不画任何东西），但它一旦出现在客户端的实体列表里就
        // **必须**有一个渲染器：原版 EntityRenderDispatcher#shouldRender 是直接调
        // renderer.shouldRender(...) 的，renderer 为 null 会当场 NPE 把客户端崩掉
        // （实测：一坐下去客户端就 Crash「renderer is null」，然后掉线）。
        // 这里挂原版现成的 NoopRenderer：只有 createRenderState + 空渲染，正好什么都不画。
        EntityRendererRegistry.register(ModEntities.BLOCK_SEAT, NoopRenderer::new);
    }

    private static void onEndTick(Minecraft client) {
        ClientPacketListener connection = client.getConnection();
        if (connection != sentOn) {
            sentOn = connection;
            sentEnabled = null;
        }
        if (connection == null) {
            return;
        }
        // 对面是原版服务端（没装本 mod）：这个包发不出去，也不用记状态
        if (!ClientReceiver.canSend(PlayerBlockSittingEnabledPayload.ID.type())) {
            return;
        }
        boolean enabled = ConfigEntryType.PLAYER_BLOCK_SITTING.getToggle();
        if (sentEnabled != null && sentEnabled == enabled) {
            return;
        }
        if (ClientReceiver.send(new PlayerBlockSittingEnabledPayload(enabled))) {
            sentEnabled = enabled;
        }
    }
}
