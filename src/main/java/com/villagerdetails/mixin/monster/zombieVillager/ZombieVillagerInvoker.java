package com.villagerdetails.mixin.monster.zombieVillager;

import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.UUID;

/**
 * 把僵尸村民的私有「开始治愈」入口借出来。
 *
 * <p>{@code startConverting(UUID, int)} 是 private，原版只在「虚弱 + 金苹果」那条路里调它；
 * 「命名牌写 help 秒治愈」需要从外面启动同一套流程，所以用一个 {@code @Invoker} 拿到调用入口
 * （和 {@code craft.AbstractCraftingMenuAccessor} 拿到槽位的做法一样）。
 *
 * <p><b>只是拿到入口，不改方法体</b>：原版 {@code mobInteract} 里那个
 * {@code 2401 + random.nextInt(3600)} 的治愈时长一个字没动，别的僵尸村民照旧慢慢治。
 * 转换时长是<b>实体实例字段</b>，我们传 0 只影响被贴了 help 的那一只。
 */
@Mixin(ZombieVillager.class)
public interface ZombieVillagerInvoker {

    /**
     * 让这只僵尸村民开始治愈，并把它剩下的治愈时间设为 {@code conversionTime} tick（0 = 下一 tick 完成）。
     */
    @Invoker("startConverting")
    void villagerdetails$startConverting(UUID conversionStarter, int conversionTime);
}
