package com.villagerdetails.client.network.channel;

import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.channel.SyncPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 同步通道的<b>客户端侧</b>能力（默认方法）。
 *
 * <p>用法：客户端的通道类直接 {@code implements ClientReceiver<XxxPayload>}，
 * 并实现 {@link #id()} 返回包定义里的 {@code XxxPayload.ID}。
 *
 * <p>三端分离之后客户端<b>不再继承</b>服务端的发包通道（{@code network.sync.clientbound} 在
 * server 源集），客户端只依赖「包定义 + 收包接口 + 包类型注册」这些 main 里的契约。
 * 由 {@code ClientChannels} 统一注册一次。
 */
public interface ClientReceiver<P extends SyncPayload<P>> {

    Logger LOG = LogManager.getLogger(ClientReceiver.class);

    /** 这条通道的身份证（父类 SyncChannel 已经实现） */
    PayloadId<P> id();

    /** 客户端收包（自动切回客户端主线程再交给 {@link #handleClient}） */
    default void registerClientReceiver() {
        ClientPlayNetworking.registerGlobalReceiver(id().type(),
                (payload, context) -> context.client().execute(() -> handleClient(context.client(), payload)));
    }

    /** 客户端收到这个包时干什么；只发不收的通道不用管 */
    default void handleClient(Minecraft client, P payload) {
    }

    /**
     * 客户端自己的事件注册（实体卸载清理、tick 检查、JOIN 打招呼……）。
     *
     * <p><b>断线清理不在这里</b>：那是 {@code ClientSessionStates} 统一接管的
     * （{@code VillagerDetailsClient} 里唯一的一处 DISCONNECT 回调），通道自己别各注册一份。
     *
     * <p>刻意和「服务端事件」分开：服务端那边的事件接线在
     * {@code network.sync.event}（{@code SyncEvents}），收包在 {@code network.sync.receiver}
     * （{@code ServerReceiver}）；客户端子类继承的是同一个通道类，混在一起会在集成服里被注册两遍。
     */
    default void registerClientEvents() {
    }

    /**
     * 给「只有静态发送方法」的场景用：不持有通道实例也能发（拿包的 type 直接判 + 发）。
     *
     * <p>这是整个客户端<b>唯一</b>的自定义包发送口（{@code ClientPlayNetworking.send} 全项目只出现在这一处）。
     * 服务端没装本 mod 时 {@code canSend} 返回 false，这里直接跳过 —— 不会把包发出去让对端报未知包。
     *
     * <p>异常隔离：本方法会被 tick 监听器、按键回调、界面动作调用，发送失败必须只影响这一次发送，
     * 不能顺着调用栈把客户端 tick 打断。每个包类型只 WARN 一次（首次），之后降 DEBUG。
     *
     * @return 是否真的发出去了
     */
    static boolean send(CustomPacketPayload payload) {
        if (payload == null) {
            return false;
        }
        try {
            if (!ClientPlayNetworking.canSend(payload.type())) {
                return false;
            }
            ClientPlayNetworking.send(payload);
            return true;
        } catch (Exception e) {
            reportFailure(payload, e);
            return false;
        }
    }

    /**
     * 已经报过「发送失败」的包类型
     */
    Set<String> REPORTED_FAILURES = ConcurrentHashMap.newKeySet();

    /**
     * 发送失败的统一上报：每个包类型只 WARN 一次，之后 DEBUG。
     *
     * <p>用类名而不是包 id 做键：包 id 要在 {@code payload.type()} 上取，
     * 而这里可能正是因为 {@code type()} 抛异常才进来的。
     */
    private static void reportFailure(CustomPacketPayload payload, Throwable cause) {
        String key = payload.getClass().getName();
        if (REPORTED_FAILURES.add(key)) {
            LOG.warn("客户端发送 {} 失败（该包此后只在 DEBUG 里提示）：{}", key, cause.toString(), cause);
        } else {
            LOG.debug("客户端发送 {} 失败：{}", key, cause.toString());
        }
    }

    /** 服务器装没装本 mod（按包类型判断） */
    static boolean canSend(CustomPacketPayload.Type<?> type) {
        return ClientPlayNetworking.canSend(type);
    }
}
