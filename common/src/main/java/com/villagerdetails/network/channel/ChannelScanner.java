package com.villagerdetails.network.channel;

import com.villagerdetails.util.PackageScanner;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * 扫包注册的<b>公共底座</b>：服务端 {@code ServerChannels}、客户端 {@code ClientChannels} /
 * {@code ClientSessionStates} / {@code ClientFeatureScanner} 共用。
 *
 * <p>只做几件和具体角色无关的事：
 * <ol>
 *     <li>{@link #forEach} —— 扫一个包，筛出实现某个接口的具体类，实例化后交给回调；</li>
 *     <li>{@link #instanceOf} —— 优先读 {@code public static final INSTANCE}，没有就退回无参构造；</li>
 *     <li>{@link #checkNotEmpty} —— 一个都没扫到必须报错（否则「少注册一块」会静默失效）；</li>
 *     <li>{@link #reportFailures} —— 把这一轮的失败汇总成一条 ERROR。</li>
 * </ol>
 *
 * <p>“扫哪个包、用什么角色接口、注册成什么”仍由两端各自的注册中心决定。
 */
public final class ChannelScanner {

    private ChannelScanner() {
    }

    /**
     * 一次注册失败：哪一步、坏的是谁、为什么。
     *
     * @param stage 阶段（收包 / 事件 / 通道体检 / 包类型……由调用方决定）
     * @param target 坏掉的那个类或包（日志里就靠它定位）
     * @param what 人话说明
     * @param cause 反射 / 实例化抛出来的原始异常，可能为 null
     */
    public record Failure(String stage, String target, String what, Throwable cause) {

        public String describe() {
            return "[" + stage + "] " + target + " —— " + what;
        }
    }

    /**
     * 扫一个包，把实现 {@code role} 的类实例化后交给 {@code action}。
     *
     * <p>顺序：按<b>类名排序</b>后逐个处理。扫包本身拿到的顺序由文件系统 / jar 条目顺序决定
     * （开发环境和打包后不一样），而注册顺序有时是有意义的（比如一串渲染回调的先后），
     * 所以这里统一排一次，保证每次启动完全一致。
     *
     * @param packageName 扫包根（含子包）
     * @param caller 调用方 class，仅用于 {@link PackageScanner} 定位/日志
     * @param role 角色接口，只有它的具体实现会被处理
     * @param action 对每个实例做什么（注册收包 / 注册事件 / 体检……）
     * @param failures 失败收集器；单个类失败记进去并跳过，不影响其它类
     * @return 成功处理了几个
     */
    public static <T> int forEach(String packageName, Class<?> caller, Class<T> role,
                                  Consumer<T> action, List<Failure> failures) {
        List<Class<?>> candidates = new ArrayList<>(PackageScanner.scan(packageName, caller,
                (name, cause) -> failures.add(new Failure(role.getSimpleName(), name,
                        "类加载失败（扫包时被跳过），这项不会生效", cause))));
        candidates.sort(Comparator.comparing(Class::getName));

        int count = 0;
        for (Class<?> candidate : candidates) {
            if (!role.isAssignableFrom(candidate)
                    || candidate.isInterface()
                    || Modifier.isAbstract(candidate.getModifiers())) {
                continue;
            }
            try {
                action.accept(role.cast(instanceOf(candidate)));
                count++;
            } catch (Throwable t) {
                failures.add(new Failure(role.getSimpleName(), candidate.getName(),
                        "注册失败，这项功能不会生效", t));
            }
        }
        return count;
    }

    /**
     * 拿一个类的实例：优先它自己声明的 {@code public static final INSTANCE} 常量（本项目统一写法），
     * 没有就退回无参构造（私有构造也可以，这里会 setAccessible）。
     */
    public static Object instanceOf(Class<?> clazz) throws ReflectiveOperationException {
        try {
            Field field = clazz.getDeclaredField("INSTANCE");
            if (Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                Object value = field.get(null);
                if (value != null) {
                    return value;
                }
            }
        } catch (NoSuchFieldException ignored) {
            // 没有 INSTANCE 就退回无参构造
        }
        Constructor<?> constructor = clazz.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    /**
     * 「一个实现都没扫到」的兜底检查。
     *
     * <p>包名写错、子包没打进产物、接口名改了……这些都不会编译报错，只会让 {@link #forEach}
     * 默默返回 0 —— 然后整块功能静默失效。所以每个注册中心扫完都该问一句「真的扫到东西了吗」。
     *
     * @param found {@link #forEach} 的返回值
     * @param packageName 扫的包（日志里要看到它）
     * @param stage 阶段名，如 "客户端状态扫包"
     */
    public static void checkNotEmpty(int found, String packageName, String stage,
                                     List<Failure> failures) {
        if (found == 0) {
            failures.add(new Failure(stage, packageName,
                    "一个实现都没扫到！检查包名有没有写错、子包有没有被打进包里", null));
        }
    }

    /**
     * 把这一轮的失败汇总成一条 ERROR。
     *
     * <p>注册失败意味着某个包 / 接收器 / 事件<b>静默失效</b>，必须让人一眼看到坏的是哪个；
     * 但这属于「功能残缺」而不是「不该启动」，拦下启动会让整个模组都用不了 ——
     * 所以聚合异常只用来承载完整堆栈，游戏继续跑。
     *
     * @param logger 调用方的 logger（日志里保留两端各自的类名）
     * @param context 上下文前缀，如 "同步注册" / "客户端同步注册"
     */
    public static void reportFailures(Logger logger, String context, List<Failure> failures) {
        if (failures.isEmpty()) {
            return;
        }
        StringBuilder detail = new StringBuilder();
        for (Failure failure : failures) {
            detail.append("\n  - ").append(failure.describe());
        }

        IllegalStateException aggregate = new IllegalStateException(
                context + "失败 " + failures.size() + " 项，下列功能可能失效（游戏继续启动）：" + detail);
        for (Failure failure : failures) {
            if (failure.cause() != null) {
                aggregate.addSuppressed(failure.cause());
            }
        }
        logger.error("{}失败 {} 项（游戏继续启动，但以下内容可能不工作）：{}",
                context, failures.size(), detail, aggregate);
    }
}
