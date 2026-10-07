package com.villagerdetails.client.setting.component;

import com.villagerdetails.client.setting.config.UiLayout;

/**
 * 一页行列表的滚动状态（配置主界面的内容行、列表项的详情页共用同一套）。
 *
 * <p><b>滚动量的单位始终是「行」，不是像素</b> —— 换算成像素只由 {@link #offsetPx(int)} 做。
 * （之前就是在这里踩过坑：按行算、按像素画，结果滚到底内容也只挪了几像素，下面的行永远上不来。）
 *
 * <p>滚动是逐帧推进的：界面每帧画一次，而 tick 只有 20Hz，所以推进用引擎传进来的
 * 「这一帧过去了多少 tick」折算成帧率无关的形式，20fps 和 240fps 看到的时长一致。
 */
public final class RowScrollState {

    /** 当前显示出来的滚动位置（行） */
    private float scroll;

    /** 滚动目标：滚轮 / 拖滚动条改的是它，{@link #scroll} 随后平滑地追上去 */
    private float target;

    /** 总共 total 行、一页 perPage 行时，最多能滚几行 */
    public static float maxScroll(int total, int perPage) {
        return Math.max(0, total - perPage);
    }

    public float scroll() {
        return scroll;
    }

    public float target() {
        return target;
    }

    /**
     * 直接设到某一行，不做动画（拖滚动条 / 点轨道时用：要求「手到哪、内容到哪」）。
     */
    public void set(double rows, int total, int perPage) {
        this.scroll = clamp(rows, total, perPage);
        this.target = this.scroll;
    }

    /** 滚指定行数（滚轮 / 翻页按钮），走平滑动画 */
    public void scrollBy(int rows, int total, int perPage) {
        this.target = clamp(this.target + rows, total, perPage);
    }

    /** 把当前位置夹回合法范围（行数变了之后调，比如删掉了一项） */
    public void clampTo(int total, int perPage) {
        this.scroll = clamp(this.scroll, total, perPage);
        this.target = clamp(this.target, total, perPage);
    }

    /**
     * 每帧推进一次。
     *
     * @param frameTicks 这一帧经过了多少个 tick（60fps ≈ 0.33，20fps ≈ 1.0）
     * @return 滚动位置有没有变（没变就不用重摆控件）
     */
    public boolean advance(float frameTicks) {
        if (scroll == target) {
            return false;
        }
        float before = scroll;
        float deltaTicks = Math.clamp(frameTicks, 0.0F, UiLayout.ROW_SCROLL_MAX_FRAME_TICKS);
        // 等价于「每 tick 逼近 ROW_SCROLL_EASE」，但按实际帧间隔折算，帧率高不会滚得更快
        float factor = 1.0F - (float) Math.pow(1.0F - UiLayout.ROW_SCROLL_EASE, deltaTicks);
        scroll += (target - scroll) * factor;
        if (Math.abs(target - scroll) < UiLayout.ROW_SCROLL_EPSILON) {
            scroll = target;
        }
        return scroll != before;
    }

    /** 换算成像素：内容整体往上移这么多 */
    public float offsetPx(int rowHeight) {
        return scroll * rowHeight;
    }

    private static float clamp(double rows, int total, int perPage) {
        return (float) Math.clamp(rows, 0.0D, maxScroll(total, perPage));
    }
}
