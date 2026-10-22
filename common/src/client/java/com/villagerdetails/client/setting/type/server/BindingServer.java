package com.villagerdetails.client.setting.type.server;

import com.villagerdetails.client.setting.type.style.StyleType;

/**
 * 配置项「行样式 + 数值范围 + 触发动作」的载体。
 *
 * <p>{@code ConfigEntryType} 的每个枚举常量都持有一个 {@code BindingServer}，
 * 由它决定这一行在配置界面里长什么样（开关 / 颜色 / 滑块 / 列表 / 触发按钮），
 * 滑块类还要提供 min / max / step。
 *
 * <p>本接口刻意做成「一堆工厂方法 + 默认值」的形状：
 * <ul>
 *     <li>无参类型（{@link #toggle()} / {@link #color()} / {@link #list()}）直接返回 lambda；</li>
 *     <li>带参类型（{@link #slider} / {@link #trigger}）用匿名类，只覆写需要的那几个方法，
 *         其余走接口默认值。</li>
 * </ul>
 * 这样 {@code ConfigEntryType} 的枚举常量声明可以压成一行，不用为每种样式各写一个实现类。
 */
public interface BindingServer {

    /**
     * 这一行的行样式。
     *
     * <p>注意这里是顶层 {@link StyleType}，不是 {@code SecondCategoryType} 里的同名嵌套类型 ——
     * 行样式由配置项自己声明，不再跟随二级分类。
     */
    StyleType style();

    /**
     * 滑块最小值；非滑块样式忽略此值。
     */
    default double minNumber() {
        return 0.0D;
    }

    /**
     * 滑块最大值；非滑块样式忽略此值。
     */
    default double maxNumber() {
        return 1.0D;
    }

    /**
     * 滑块步进；非滑块样式忽略此值。
     *
     * <p>步进同时影响显示小数位：{@code < 0.1} 时显示两位小数，否则一位
     * （见 {@code ConfigEntryType#formatNumber}）。
     */
    default double stepNumber() {
        return 1.0D;
    }

    // ==============================================================
    // 无参样式：一行就能造出来
    // ==============================================================

    /**
     * 开关样式：行右侧是开 / 关切换，可带快捷键绑定。
     */
    static BindingServer toggle() {
        return () -> StyleType.TOGGLE;
    }

    /**
     * 颜色样式：行右侧是颜色块，点击打开取色器，值形如 {@code #RRGGBB}。
     */
    static BindingServer color() {
        return () -> StyleType.COLOR;
    }

    /**
     * 列表样式：行右侧是预览文本（如 {@code [a,b]}），点击进入列表详情页。
     */
    static BindingServer list() {
        return () -> StyleType.LIST;
    }

    // ==============================================================
    // 动作
    // ==============================================================

    /**
     * 这一行被「激活」时要执行的动作。
     *
     * <p>只有 {@link StyleType#TRIGGER} 样式有意义（点按钮 / 按快捷键触发一次）；
     * 其余样式默认是空操作。开关样式走的是 {@code setToggle}，不走这里。
     */
    default Runnable action() {
        return () -> {
        };
    }

    // ==============================================================
    // 带参样式：匿名类，只覆写需要的部分
    // ==============================================================

    /**
     * 滑块样式，带数值范围。
     *
     * @param min  最小值（含）
     * @param max  最大值（含）
     * @param step 步进；同时决定显示小数位（{@code < 0.1} 显示两位，否则一位）
     */
    static BindingServer slider(double min, double max, double step) {
        return new BindingServer() {
            @Override
            public StyleType style() {
                return StyleType.SLIDER;
            }

            @Override
            public double minNumber() {
                return min;
            }

            @Override
            public double maxNumber() {
                return max;
            }

            @Override
            public double stepNumber() {
                return step;
            }
        };
    }

    /**
     * 触发样式，带要执行的动作。
     *
     * <p>「触发」和「开关」的区别：触发没有持久的开 / 关状态，点一次执行一次
     * （典型如「清除 BB 选区」）；开关有持久的 {@code true/false} 状态。
     *
     * @param action 按钮点击 / 快捷键按下时执行的动作
     */
    static BindingServer trigger(Runnable action) {
        return new BindingServer() {
            @Override
            public StyleType style() {
                return StyleType.TRIGGER;
            }

            @Override
            public Runnable action() {
                return action;
            }
        };
    }
}
