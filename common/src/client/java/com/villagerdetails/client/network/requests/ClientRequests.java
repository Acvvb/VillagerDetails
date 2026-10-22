package com.villagerdetails.client.network.requests;

import com.villagerdetails.client.cache.impl.PortalViewClientState;
import com.villagerdetails.client.network.channel.ClientReceiver;
import com.villagerdetails.network.channel.SyncProtocol;
import com.villagerdetails.network.sync.payload.serverbound.*;
import net.minecraft.client.Minecraft;

/**
 * 客户端 → 服务端 的几种请求：统一从这里发，省得每个 receiver 各写一遍「判 canSend + send」。
 *
 * <p>这些包服务端才处理，客户端不需要收，所以没有对应的 {@code ClientChannel} 类，
 * 直接用 {@link ClientReceiver#send} 静态发送。
 *
 * <p>规则相关的请求<b>一律按注册名发</b>：客户端不需要（也不应该）认识这条规则是什么，
 * 校验与语义都在服务端。
 *
 * <p>本类不持有任何会话数据（发完就忘），但仍统一成 {@link #INSTANCE} 单例调用
 * （{@code ClientRequests.INSTANCE.sendHello()}）：和缓存层保持同一套调用姿势，
 * 免得「这行该写静态还是实例」每次都靠记忆。
 */
public final class ClientRequests {

    /**
     * 单例：调用方统一写 {@code ClientRequests.INSTANCE.xxx()}
     */
    public static final ClientRequests INSTANCE = new ClientRequests();

    private ClientRequests() {
    }

    /**
     * 打招呼：声明协议版本 + 界面语言，并顺带要一份描述符（进服、打开配置界面、改了界面语言都发）
     */
    public void sendHello() {
        ClientReceiver.send(new com.villagerdetails.network.sync.payload.serverbound.RuleDescriptorHelloPayload(SyncProtocol.VERSION, currentLanguage()));
    }

    /**
     * 客户端当前界面语言（例如 {@code zh_cn}）；读不到就交空串，服务端会退回自己的当前语言
     */
    public String currentLanguage() {
        return Minecraft.getInstance().getLanguageManager().getSelected();
    }

    /**
     * 服务器装没装本 mod（能收我们的 hello = 对方有这套同步机制）
     */
    public boolean canSync() {
        return ClientReceiver.canSend(RuleDescriptorHelloPayload.ID.type());
    }

    /**
     * 要某条规则的「可选 id + 原版值」清单（打开它的详情页时发一次）
     */
    public void requestRuleDataOptions(String registerName) {
        ClientReceiver.send(new RuleDataOptionsRequestPayload(registerName));
    }

    /**
     * 把某条规则设成指定状态（等价于 {@code /ec <规则> <状态>}）
     */
    public void sendRuleState(String registerName, String state) {
        ClientReceiver.send(new ServerRuleSetPayload(registerName, state, false));
    }

    /**
     * 恢复某条规则的默认状态
     */
    public void sendRuleReset(String registerName) {
        ClientReceiver.send(new ServerRuleSetPayload(registerName, "", true));
    }

    /**
     * 把某条规则的某个 id 设成指定值（抗性是数字，可采集 / 无挤压是 true|false）
     */
    public void sendRuleData(String registerName, String id, String value) {
        ClientReceiver.send(new RuleDataPayload(registerName, id, value, false, false));
    }

    /**
     * 删掉某条规则的某一条数据
     */
    public void sendRuleDataRemove(String registerName, String id) {
        ClientReceiver.send(new RuleDataPayload(registerName, id, "", true, false));
    }

    /**
     * 清空某条规则的全部数据（等价于 {@code /ec c <规则> reset}）
     */
    public void sendRuleDataReset(String registerName) {
        ClientReceiver.send(new RuleDataPayload(registerName, "", "", false, true));
    }

    /**
     * 请求服务端清空本机玩家的 BB 选区（配置界面触发按钮用；客户端不自己清缓存，等服务端下发新状态）
     */
    public void sendBBClear() {
        ClientReceiver.send(BBClearPayload.INSTANCE);
    }

    /**
     * 请求服务端清空本机玩家的 EB（实体 + 方块）选区（同上，本地不自行清空）
     */
    public void sendEBClear() {
        ClientReceiver.send(EBClearPayload.INSTANCE);
    }

    /**
     * 请求服务端清空本机玩家的传送门视图高亮。
     *
     * <p>能发就发给服务端（它回一条空包，和 BB / EB 清除走同一条链路）；
     * 对面没装本 mod 时退回本地清空 —— 这是纯本机视图状态，按钮点不动才是 bug。
     */
    public void sendPortalViewClear() {
        if (!ClientReceiver.send(PortalViewClearPayload.INSTANCE)) {
            PortalViewClientState.INSTANCE.clear();
        }
    }
}
