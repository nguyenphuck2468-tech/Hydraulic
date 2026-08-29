package org.geysermc.hydraulic.pack;

import java.util.LinkedHashMap;
import java.util.Map;

/** Per-pack counters used to make compatibility fallbacks visible to operators. */
public final class ConversionReport {
    private final Map<String, Integer> fallbacks = new LinkedHashMap<>();

    public void fallback(String kind) {
        fallbacks.merge(kind, 1, Integer::sum);
    }

    public int fallbacks() {
        return fallbacks.values().stream().mapToInt(Integer::intValue).sum();
    }

    public String fallbackSummary() {
        return fallbacks.isEmpty() ? "none" : fallbacks.toString();
    }
}
