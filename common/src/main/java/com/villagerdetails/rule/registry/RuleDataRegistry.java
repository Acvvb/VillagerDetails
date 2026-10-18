package com.villagerdetails.rule.registry;

import com.villagerdetails.config.impl.section.base.IdSetConfig;
import com.villagerdetails.config.impl.section.item.BlockCollectableConfig;
import com.villagerdetails.config.impl.section.item.BlockExplosionResistanceConfig;
import com.villagerdetails.config.impl.section.item.BlockMiningResistanceConfig;
import com.villagerdetails.config.impl.section.item.BlockStrongPowerConfig;
import com.villagerdetails.config.impl.section.item.MobNoSpawnConfig;
import com.villagerdetails.config.impl.section.item.NoSqueezeConfig;
import com.villagerdetails.rule.type.RuleConfigKind;
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
     * 纯名单（{@code Set<Identifier>} 形态）的写入：<b>值是真的要看的</b>。
     *
     * <p>以前是 {@code (id, _) -> cfg.add(id)} —— 把值丢掉、一律当 {@code true}。
     * 界面上的「是 / 否」开关拨到「否」时发的就是 {@code false}，忽略值会让它反而把 id
     * 加进名单，所以这里必须把 {@code false} 落成「从名单移除」。
     *
     * <p>集合里存不下显式的 {@code false}，所以「不在名单里」就是「值是 false」，
     * 反过来「值是 false」也就是「不在名单里」—— 两边是同一件事。
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

    private static final List<Entry> ENTRIES = List.of(
            new Entry(RuleType.VILLAGER_NO_SQUEEZE, RuleDataKind.ENTITY_LIST,
                    new DataStore(NoSqueezeConfig.INSTANCE::toSyncString,
                            NoSqueezeConfig.INSTANCE::applyFromSyncString,
                            setFlag(NoSqueezeConfig.INSTANCE),
                            NoSqueezeConfig.INSTANCE::remove,
                            NoSqueezeConfig.INSTANCE::reset),
                    Block::defaultDestroyTime),

            // 禁止生成：和「无挤压」完全同一种形态（实体类型 id 纯名单），只是语义反过来（名单里的不生成）
            new Entry(RuleType.MOB_NO_SPAWN, RuleDataKind.ENTITY_LIST,
                    new DataStore(MobNoSpawnConfig.INSTANCE::toSyncString,
                            MobNoSpawnConfig.INSTANCE::applyFromSyncString,
                            setFlag(MobNoSpawnConfig.INSTANCE),
                            MobNoSpawnConfig.INSTANCE::remove,
                            MobNoSpawnConfig.INSTANCE::reset),
                    Block::defaultDestroyTime),

            new Entry(RuleType.BLOCK_COLLECTABLE, RuleDataKind.BLOCK_LIST,
                    new DataStore(BlockCollectableConfig.INSTANCE::toSyncString,
                            BlockCollectableConfig.INSTANCE::applyFromSyncString,
                            setFlag(BlockCollectableConfig.INSTANCE),
                            BlockCollectableConfig.INSTANCE::remove,
                            BlockCollectableConfig.INSTANCE::reset),
                    Block::defaultDestroyTime),

            // 方块强充能：和「可采集」完全同一种形态（方块 id 纯名单），所以也是同一套模板 / 同一套界面
            new Entry(RuleType.BLOCK_STRONG_POWER, RuleDataKind.BLOCK_LIST,
                    new DataStore(BlockStrongPowerConfig.INSTANCE::toSyncString,
                            BlockStrongPowerConfig.INSTANCE::applyFromSyncString,
                            setFlag(BlockStrongPowerConfig.INSTANCE),
                            BlockStrongPowerConfig.INSTANCE::remove,
                            BlockStrongPowerConfig.INSTANCE::reset),
                    Block::defaultDestroyTime),

            new Entry(RuleType.BLOCK_MINING_RESISTANCE, RuleDataKind.BLOCK_NUMBER,
                    new DataStore(BlockMiningResistanceConfig.INSTANCE::toSyncString,
                            BlockMiningResistanceConfig.INSTANCE::applyFromSyncString,
                            (id, value) -> BlockMiningResistanceConfig.INSTANCE.put(id, Float.parseFloat(value)),
                            BlockMiningResistanceConfig.INSTANCE::remove,
                            BlockMiningResistanceConfig.INSTANCE::reset),
                    Block::defaultDestroyTime),

            new Entry(RuleType.BLOCK_EXPLOSION_RESISTANCE, RuleDataKind.BLOCK_NUMBER,
                    new DataStore(BlockExplosionResistanceConfig.INSTANCE::toSyncString,
                            BlockExplosionResistanceConfig.INSTANCE::applyFromSyncString,
                            (id, value) -> BlockExplosionResistanceConfig.INSTANCE.put(id, Float.parseFloat(value)),
                            BlockExplosionResistanceConfig.INSTANCE::remove,
                            BlockExplosionResistanceConfig.INSTANCE::reset),
                    Block::getExplosionResistance)
    );

    private RuleDataRegistry() {
    }

    /**
     * 启动自检：{@link RuleConfigKind#needsSync()} 和 {@link #ENTRIES} 必须一一对应。
     *
     * <p>「哪条规则是逐条配置类」现在声明在两处（规则自己标 {@code RuleConfigKind.List}，
     * 这里登记 {@code Entry}）。两边一旦不一致，症状是「客户端列表页空白」「规则开关不触发同步」
     * 这种上线之后才发现的静默故障，所以这里直接把它变成启动即崩。
     *
     * <p>由主类 {@code onInitialize} 调一次（不放在 static 块里：类初始化是懒的，
     * 会等到第一个玩家进服才跑，那就失去「启动即崩」的意义了）。
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
