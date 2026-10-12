package com.villagerdetails.rule.type;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import java.util.Locale;

/**
 * 「逐条配置」类规则的<b>数据形态</b>。
 *
 * <p>这个标识是<b>服务端下发时带过来的</b>（见 {@code BlockRuleSyncPayload}），客户端照着它决定
 * 列表页长什么样 —— 客户端不写死「哪条规则是哪种」。
 *
 * <p>两种列表形态：
 * <ul>
 *     <li>{@link #ENTITY_LIST} / {@link #BLOCK_LIST}：<b>纯名单</b>。业务上就是「是 / 否」——
 *         在名单里就是是，不需要值控件，列表页和「配置项 - 列表」那一页完全一致</li>
 *     <li>{@link #BLOCK_NUMBER}：<b>id → 数字</b>（方块抗性）。列表页每行多一个数字输入框</li>
 * </ul>
 */
public enum RuleDataKind {

    /**
     * 实体类型 id 的纯名单（无挤压）
     */
    ENTITY_LIST(true, false),

    /**
     * 方块 id 的纯名单（可采集）
     */
    BLOCK_LIST(true, true),

    /**
     * 方块 id → 非负数字（挖掘 / 爆炸抗性）
     */
    BLOCK_NUMBER(false, true);

    private final boolean plainList;
    private final boolean blockId;

    RuleDataKind(boolean plainList, boolean blockId) {
        this.plainList = plainList;
        this.blockId = blockId;
    }

    /**
     * 纯名单：列表页只有 id 和增删，没有值控件
     */
    public boolean isPlainList() {
        return plainList;
    }

    /**
     * 值是不是数字（键值列表页用数字输入框）
     */
    public boolean isNumber() {
        return !plainList;
    }

    /**
     * id 是不是方块 id（否则是实体类型 id）—— 决定补全范围和校验
     */
    public boolean isBlockId() {
        return blockId;
    }

    /**
     * 这条规则的 id 属于哪个注册表
     */
    public Registry<?> registry() {
        return blockId ? BuiltInRegistries.BLOCK : BuiltInRegistries.ENTITY_TYPE;
    }

    /**
     * id 合法吗（必须是这个注册表里真实存在的条目）
     */
    public boolean isValidId(String id) {
        Identifier identifier = Identifier.tryParse(id == null ? "" : id.trim());
        return identifier != null && registry().containsKey(identifier);
    }

    /**
     * 值合法吗（数字：非负浮点；名单：true / false）
     */
    public boolean isValidValue(String value) {
        String text = value == null ? "" : value.trim();
        if (plainList) {
            return "true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text);
        }
        try {
            float parsed = Float.parseFloat(text);
            return Float.isFinite(parsed) && parsed >= 0.0F;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 规范化成存下去的样子（名单转小写，数字去掉首尾空格）
     */
    public String normalizeValue(String value) {
        String text = value == null ? "" : value.trim();
        return plainList ? text.toLowerCase(Locale.ROOT) : text;
    }

    /**
     * 同步用的名字 → 枚举；不认识返回 null
     */
    public static RuleDataKind byName(String name) {
        if (name == null) {
            return null;
        }
        for (RuleDataKind kind : values()) {
            if (kind.name().equals(name)) {
                return kind;
            }
        }
        return null;
    }
}
