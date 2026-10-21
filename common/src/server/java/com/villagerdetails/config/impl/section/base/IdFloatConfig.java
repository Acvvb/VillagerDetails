package com.villagerdetails.config.impl.section.base;

import com.villagerdetails.config.ReloadableConfig;
import com.villagerdetails.config.impl.section.storage.IdFloatStorage;
import net.minecraft.resources.Identifier;

import java.util.Map;

public abstract class IdFloatConfig implements ReloadableConfig {

    protected final IdFloatStorage storage;

    protected IdFloatConfig(String section) {
        this.storage = new IdFloatStorage(section);
    }

    public Float get(Identifier id) {
        return storage.get(id);
    }

    public Map<Identifier, Float> snapshot() {
        return storage.snapshot();
    }

    public void put(Identifier id, float value) {
        storage.put(id, value);
    }

    public void remove(Identifier id) {
        storage.remove(id);
    }

    public void reset() {
        storage.reset();
    }

    public String toSyncString() {
        return storage.toSyncString();
    }

    public void applyFromSyncString(String data) {
        storage.applyFromSyncString(data);
    }

    protected void loadStorage() {
        storage.load();
    }
}