package com.villagerdetails.command.c.impl.util;

import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * 实体注册 id 补全。
 *
 * <p>只给 {@code /ec c <规则> <实体id> true|false} 的 id 位置用（{@code NoSqueeze}、
 * {@code MobNoSpawn}）：那条命令是「一个实体一个值」，所以这里一次性把全部实体注册 id 列出来即可。
 *
 * <p>以前这里还有一套「按逗号分段、只补 {@code Mob}、补完整 id 后追加逗号」的
 * {@code StateSuggestionServer} 通道（给 {@code /ec <规则> <状态>} 用）。但没有任何
 * {@code RuleType} 接过它（41 条规则全部传 {@code null}），那段代码永远走不到，
 * 已随 {@code StateSuggestionServer} 接口一起删除；现在 {@code /ec <规则> <状态>}
 * 的补全只来自规则自己的 quickSwitches。
 */
public final class EntityIdSuggestionServerImpl {

    private EntityIdSuggestionServerImpl() {
    }

    /**
     * <b>所有</b>实体类型注册 id 的补全（不区分是不是生物）。
     */
    public static final SuggestionProvider<CommandSourceStack> ALL_ENTITY_ID_SUGGESTER = (_, builder) -> {
        List<String> ids = new ArrayList<>();
        for (Identifier id : BuiltInRegistries.ENTITY_TYPE.keySet()) {
            ids.add(id.toString());
        }
        return SharedSuggestionProvider.suggest(ids, builder);
    };
}
