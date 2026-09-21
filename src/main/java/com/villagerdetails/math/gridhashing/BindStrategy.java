package com.villagerdetails.math.gridhashing;


/**
 * 绑定策略接口：定义如何将生物与目标方块绑定
 * <p>
 * 仅保留 bind() 方法，unbind() 和 isBound() 由调用方自行处理，
 * 这样各策略类只需关注绑定逻辑，无需重复实现解除/状态检查。
 * </p>
 *
 * @param <M> 生物类型
 * @param <T> 目标类型
 */
public interface BindStrategy<M extends BindableMob, T extends BindableTarget> {

    /**
     * 执行绑定操作
     *
     * @param mob    生物适配器
     * @param target 目标适配器
     * @return 绑定是否成功
     */
    boolean bind(M mob, T target);
}