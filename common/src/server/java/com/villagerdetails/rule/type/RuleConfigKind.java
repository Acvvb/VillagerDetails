package com.villagerdetails.rule.type;

/**
 * 规则是不是「逐条配置类」—— 也就是这条规则除了一开一关之外，还维护一份可增删的内容。
 *
 * <p>之所以用枚举而不是 {@code boolean}：这个标识以后大概率还要长（比如「服务端独占、不下发」、
 * 「只下发不回收」之类的形态），到时候在 {@link #needsSync()} 里补一行就行，
 * 调用方一处都不用改；写成 {@code boolean} 就只能全仓库改签名单。
 *
 * <p><b>谁在用</b>：{@code RuleCache} 启动时按它筛出「需要同步的规则」放进不可变列表，
 * {@code BlockRuleSyncEvents} 在规则开/关变化时拿那个列表判断要不要广播配置。
 *
 * <p><b>和 {@link RuleDataKind} 的区别</b>：这个枚举回答的是「<i>要不要</i>同步」，
 * {@link RuleDataKind} 回答的是「同步过去的内容<i>长什么样</i>（纯名单 / id→数字、实体 id / 方块 id）」。
 * 两者配合：{@link #LIST} 的规则在 {@code RuleDataRegistry} 里一定有一条 {@code Entry}。
 */
public enum RuleConfigKind {

    /**
     * 不是逐条配置类：状态只有一开一关（或者一档档的档位），没有列表内容要下发
     */
    NONE,

    /**
     * 逐条配置类：维护一份 id → 值 的列表（比如「无挤压」的实体名单、「挖掘抗性」的方块 id→数字），
     * 内容需要同步给客户端渲染列表页
     */
    LIST;

    /**
     * 这条规则的配置内容要不要下发给客户端。
     *
     * <p><b>规则只有一条：只要不是 {@link #NONE}，就要推送。</b>
     * 以后新增的形态（不管叫 {@code SET} 还是别的什么）默认都走「要推送」这条路，
     * 只有真的「没有内容可发」的才会是 {@link #NONE}。
     * 所以「要不要推送」永远只在这里判断一处，调用方不要去比对具体的枚举值。
     */
    public boolean needsSync() {
        return this != NONE;
    }
}
