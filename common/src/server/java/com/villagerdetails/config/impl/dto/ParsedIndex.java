package com.villagerdetails.config.impl.dto;

import com.villagerdetails.pojo.dto.VillagerTradeInfo;

import java.util.Map;
import java.util.UUID;

public record ParsedIndex(Map<UUID, VillagerTradeInfo> villagers, int skipped) {
}