package com.villagerdetails.client.network.sync;

import com.villagerdetails.client.network.channel.ClientReceiver;
import com.villagerdetails.network.sync.payload.serverbound.*;
import com.villagerdetails.rule.type.RuleType;

/**
 * 客户端 → 服务端 的几种请求：统一从这里发，省得每个 receiver 各写一遍「判 canSend + send」。
 *
 * <p>这些包服务端才处理，客户端不需要收，所以没有对应的 {@code ClientChannel} 类，
 * 直接用 {@link ClientReceiver#send} 静态发送。
 */
public final class ClientRequests {

    private ClientRequests() {
    }

    /**
     * 请求服务端把规则状态同步一份回来（打开配置界面时用）
     */
    public static void requestRuleSync() {
        ClientReceiver.send(ServerRuleRequestPayload.INSTANCE);
    }

    /**
     * 服务器装没装本 mod（能收我们的规则请求包 = 对方有这套同步机制）
     */
    public static boolean canRequestRuleSync() {
        return ClientReceiver.canSend(ServerRuleRequestPayload.ID.type());
    }

    /**
     * 把某条规则设成指定状态（等价于 {@code /ec <规则> <状态>}）
     */
    public static void sendRuleState(RuleType rule, String state) {
        ClientReceiver.send(new ServerRuleSetPayload(rule.getRegisterName(), state, false));
    }

    /**
     * 恢复某条规则的默认状态
     */
    public static void sendRuleReset(RuleType rule) {
        ClientReceiver.send(new ServerRuleSetPayload(rule.getRegisterName(), "", true));
    }

    /**
     * 把某条规则的某个 id 设成指定值（抗性是数字，可采集 / 无挤压是 true|false）
     */
    public static void sendRuleData(RuleType rule, String id, String value) {
        ClientReceiver.send(new RuleDataPayload(rule.getRegisterName(), id, value, false, false));
    }

    /**
     * 删掉某条规则的某一条数据
     */
    public static void sendRuleDataRemove(RuleType rule, String id) {
        ClientReceiver.send(new RuleDataPayload(rule.getRegisterName(), id, "", true, false));
    }

    /**
     * 清空某条规则的全部数据（等价于 {@code /ec c <规则> reset}）
     */
    public static void sendRuleDataReset(RuleType rule) {
        ClientReceiver.send(new RuleDataPayload(rule.getRegisterName(), "", "", false, true));
    }

    /**
     * 请求服务端清空本机玩家的 BB 选区（配置界面触发按钮用；客户端不自己清缓存，等服务端下发新状态）
     */
    public static void sendBBClear() {
        ClientReceiver.send(BBClearPayload.INSTANCE);
    }

    /**
     * 请求服务端清空本机玩家的 EB（实体 + 方块）选区（同上，本地不自行清空）
     */
    public static void sendEBClear() {
        ClientReceiver.send(EBClearPayload.INSTANCE);
    }
}
