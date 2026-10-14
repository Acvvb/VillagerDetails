package com.villagerdetails.rule.descriptor;

import java.util.List;

/**
 * 一条规则的<b>完整视图</b>：服务端按玩家语言翻译好之后整份下发，客户端照着它渲染。
 *
 * <p>存在的理由：配置界面里「服务器 / 服务端配置」这两个分类以前完全由客户端自己的
 * {@code RuleType} / {@code RuleCategoryType} / {@code RuleDataKind} 驱动 —— 客户端版本比服务端旧时，
 * 服务端新增的规则在这三个枚举里都查不到，界面里干脆就没有它。
 *
 * <p>现在这两个分类改成「服务端下发什么就画什么」：
 * <ul>
 *     <li>{@code name} / {@code info} 是<b>服务端已经翻译好的纯文本</b>，
 *         客户端不再拿翻译键去查自己的语言文件（新规则的键在老客户端里本来也不存在）</li>
 *     <li>{@code state} / {@code enabled} / {@code defaultState} 是服务端的权威值，
 *         客户端不再从本地规则镜像里猜</li>
 *     <li>{@code template} 用<b>模板原语</b>描述逐条配置长什么样（纯名单 / id → 数字），
 *         客户端不写死「哪条规则是哪种列表」</li>
 * </ul>
 *
 * <p>客户端写入仍然按 {@code registerName} 走（{@code server_rule_set} / {@code rule_data}），
 * 服务端自己反查规则并做校验，客户端不需要认识这条规则。
 *
 * @param registerName 规则注册名：客户端改规则 / 改数据时原样发回来
 * @param name         服务端翻译好的显示名
 * @param info         服务端翻译好的描述
 * @param categories   属于哪些二级分类（{@code RuleCategoryType} 的注册名，有序）
 * @param state        当前状态值（和 {@code /ec} 命令里用的一致）
 * @param defaultState 重置按钮的目标值（规则自己声明的默认状态）
 * @param enabled      现在是不是开着（服务端算好的，客户端不解析状态值语义）
 * @param switches     快捷开关按钮组；空 = 这一行用普通的「是 / 否」开关
 * @param template     逐条配置的模板；{@code null} = 这条规则不支持逐条配置
 * @param data         逐条配置的当前内容（有序，按 id 排序保证同一份数据每次下发的字节稳定）
 */
public record RuleDescriptor(
        String registerName,
        String name,
        String info,
        List<String> categories,
        String state,
        String defaultState,
        boolean enabled,
        List<Switch> switches,
        ListTemplate template,
        List<DataEntry> data) {

    /**
     * 一个快捷开关按钮。
     *
     * @param value 状态值（点击后原样发给服务端）
     * @param text  服务端翻译好的按钮文字
     */
    public record Switch(String value, String text) {
    }

    /**
     * 逐条配置列表的<b>模板</b>：客户端只认这几种原语，不认识规则语义。
     *
     * @param plain     纯名单（列表页只有 id 一列，没有值控件）
     * @param valueKind 值控件形态
     * @param idSource  id 来源的注册表 id（例如 {@code minecraft:block}），补全 / 校验用；空 = 任意
     */
    public record ListTemplate(boolean plain, ValueKind valueKind, String idSource) {
    }

    /**
     * 值控件形态
     */
    public enum ValueKind {

        /** 没有值（纯名单） */
        NONE,

        /** 非负数字 */
        NUMBER,

        /** 任意文本 */
        TEXT;

        /**
         * 同步用的名字 → 枚举；不认识返回 {@link #TEXT}（当成自由文本，最宽松）
         */
        public static ValueKind byName(String name) {
            if (name == null) {
                return TEXT;
            }
            for (ValueKind kind : values()) {
                if (kind.name().equals(name)) {
                    return kind;
                }
            }
            return TEXT;
        }
    }

    /**
     * 逐条配置里的一条。
     *
     * @param id    id（方块 / 实体类型 / 任意字符串）
     * @param value 值；纯名单固定 {@code "true"}
     */
    public record DataEntry(String id, String value) {
    }

    /**
     * 二级分类页签（决定界面右上那一排按钮的顺序和文字）。
     *
     * @param registerName 分类注册名（稳定 id，记住上次停留位置用）
     * @param text         服务端翻译好的分类名
     */
    public record Category(String registerName, String text) {
    }
}
