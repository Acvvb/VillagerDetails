package com.villagerdetails.config.impl.dto;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record Cleaned(Map<String, List<String>> index, Set<String> dropped) {
}