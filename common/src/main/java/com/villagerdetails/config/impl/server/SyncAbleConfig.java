package com.villagerdetails.config.impl.server;

import com.villagerdetails.rule.type.RuleDataKind;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import java.util.function.ToDoubleFunction;

/**
 * 一条「逐条配置」规则的读写 + 自描述接口。
 * 实现类在自己的静态初始化块里调用
 */
public interface SyncAbleConfig {

    /** 对应哪条规则 */
    RuleType ruleType();

    /** 列表形态 */
    RuleDataKind kind();

    /** 数字形态下「原版值」取方块的哪个数；纯名单用不到，可以不覆盖 */
    default ToDoubleFunction<Block> vanillaNumber() {
        return Block::defaultDestroyTime;
    }

    /** 序列化成同步字符串 */
    String toSyncString();

    /** 从同步字符串整份反序列化 */
    void applyFromSyncString(String data);

    /** 新增 / 覆盖一条：列表型规则忽略 value */
    void set(Identifier id, String value);

    /** 删除一条 */
    void remove(Identifier id);

    /** 清空 */
    void reset();
}