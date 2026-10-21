package com.villagerdetails.rule.registry;

import com.villagerdetails.config.ReloadableConfig;
import com.villagerdetails.config.impl.section.base.IdSetConfig;
import com.villagerdetails.config.impl.server.NumberConfigServer;
import com.villagerdetails.rule.type.RuleConfigKind;
import com.villagerdetails.rule.type.RuleDataKind;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;

/**
 * 支持「逐条配置」的规则表。
 *
 * <p>这张表两边共用：服务端按它生成同步条目（注册名 + {@link RuleDataKind} 标识 + 内容），
 * 客户端收到后按注册名反查，写进本地镜像并照着 {@code kind} 渲染列表页 ——
 * <b>客户端不写死「哪条规则是哪种列表」</b>。
 *
 * <p>{@link Entry#vanillaValue(String)} 是新增一条时的原版值预填
 * （和 {@code /ec c} 的原版值补全同一套算法），也放在这张表里，免得客户端再抄一份规则语义。
 */
public final class RuleDataRegistry {

    /**
     * {@code (id, 已规范化的值) → 落库}
     */
    @FunctionalInterface
    public interface Setter {
        void accept(Identifier id, String value);
    }

    /**
     * 一条规则的数据读写（值已经按 kind 校验过，这里只管落库）
     */
    public record DataStore(Supplier<String> serializer,
                            Consumer<String> deserializer,
                            Setter setter,
                            Consumer<Identifier> remover,
                            Runnable register) {
    }

    /**
     * 纯名单（{@code Set<Identifier>} 形态）的写入：<b>值是真的要看的</b>。
     */
    private static Setter setFlag(IdSetConfig config) {
        return (id, value) -> {
            if ("true".equalsIgnoreCase(value)) {
                config.add(id);
            } else {
                config.remove(id);
            }
        };
    }

    /**
     * @param vanillaNumber 数字形态下「原版值」取方块哪个数（纯名单用不到）
     */
    public record Entry(RuleType rule, RuleDataKind kind, DataStore store,
                        ToDoubleFunction<Block> vanillaNumber) {

        /**
         * 新增一条时的原版值预填
         */
        public String vanillaValue(String id) {
            if (kind.isPlainList()) {
                return "true";
            }
            Identifier identifier = Identifier.tryParse(id == null ? "" : id.trim());
            if (identifier == null || !BuiltInRegistries.BLOCK.containsKey(identifier)) {
                return "0.0";
            }
            Block block = BuiltInRegistries.BLOCK.getValue(identifier);
            return String.valueOf(vanillaNumber.applyAsDouble(block));
        }
    }

    /**
     * 动态从 RuleType 枚举中提取所有绑定的 ReloadableConfig，自动构建同步列表。
     */
    private static final List<Entry> ENTRIES = initEntries();

    private static List<Entry> initEntries() {
        List<Entry> entries = new ArrayList<>();

        for (RuleType rule : RuleType.values()) {
            if (!rule.getConfigKind().needsSync()) continue;
            ReloadableConfig config = rule.getReloadableConfig();
            switch (config) {
                case IdSetConfig setConfig -> {
                    RuleDataKind kind = setConfig.configName().toLowerCase().contains("block")
                            ? RuleDataKind.BLOCK_LIST : RuleDataKind.ENTITY_LIST;
                    entries.add(new Entry(rule, kind,
                            new DataStore(setConfig::toSyncString, setConfig::applyFromSyncString,
                                    setFlag(setConfig), setConfig::remove, setConfig::reset),
                            Block::defaultDestroyTime));
                }
                //数字类型
                case NumberConfigServer numberConfigServer -> addFloatEntry(entries, rule, numberConfigServer, Block::defaultDestroyTime);
                case null, default -> {
                }
            }
        }
        return entries;
    }

    /**
     * 统一处理数字（Float）类型配置的 Entry 构建
     */
    private static void addFloatEntry(List<Entry> entries, RuleType rule, NumberConfigServer config, ToDoubleFunction<Block> vanillaNumber) {
        entries.add(new Entry(rule, RuleDataKind.BLOCK_NUMBER,
                new DataStore(config::toSyncString, config::applyFromSyncString,
                        (id, value) -> config.put(id, Float.parseFloat(value)),
                        config::remove, config::reset),
                vanillaNumber));
    }

    private RuleDataRegistry() {
    }

    /**
     * 启动自检：{@link RuleConfigKind#needsSync()} 和 {@link #ENTRIES} 必须一一对应。
     */
    public static void verifyConsistency() {
        for (Entry entry : ENTRIES) {
            if (!entry.rule().getConfigKind().needsSync()) {
                throw new IllegalStateException("RuleDataRegistry 登记了 Entry，但规则没标记为逐条配置类（RuleConfigKind 不是 NONE）："
                        + entry.rule().getRegisterName());
            }
        }
        for (RuleType type : RuleType.values()) {
            if (type.getConfigKind().needsSync() && of(type) == null) {
                throw new IllegalStateException("规则标记为逐条配置类，但 RuleDataRegistry 没有对应 Entry："
                        + type.getRegisterName());
            }
        }
    }

    public static List<Entry> all() {
        return ENTRIES;
    }

    /**
     * 这条规则支不支持逐条配置；不支持返回 null
     */
    public static Entry of(RuleType rule) {
        for (Entry entry : ENTRIES) {
            if (entry.rule() == rule) {
                return entry;
            }
        }
        return null;
    }

}