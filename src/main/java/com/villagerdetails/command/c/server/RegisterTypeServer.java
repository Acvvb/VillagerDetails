package com.villagerdetails.command.c.server;

import net.minecraft.commands.CommandSourceStack;

import java.util.function.Predicate;

public interface RegisterTypeServer {

    RegisterServer getCommandObject();

    default Predicate<CommandSourceStack> requirement() {
        return null;
    }

}
