package com.villagerdetails.config.impl.section.item;

import com.villagerdetails.command.SwitchComponentType;
import com.villagerdetails.config.impl.RuleConfig;
import com.villagerdetails.config.impl.section.base.IdSetConfig;
import com.villagerdetails.config.impl.section.storage.IdSetStorage;
import com.villagerdetails.rule.RuleChangeService;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Set;

import static com.villagerdetails.rule.type.RuleType.VILLAGER_NO_SQUEEZE;

public final class NoSqueezeConfig extends IdSetConfig {

    private NoSqueezeConfig() {
        super(VILLAGER_NO_SQUEEZE.getRegisterName());
    }

    public static final NoSqueezeConfig INSTANCE = new NoSqueezeConfig();

    private static final Logger log = LogManager.getLogger(NoSqueezeConfig.class);

    @Override
    public String configName() {
        return VILLAGER_NO_SQUEEZE.getRegisterName();
    }

    @Override
    public void reload(MinecraftServer server) {
        loadStorage();
        migrateLegacyRuleState(server);
    }

    /**
     * 类型适配：只有这一个方法需要新写。
     */
    public boolean contains(EntityType<?> type) {
        if (type == null) return false;
        return containsId(BuiltInRegistries.ENTITY_TYPE.getKey(type));
    }

    private static void migrateLegacyRuleState(MinecraftServer server) {
        RuleConfig config = RuleConfig.getOrCreate(server);
        String key = VILLAGER_NO_SQUEEZE.getRegisterName();
        String legacy = config.getBindingState(key);
        if (!isLegacyState(legacy)) return;

        Set<Identifier> ids = IdSetStorage.parseIds(legacy);
        if (!ids.isEmpty()) INSTANCE.addAll(ids);

        // 写规则状态统一走 RuleChangeService —— 它会经 RuleCache 落盘，
        // 这里不再自己动 RuleConfig（以前是 setBindingState + setState 写两遍）
        RuleChangeService.change(VILLAGER_NO_SQUEEZE, SwitchComponentType.TRUE_STR);
        log.info("已把「无挤压」规则状态里的老名单迁移进配置（{} 项）", ids.size());
    }

    private static boolean isLegacyState(String state) {
        return state != null && !state.isBlank()
                && SwitchComponentType.getByCommandStr(state.trim()) == null;
    }
}