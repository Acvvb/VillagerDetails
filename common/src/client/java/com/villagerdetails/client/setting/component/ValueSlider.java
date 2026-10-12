package com.villagerdetails.client.setting.component;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.NonNull;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

/**
 * 通用数值滑条：把原版滑条的 0~1 比例映射到 {@code [min, max]} 的数值。
 *
 * <p>拖动时实时把值写回配置（不落盘），松手时（{@link #onRelease}）再调用 {@code onCommit} 落盘，
 * 避免拖动过程中反复写文件。
 */
public class ValueSlider extends AbstractSliderButton {

    private final double min;
    private final double max;
    private final double step;
    private final DoubleConsumer setter;
    private final DoubleFunction<Component> formatter;
    private final Runnable onCommit;

    /**
     * @param initialValue 初始值
     *                     <p>这里要调 {@link #updateMessage()}，而它是从 {@code AbstractSliderButton} 继承、
     *                     可以被子类覆盖的方法，所以 javac 会报 {@code this-escape}。这里是安全的：
     *                     {@code updateMessage} / {@code getActualValue} 对还没赋值的字段都做了空值保护
     *                     （父类构造里其实也会回调一次 {@code updateMessage}）。
     */
    @SuppressWarnings("this-escape")
    public ValueSlider(int x, int y, int width, int height,
                       double min, double max, double step,
                       double initialValue, DoubleConsumer setter,
                       DoubleFunction<Component> formatter, Runnable onCommit) {
        // 注意：super 构造里可能会回调 updateMessage()，此时代码下方的字段还没赋值，
        // 所以 updateMessage / actualValue 都做了空值保护。
        super(x, y, width, height, Component.empty(), toRatio(initialValue, min, max));
        this.min = min;
        this.max = max;
        this.step = Math.max(step, 0.0D);
        this.setter = setter;
        this.formatter = formatter;
        this.onCommit = onCommit;
        updateMessage();
    }

    /**
     * 当前滑条对应的实际数值（按步长取整）
     */
    public double getActualValue() {
        if (max <= min) {
            return min;
        }
        double raw = min + (max - min) * this.value;
        if (step > 0.0D) {
            raw = Math.round(raw / step) * step;
        }
        return Math.clamp(raw, min, max);
    }

    /**
     * 外部同步用：把滑条设到指定数值（只更新显示，不回调 setter，避免来回触发）。
     */
    public void setActualValue(double value) {
        setValue(toRatio(value, min, max));
    }

    @Override
    protected void updateMessage() {
        if (formatter == null) {
            setMessage(Component.empty());
            return;
        }
        setMessage(formatter.apply(getActualValue()));
    }

    @Override
    protected void applyValue() {
        if (setter != null) {
            setter.accept(getActualValue());
        }
        updateMessage();
    }

    @Override
    public void onRelease(@NonNull MouseButtonEvent event) {
        super.onRelease(event);
        if (onCommit != null) {
            onCommit.run();
        }
    }

    private static double toRatio(double value, double min, double max) {
        if (max <= min) {
            return 0.0D;
        }
        return Math.clamp((value - min) / (max - min), 0.0D, 1.0D);
    }
}
