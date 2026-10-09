package com.villagerdetails.client.network.sync;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.client.network.channel.ClientReceiver;
import com.villagerdetails.client.setting.network.ServerRuleClientState;
import com.villagerdetails.network.sync.payload.clientbound.BlockRuleSyncPayload;
import com.villagerdetails.network.sync.clientbound.BlockRuleSyncChannel;
import com.villagerdetails.rule.type.RuleDataKind;
import com.villagerdetails.rule.registry.RuleDataRegistry;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.client.Minecraft;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 逐条配置数据的客户端侧。
 *
 * <p>两件事：
 * <ul>
 *     <li>写进各配置的本地镜像，供客户端 {@code getDestroyProgress} 等游戏逻辑使用
 *         （走 {@link RuleDataRegistry}，两边同一张表）</li>
 *     <li>把「注册名 → 列表形态 + 内容」存进 {@link ServerRuleClientState}，
 *         界面照着列表形态渲染 —— 列表形态是服务端带过来的标识，客户端不写死</li>
 * </ul>
 */
public final class BlockRuleClientChannel extends BlockRuleSyncChannel
        implements ClientReceiver<BlockRuleSyncPayload> {

    public static final BlockRuleClientChannel INSTANCE = new BlockRuleClientChannel();

    private BlockRuleClientChannel() {
    }

    @Override
    public void handleClient(Minecraft client, BlockRuleSyncPayload payload) {
        Map<RuleType, ServerRuleClientState.RuleData> synced = new HashMap<>();

        for (BlockRuleSyncPayload.RuleData entry : payload.ruleData()) {
            RuleType type = RuleType.getRuleTypeByRegisterName(entry.registerName());
            RuleDataKind kind = RuleDataKind.byName(entry.kind());
            if (type == null || kind == null) {
                continue;
            }
            // 先写进本地镜像（落盘由服务端负责，客户端这边只覆盖内存）
            RuleDataRegistry.applySynced(entry.registerName(), entry.data());
            // 开关状态也照服务端给的写：规则状态那份同步按权限过滤，没权限的玩家收不到，
            // 但本地预测要用（比如「无挤压」关掉之后客户端也不该再拦推动）。
            // 只在「服务端说关了」或「本地还以为是关的」时动，避免把 ops 这类权限等级覆盖成 true。
            if (!entry.enabled() || !RuleCache.isEnabled(type)) {
                RuleCache.applyClientMirror(type, Boolean.toString(entry.enabled()));
            }
            synced.put(type, new ServerRuleClientState.RuleData(kind, parse(kind, entry.data())));
        }

        ServerRuleClientState.setRuleData(synced);
        // 让开着的配置界面重建一次：列表按钮上的预览要跟着刷新
        ServerRuleClientState.bumpVersion();
    }

    /**
     * 把同步字符串解析成 {@code id → 值}。
     *
     * <p>纯名单是 {@code id,id}（值一律 {@code "true"}），键值是 {@code id=值,id=值}。
     */
    private static LinkedHashMap<String, String> parse(RuleDataKind kind, String data) {
        LinkedHashMap<String, String> parsed = new LinkedHashMap<>();
        if (data == null || data.isEmpty()) {
            return parsed;
        }
        for (String part : data.split(",")) {
            String text = part.trim();
            if (text.isEmpty()) {
                continue;
            }
            if (kind.isPlainList()) {
                parsed.put(text, "true");
                continue;
            }
            int eq = text.indexOf('=');
            if (eq <= 0 || eq == text.length() - 1) {
                continue;
            }
            parsed.put(text.substring(0, eq).trim(), text.substring(eq + 1).trim());
        }
        return parsed;
    }
}
