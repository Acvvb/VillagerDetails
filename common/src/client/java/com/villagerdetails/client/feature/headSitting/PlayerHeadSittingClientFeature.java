package com.villagerdetails.client.feature.headSitting;

import com.villagerdetails.client.feature.ClientFeature;
import com.villagerdetails.client.network.channel.ClientReceiver;
import com.villagerdetails.client.setting.type.ConfigEntryType;
import com.villagerdetails.network.sync.payload.serverbound.PlayerHeadSittingAllowPayload;
import com.villagerdetails.rule.playerHeadSitting.PlayerHeadSittingSeat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.List;

/**
 * 客户端的「玩家头顶乘坐」，每个 tick 干两件事：
 *
 * <ol>
 *     <li><b>摆位置</b>：把坐在玩家头上的玩家摆到<b>载具碰撞箱顶</b>；</li>
 *     <li><b>上报同意</b>：把配置里的「允许别人坐到我头上」开关发给服务端。</li>
 * </ol>
 *
 * <p>为什么位置必须客户端自己摆：原版不广播乘客坐标（{@code ServerEntity#sendChanges} 对乘客只发朝向），
 * 每个客户端各自跑 {@code positionRider} 算位置。而「玩家当载具」原版不存在，
 * 那条 {@code tickEntities → tickNonPassenger → tickPassenger → rideTick} 的链实测并不会
 * 把乘客摆上去，所以这里不等原版，直接按碰撞箱摆 —— 和 {@code PlayerHeadSittingSeat} 共用同一份公式，
 * 服务端算出来的位置和画面永远一致。
 *
 * <p>同意开关每个 tick 读一次（就是一次 map 查表），只在「值变了」或者「换了连接」时才发包：
 * 进服第一帧会把当前值发一遍，之后改配置也会立刻同步，服务端那边不会因为丢包而多问。
 *
 * <p>放在 {@code client.feature} 包下 = 被 {@code ClientFeatureScanner} 自动注册。
 */
public final class PlayerHeadSittingClientFeature implements ClientFeature {

    public static final PlayerHeadSittingClientFeature INSTANCE = new PlayerHeadSittingClientFeature();

    private PlayerHeadSittingClientFeature() {
    }

    /**
     * 上一次成功发出去的同意值；{@code null} = 这条连接还没发过
     */
    private static Boolean sentAllow;

    /**
     * 上一次发包时用的连接。换了连接（重进服务器 / 换世界）就当作没发过，
     * 免得「上个服已经通知过 true」导致这个服一直没记录 —— 服务端是按连接存的。
     */
    private static ClientPacketListener sentOn;

    @Override
    public void register() {
        ClientTickEvents.END_CLIENT_TICK.register(PlayerHeadSittingClientFeature::onEndTick);
    }

    private static void onEndTick(Minecraft client) {
        ClientPacketListener connection = client.getConnection();
        if (connection != sentOn) {
            sentOn = connection;
            sentAllow = null;
        }
        syncAllowFlag(connection);
        positionPassengers(client);
    }

    // ==============================================================
    // 上报「允许别人坐到我头上」
    // ==============================================================

    private static void syncAllowFlag(ClientPacketListener connection) {
        if (connection == null) {
            return;
        }
        // 对面是原版服务端（没装本 mod）：这个包根本发不出去，也不用记状态
        if (!ClientReceiver.canSend(PlayerHeadSittingAllowPayload.ID.type())) {
            return;
        }
        boolean allow = ConfigEntryType.ALLOW_PLAYER_HEAD_SITTING.getToggle();
        if (sentAllow != null && sentAllow == allow) {
            return;
        }
        if (ClientReceiver.send(new PlayerHeadSittingAllowPayload(allow))) {
            sentAllow = allow;
        }
    }

    // ==============================================================
    // 摆乘客位置
    // ==============================================================

    /**
     * 只遍历玩家列表（几个人），没有玩家载具时连内层循环都不进
     */
    private static void positionPassengers(Minecraft client) {
        ClientLevel level = client.level;
        if (level == null) {
            return;
        }
        for (AbstractClientPlayer vehicle : level.players()) {
            if (vehicle.getPassengers().isEmpty()) {
                continue;
            }
            // getPassengers() 是不可变快照，但摆位置过程中集合可能变，拷一份更稳
            for (Entity passenger : List.copyOf(vehicle.getPassengers())) {
                if (passenger instanceof Player) {
                    PlayerHeadSittingSeat.snapToSeat(passenger, vehicle);
                }
            }
        }
    }
}
