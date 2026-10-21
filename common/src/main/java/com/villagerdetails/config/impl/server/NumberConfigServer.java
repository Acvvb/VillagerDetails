package com.villagerdetails.config.impl.server;


import net.minecraft.resources.Identifier;

public interface NumberConfigServer {

    String toSyncString();
    void applyFromSyncString(String data);
    void put(Identifier id, float value);
    void remove(Identifier id);
    void reset();

}