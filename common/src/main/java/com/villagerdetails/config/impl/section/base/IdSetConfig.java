package com.villagerdetails.config.impl.section.base;

import com.villagerdetails.config.ReloadableConfig;
import com.villagerdetails.config.impl.section.storage.IdSetStorage;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Set;

public abstract class IdSetConfig implements ReloadableConfig {

    protected final IdSetStorage storage;

    protected IdSetConfig(String section) {
        this.storage = new IdSetStorage(section);
    }

    // ---- 全部去掉 static ----

    public boolean containsId(Identifier id) {
        return storage.contains(id);
    }

    public List<Identifier> snapshot() {
        return storage.snapshot();
    }

    public void add(Identifier id) {
        storage.add(id);
    }

    public void addAll(Set<Identifier> more) {
        storage.addAll(more);
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