package com.villagerdetails.command.c;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.villagerdetails.command.c.impl.reload.ReloadMapping;
import com.villagerdetails.command.c.server.RegisterServer;
import com.villagerdetails.command.c.server.RegisterTypeServer;
import com.villagerdetails.event.type.ListenerType;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

public class CCommand {

    public static LiteralArgumentBuilder<CommandSourceStack> buildCSubcommand() {
        LiteralArgumentBuilder<CommandSourceStack> c = Commands.literal("c");

        c = c.then(ReloadMapping.INSTANCE.register());

        List<RegisterTypeServer> all = new ArrayList<>();
        all.addAll(Arrays.asList(ListenerType.values()));
        all.addAll(Arrays.asList(RuleType.values()));

        for (RegisterTypeServer item : all) {
            RegisterServer rs = item.getCommandObject();
            if (rs == null) continue;
            LiteralArgumentBuilder<CommandSourceStack> sub = rs.register();
            Predicate<CommandSourceStack> req = item.requirement();
            if (req != null) sub = sub.requires(req);
            c = c.then(sub);
        }

        return c;
    }


}
