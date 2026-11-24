package com.villagerdetails.handler.block.pistonPush;

/**
 * 当前这次「活塞结构扫描」是<b>伸出</b>还是<b>缩回</b>。
 *
 * <p>为什么需要它：原版推动和黏回用的是<b>同一个</b> {@code PistonBaseBlock#isPushable}
 * （{@code PistonStructureResolver} 里 4 个调用点全在这个类里），函数的参数里没有
 * 「方向」这一信息可用来分流（{@code direction} / {@code connectionDirection} 的组合在
 * 伸出和缩回时都会出现）。而 {@code PistonStructureResolver} 自己带着
 * {@code extending} 字段 —— 由它的 {@code resolve()} 注入在开头写进来，注入
 * {@code isPushable} 的两条规则再读它决定「这次该用哪条规则」。
 *
 * <p>线程局部：结构扫描只会在服务端主线程跑（单人里就是客户端主线程），
 * 用 ThreadLocal 是为了将来万一有别的线程碰活塞逻辑时不会串味。
 *
 * <p>默认 {@code true}（伸出）：还没被任何一次 {@code resolve()} 覆盖时按推动规则处理，
 * 和「只有结构扫描会设置它」这个事实一致；下一次扫描开头会立刻覆盖掉。
 */
public final class PistonMoveScope {

    private static final ThreadLocal<Boolean> EXTENDING = ThreadLocal.withInitial(() -> Boolean.TRUE);

    private PistonMoveScope() {
    }

    /**
     * 由 {@code PistonStructureResolver#resolve()} 的注入在开头调用
     */
    public static void set(boolean extending) {
        EXTENDING.set(extending);
    }

    /**
     * 这次结构扫描是不是在伸出（推动）
     */
    public static boolean isExtending() {
        return EXTENDING.get();
    }
}
