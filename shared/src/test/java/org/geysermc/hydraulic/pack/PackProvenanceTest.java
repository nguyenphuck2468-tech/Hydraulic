package org.geysermc.hydraulic.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackProvenanceTest {
    @Test
    void buildEmitsSchemaMetadataAndSummary() {
        ConversionReport report = new ConversionReport();
        report.outcome("entity-native-geometry", "alexsmobs:komodo_dragon");
        report.outcome("entity-native-geometry", "alexsmobs:elephant");
        report.outcome("entity-hitbox", "biomesoplenty:origin_hill");
        report.status("created");

        JsonObject root = PackProvenance.build("alexsmobs", "balanced", 1_700_000_000_000L, report);

        assertEquals(1, root.get("schema_version").getAsInt());
        assertEquals("alexsmobs", root.get("mod_id").getAsString());
        assertEquals("balanced", root.get("profile").getAsString());
        assertEquals(1_700_000_000_000L, root.get("generated_at").getAsLong());
        assertEquals(PackManager.PACK_GENERATION_REVISION, root.get("pack_generation_revision").getAsString());
        assertEquals("created", root.get("hydraulic_conversion_status").getAsString());

        JsonObject summary = root.getAsJsonObject("summary");
        assertEquals(2, summary.get("entity-native-geometry").getAsInt());
        assertEquals(1, summary.get("entity-hitbox").getAsInt());
    }

    @Test
    void buildSplitsOutcomesByNamespace() {
        ConversionReport report = new ConversionReport();
        report.outcome("entity-native-geometry", "alexsmobs:komodo_dragon");
        report.outcome("entity-native-geometry", "alexsmobs:elephant");
        report.outcome("entity-hitbox", "alexsmobs:komodo_dragon");
        report.outcome("entity-native-geometry", "biomesoplenty:origin_hill");

        JsonObject namespaces = PackProvenance.build("alexsmobs", "balanced", 0L, report).getAsJsonObject("namespaces");

        JsonObject alexsmobs = namespaces.getAsJsonObject("alexsmobs");
        assertNotNull(alexsmobs);
        assertEquals(3, alexsmobs.get("total").getAsInt(),
                "Total must be 2 native + 1 hitbox, not double-count the IDs across kinds");
        JsonObject alexsmobsByKind = alexsmobs.getAsJsonObject("by_kind");
        assertEquals(2, alexsmobsByKind.get("entity-native-geometry").getAsInt());
        assertEquals(1, alexsmobsByKind.get("entity-hitbox").getAsInt());

        JsonObject biomesoplenty = namespaces.getAsJsonObject("biomesoplenty");
        assertEquals(1, biomesoplenty.get("total").getAsInt());
        assertEquals(1, biomesoplenty.getAsJsonObject("by_kind").get("entity-native-geometry").getAsInt());
    }

    @Test
    void buildIgnoresIdsWithoutNamespaceSeparator() {
        ConversionReport report = new ConversionReport();
        // No colon — these are malformed and should be dropped from the namespace breakdown
        // rather than throwing or creating a synthetic empty-namespace bucket.
        report.outcome("entity-native-geometry", "malformed_id_no_colon");

        JsonObject namespaces = PackProvenance.build("anymod", "balanced", 0L, report).getAsJsonObject("namespaces");

        assertTrue(namespaces.size() == 0,
                "IDs without a ':' separator must be skipped, not bucketed under an empty namespace");
    }

    @Test
    void buildIncludesIdsArrayForEachNamespace() {
        ConversionReport report = new ConversionReport();
        report.outcome("entity-native-geometry", "alexsmobs:komodo_dragon");
        report.outcome("entity-hitbox", "alexsmobs:komodo_dragon");

        JsonObject namespaces = PackProvenance.build("alexsmobs", "balanced", 0L, report).getAsJsonObject("namespaces");
        JsonArrayBackedCheck ids = new JsonArrayBackedCheck(namespaces.getAsJsonObject("alexsmobs").getAsJsonArray("ids"));

        assertTrue(ids.contains("alexsmobs:komodo_dragon"),
                "Both native and hitbox outcomes for the same id must contribute to its namespace's ids list");
    }

    /** Small helper to assert an array contains a string element via JsonElement equality. */
    private record JsonArrayBackedCheck(com.google.gson.JsonArray array) {
        boolean contains(String value) {
            for (JsonElement element : array) {
                if (element.isJsonPrimitive() && value.equals(element.getAsString())) return true;
            }
            return false;
        }
    }
}