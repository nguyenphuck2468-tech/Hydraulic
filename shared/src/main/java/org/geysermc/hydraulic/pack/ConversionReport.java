package org.geysermc.hydraulic.pack;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

/** Per-pack counters used to make compatibility fallbacks visible to operators. */
public final class ConversionReport {
    private final Map<String, Integer> fallbacks = new LinkedHashMap<>();
    private final Map<String, Integer> outcomes = new LinkedHashMap<>();
    private final Map<String, List<String>> outcomeIds = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> resolutions = new LinkedHashMap<>();
    private final List<String> validationWarnings = new java.util.ArrayList<>();
    private final Map<String, Long> timings = new LinkedHashMap<>();

    public void fallback(String kind) {
        fallbacks.merge(kind, 1, Integer::sum);
    }

    public int fallbacks() {
        return fallbacks.values().stream().mapToInt(Integer::intValue).sum();
    }

    public void outcome(String kind) {
        outcomes.merge(kind, 1, Integer::sum);
    }

    public void outcome(String kind, String id) {
        outcome(kind);
        outcomeIds.computeIfAbsent(kind, ignored -> new java.util.ArrayList<>()).add(id);
    }

    /** Records the converted texture/model source selected for an asset ID. */
    public void resolution(String kind, String id, String source) {
        resolutions.computeIfAbsent(kind, ignored -> new LinkedHashMap<>()).put(id, source);
    }

    /** Stores every validation warning while the server log keeps a short preview. */
    public void validationWarnings(List<String> warnings) {
        validationWarnings.addAll(warnings);
    }

    public void timing(String stage, long millis) {
        timings.merge(stage, millis, Long::sum);
    }

    public JsonObject json(int blocks, int items, int entities, long archiveBytes) {
        JsonObject root = new JsonObject();
        root.addProperty("blocks", blocks);
        root.addProperty("items", items);
        root.addProperty("entities", entities);
        root.add("fallbacks", map(fallbacks));
        root.add("outcomes", map(outcomes));
        JsonObject ids = new JsonObject();
        outcomeIds.forEach((kind, values) -> {
            var array = new com.google.gson.JsonArray();
            values.forEach(array::add);
            ids.add(kind, array);
        });
        root.add("outcome_ids", ids);
        JsonObject sources = new JsonObject();
        resolutions.forEach((kind, values) -> {
            JsonObject entries = new JsonObject();
            values.forEach(entries::addProperty);
            sources.add(kind, entries);
        });
        root.add("asset_resolutions", sources);
        var warnings = new com.google.gson.JsonArray();
        validationWarnings.forEach(warnings::add);
        root.add("validation_warnings", warnings);
        JsonObject timingValues = new JsonObject();
        timings.forEach(timingValues::addProperty);
        root.add("timings_ms", timingValues);
        root.addProperty("archive_bytes", archiveBytes);
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
