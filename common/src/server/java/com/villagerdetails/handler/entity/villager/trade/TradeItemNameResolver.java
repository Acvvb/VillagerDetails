package com.villagerdetails.handler.entity.villager.trade;

import com.villagerdetails.pojo.dto.TradeItemInfo;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 主线程侧的「原版对象 → 纯数据」提取器。
 *
 * <p><b>只产出 id / 数量 / 等级，不产出任何展示文本</b>：名字是语言相关的东西，交给客户端用
 * {@code Component.translatable("item.<ns>.<path>")} 本地化，服务端不再维护「英文兜底 + 中文别名」
 * 这一套（以前正是因为把名字烘焙进索引，才出现中英混排、旧记录永远不更新）。
 *
 * <p><b>线程约定</b>：这里的方法只允许在服务端主线程调用 —— 入参是原版对象，只有主线程能安全读。
 * 返回值只有 String / int，之后随便丢给哪个线程都行。
 */
public final class TradeItemNameResolver {

    private TradeItemNameResolver() {
    }

    // ============================================================
    // id
    // ============================================================

    /**
     * 物品栈的注册 id，例如 {@code minecraft:enchanted_book}；空栈返回空串。
     */
    public static String itemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        return itemId(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    /**
     * {@code Holder<Item>}（价格物品走这条路）→ 注册 id；解析不出来时返回空串（不抛）。
     */
    public static String itemId(Holder<Item> holder) {
        if (holder == null) {
            return "";
        }
        return itemId(BuiltInRegistries.ITEM.getKey(holder.value()));
    }

    private static String itemId(Identifier id) {
        return id == null ? "" : id.toString();
    }

    // ============================================================
    // 附魔
    // ============================================================

    /**
     * 物品上的全部附魔（普通附魔 + 附魔书的 {@code STORED_ENCHANTMENTS}），只保留 id 和等级。
     *
     * <p>用 {@link LinkedHashMap} 去重：同一附魔在两张表里都出现时保留第一次（正常不会发生），
     * 最后按 id 排序，保证展示 / 落盘的顺序稳定（{@code Object2IntOpenHashMap} 的迭代顺序
     * 只是当前哈希顺序）。
     */
    public static List<TradeItemInfo.EnchantmentEntry> enchantments(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return List.of();
        }

        Map<String, TradeItemInfo.EnchantmentEntry> collected = new LinkedHashMap<>();
        collectEnchantments(stack.getEnchantments(), collected);

        ItemEnchantments stored = stack.get(DataComponents.STORED_ENCHANTMENTS);
        if (stored != null) {
            collectEnchantments(stored, collected);
        }

        List<TradeItemInfo.EnchantmentEntry> sorted = new ArrayList<>(collected.values());
        sorted.sort(Comparator.comparing(TradeItemInfo.EnchantmentEntry::id));
        return List.copyOf(sorted);
    }

    private static void collectEnchantments(ItemEnchantments enchantments,
                                            Map<String, TradeItemInfo.EnchantmentEntry> out) {
        if (enchantments == null || enchantments.isEmpty()) {
            return;
        }
        for (Holder<Enchantment> holder : enchantments.keySet()) {
            int level = enchantments.getLevel(holder);
            String id = holder.unwrapKey()
                    .map(key -> key.identifier().toString())
                    .orElse("");
            if (id.isBlank()) {
                // 注册表外的附魔（例如别的模组临时构造的 Holder）：拿不到 id 就不收录，
                // 否则展示端只能显示一个空名字
                continue;
            }
            out.putIfAbsent(id, new TradeItemInfo.EnchantmentEntry(id, level));
        }
    }
}
