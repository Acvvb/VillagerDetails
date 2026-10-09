package com.villagerdetails.rule.registry;

import com.villagerdetails.config.impl.BlockCollectableConfig;
import com.villagerdetails.config.impl.BlockExplosionResistanceConfig;
import com.villagerdetails.config.impl.BlockMiningResistanceConfig;
import com.villagerdetails.config.impl.NoSqueezeConfig;
import com.villagerdetails.rule.type.RuleDataKind;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

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

    private static final List<Entry> ENTRIES = List.of(
            new Entry(RuleType.VILLAGER_NO_SQUEEZE, RuleDataKind.ENTITY_LIST,
                    new DataStore(NoSqueezeConfig::toSyncString, NoSqueezeConfig::applyFromSyncString,
                            (id, value) -> NoSqueezeConfig.add(id),
                            NoSqueezeConfig::remove, NoSqueezeConfig::reset),
                    Block::defaultDestroyTime),
            new Entry(RuleType.BLOCK_COLLECTABLE, RuleDataKind.BLOCK_LIST,
                    new DataStore(BlockCollectableConfig::toSyncString, BlockCollectableConfig::applyFromSyncString,
                            (id, value) -> BlockCollectableConfig.add(id),
                            BlockCollectableConfig::remove, BlockCollectableConfig::reset),
                    Block::defaultDestroyTime),
            new Entry(RuleType.BLOCK_MINING_RESISTANCE, RuleDataKind.BLOCK_NUMBER,
                    new DataStore(BlockMiningResistanceConfig::toSyncString,
                            BlockMiningResistanceConfig::applyFromSyncString,
                            (id, value) -> BlockMiningResistanceConfig.put(id, Float.parseFloat(value)),
                            BlockMiningResistanceConfig::remove, BlockMiningResistanceConfig::reset),
                    Block::defaultDestroyTime),
            new Entry(RuleType.BLOCK_EXPLOSION_RESISTANCE, RuleDataKind.BLOCK_NUMBER,
                    new DataStore(BlockExplosionResistanceConfig::toSyncString,
                            BlockExplosionResistanceConfig::applyFromSyncString,
                            (id, value) -> BlockExplosionResistanceConfig.put(id, Float.parseFloat(value)),
                            BlockExplosionResistanceConfig::remove, BlockExplosionResistanceConfig::reset),
                    Block::getExplosionResistance));

    private RuleDataRegistry() {
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

    /**
     * 按注册名反查（客户端同步回来的名字用它）
     */
    public static Entry byName(String registerName) {
        if (registerName == null) {
            return null;
        }
        for (Entry entry : ENTRIES) {
            if (entry.rule().getRegisterName().equals(registerName)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * 客户端：把服务端同步过来的内容写进本地镜像
     */
    public static void applySynced(String registerName, String data) {
        Entry entry = byName(registerName);
        if (entry != null) {
            entry.store().deserializer().accept(data);
        }
    }
}
