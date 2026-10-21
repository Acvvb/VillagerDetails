package com.villagerdetails.command.c.impl.rule;

import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.villagerdetails.command.c.impl.base.AbstractRuleCommand;
import com.villagerdetails.command.c.impl.suggestion.EntityIdSuggestionServer;
import com.villagerdetails.config.impl.section.item.MobNoSpawnConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * /ec c MobNoSpawn &lt;实体id&gt; true|false —— 把某种实体加进 / 移出「禁止生成」名单。
 * /ec c MobNoSpawn reset —— 清空所有配置。
 * /ec c MobNoSpawn show  —— 显示当前名单。
 */
public final class MobNoSpawnServerImpl extends AbstractRuleCommand {

    public static final MobNoSpawnServerImpl INSTANCE = new MobNoSpawnServerImpl();

    private MobNoSpawnServerImpl() {
    }

    private static final String K_SET = "command.mob_no_spawn.set";
    private static final String K_REMOVE = "command.mob_no_spawn.remove";
    private static final String K_RESET = "command.mob_no_spawn.reset";
    private static final String K_SHOW_HEADER = "command.mob_no_spawn.show.header";
    private static final String K_SHOW_EMPTY = "command.mob_no_spawn.show.empty";
    private static final String K_INVALID_VALUE = "command.mob_no_spawn.invalid_value";

    @Override
    protected String literalName() {
        return "MobNoSpawn";
    }

    @Override
    protected String argName() {
        return "entity";
    }

    @Override
    protected SuggestionProvider<CommandSourceStack> idSuggester() {
        return EntityIdSuggestionServer.ALL_ENTITY_ID_SUGGESTER;
    }

    @Override
    protected void add(Identifier id) {
        MobNoSpawnConfig.INSTANCE.add(id);
    }

    @Override
    protected void remove(Identifier id) {
        MobNoSpawnConfig.INSTANCE.remove(id);
    }

    @Override
    protected void resetConfig() {
        MobNoSpawnConfig.INSTANCE.reset();
    }

    @Override
    protected List<Identifier> snapshot() {
        return MobNoSpawnConfig.INSTANCE.snapshot();
    }

    @Override
    protected String kSet() {
        return K_SET;
    }

    @Override
    protected String kRemove() {
        return K_REMOVE;
    }

    @Override
    protected String kReset() {
        return K_RESET;
    }

    @Override
    protected String kShowHeader() {
        return K_SHOW_HEADER;
    }

    @Override
    protected String kShowEmpty() {
        return K_SHOW_EMPTY;
    }

    @Override
    protected String kInvalidValue() {
        return K_INVALID_VALUE;
    }
}
