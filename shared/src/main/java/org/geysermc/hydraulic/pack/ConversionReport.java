package org.geysermc.hydraulic.pack;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;

/** Per-pack counters used to make compatibility fallbacks visible to operators. */
public final class ConversionReport {
    private final Map<String, Integer> fallbacks = new LinkedHashMap<>();
    private final Map<String, Integer> outcomes = new LinkedHashMap<>();

    public void fallback(String kind) {
        fallbacks.merge(kind, 1, Integer::sum);
    }

    public int fallbacks() {
        return fallbacks.values().stream().mapToInt(Integer::intValue).sum();
    }

    public void outcome(String kind) {
        outcomes.merge(kind, 1, Integer::sum);
    }

    public JsonObject json(int blocks, int items, int entities) {
        JsonObject root = new JsonObject();
        root.addProperty("blocks", blocks);
        root.addProperty("items", items);
        root.addProperty("entities", entities);
        root.add("fallbacks", map(fallbacks));
        root.add("outcomes", map(outcomes));
        return root;
    }

    private static JsonObject map(Map<String, Integer> values) {
        JsonObject json = new JsonObject();
        values.forEach(json::addProperty);
        return json;
    }

    public String fallbackSummary() {
        return fallbacks.isEmpty() ? "none" : fallbacks.toString();
    }
}
