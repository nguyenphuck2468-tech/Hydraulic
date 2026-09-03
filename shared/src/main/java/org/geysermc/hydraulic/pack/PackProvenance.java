package org.geysermc.hydraulic.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the per-pack provenance JSON embedded at {@code hydraulic/provenance.json}
 * inside every converted {@code .mcpack}. Bedrock ignores arbitrary files that are
 * not part of the pack schema, but operators and downstream debug tools can open
 * the JSON to see where each geometry came from (file-based, Tabula reflection,
 * vanilla fallback, hitbox). The schema is intentionally small: metadata plus a
 * per-namespace breakdown. Per-entity detail is already available in the
 * external {@code reports/<modid>.json} produced by {@link ConversionReport}.
 */
public final class PackProvenance {
    /** Path inside the {@code .mcpack} where the provenance JSON is embedded. */
    public static final String EMBEDDED_PATH = "hydraulic/provenance.json";

    private PackProvenance() {}

    public static JsonObject build(String modId, String profile, long generatedAtMillis, ConversionReport report) {
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", 1);
        root.addProperty("mod_id", modId);
        root.addProperty("profile", profile);
        root.addProperty("generated_at", generatedAtMillis);
        root.addProperty("pack_generation_revision", PackManager.PACK_GENERATION_REVISION);
        root.addProperty("hydraulic_conversion_status", report.statusSnapshot());
        root.add("summary", summary(report));
        root.add("namespaces", namespaces(report));
        return root;
    }

    private static JsonObject summary(ConversionReport report) {
        JsonObject summary = new JsonObject();
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : report.outcomeIdsView().entrySet()) {
            totals.put(entry.getKey(), entry.getValue().size());
        }
        totals.forEach(summary::addProperty);
        return summary;
    }

    /**
     * Per-namespace breakdown. Each entity ID follows {@code namespace:path};
     * we split on the first colon and group counts by namespace, then split
     * each count by outcome kind (native, tabula-reflection, vanilla-fallback,
     * hitbox).
     */
    private static JsonObject namespaces(ConversionReport report) {
        Map<String, Map<String, Integer>> perNamespace = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : report.outcomeIdsView().entrySet()) {
            String kind = entry.getKey();
            for (String id : entry.getValue()) {
                int sep = id.indexOf(':');
                if (sep < 0) continue;
                String namespace = id.substring(0, sep);
                perNamespace
                        .computeIfAbsent(namespace, ignored -> new LinkedHashMap<>())
                        .merge(kind, 1, Integer::sum);
            }
        }
        JsonObject root = new JsonObject();
        perNamespace.forEach((namespace, counts) -> {
            JsonObject entry = new JsonObject();
            int total = counts.values().stream().mapToInt(Integer::intValue).sum();
            entry.addProperty("total", total);
            JsonObject byKind = new JsonObject();
            counts.forEach(byKind::addProperty);
            entry.add("by_kind", byKind);
            JsonArray ids = new JsonArray();
            counts.forEach((kind, count) -> {
                for (String id : report.outcomeIdsView().get(kind)) {
                    if (id.startsWith(namespace + ":")) ids.add(id);
                }
            });
            entry.add("ids", ids);
            root.add(namespace, entry);
        });
        return root;
    }
}