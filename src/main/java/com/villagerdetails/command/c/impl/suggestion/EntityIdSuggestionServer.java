package com.villagerdetails.command.c.impl.suggestion;

import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.villagerdetails.command.c.server.StateSuggestionServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 生物实体注册 id 补全：按逗号分隔的多选值，逐段补全生物（{@link Mob}）的注册 id，
 * 并在完整 id 后提示逗号继续输入。矿车、船、掉落物等非生物实体不在补全范围内。
 * <p>
 * 例如 {@code /ec NoSqueeze minecraft:vi<TAB>} 补全为 {@code minecraft:villager}，
 * 再 {@code <TAB>} 提示追加逗号，逗号后继续补全下一个生物 id。
 */
public class EntityIdSuggestionServer implements StateSuggestionServer {

    public static final EntityIdSuggestionServer INSTANCE = new EntityIdSuggestionServer();

    private EntityIdSuggestionServer() {
    }

    /**
     * 生物实体注册 id 缓存：注册表运行时固定，只计算一次。
     */
    private static volatile List<String> mobIds = List.of();

    private static final SuggestionProvider<CommandSourceStack> SUGGESTER = (ctx, builder) -> {
        String remaining = builder.getRemaining();

        // 以最后一个逗号为界，拆出「已确认的前缀」与「当前正在输入的 token」
        int lastComma = remaining.lastIndexOf(',');
        String prefix = lastComma >= 0 ? remaining.substring(0, lastComma + 1) : "";
        String current = remaining.substring(lastComma + 1);
        String currentLower = current.toLowerCase(Locale.ROOT);

        ServerLevel level = ctx.getSource().getLevel();
        List<String> ids = mobIds(level);

        // 1) 生物注册 id 补全：按当前 token 前缀匹配
        for (String idText : ids) {
            if (!idText.toLowerCase(Locale.ROOT).startsWith(currentLower)) continue;
            String full = prefix + idText;
            if (!full.equals(remaining)) {
                builder.suggest(full);
            }
        }

        // 2) 当前 token 已是完整生物 id → 补一个逗号，方便继续输入下一个
        Identifier complete = Identifier.tryParse(current);
        if (complete != null && ids.contains(complete.toString())) {
            builder.suggest(remaining + ",");
        }

        return builder.buildFuture();
    };

    private static List<String> mobIds(ServerLevel level) {
        List<String> cached = mobIds;
        if (!cached.isEmpty()) return cached;

        List<String> list = new ArrayList<>();
        for (Identifier id : BuiltInRegistries.ENTITY_TYPE.keySet()) {
            var ref = BuiltInRegistries.ENTITY_TYPE.get(id);
            if (ref.isEmpty()) continue;
            EntityType<?> type = ref.get().value();
            try {
                Entity entity = type.create(level, EntitySpawnReason.LOAD);
                if (entity instanceof Mob) {
                    list.add(id.toString());
                }
            } catch (Exception ignored) {
                // 个别实体类型可能无法直接构造，跳过即可
            }
        }
        List<String> copy = List.copyOf(list);
        mobIds = copy;
        return copy;
    }

    @Override
    public SuggestionProvider<CommandSourceStack> getStateSuggester() {
        return SUGGESTER;
    }
}
