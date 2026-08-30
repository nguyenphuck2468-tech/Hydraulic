package org.geysermc.hydraulic.block;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockPackModuleTest {
    @Test
    void matchesAlternativeAndSpecificBlockstateVariants() {
        Map<String, String> state = Map.of("facing", "north", "powered", "true");

        assertTrue(BlockPackModule.variantMatches(state, "facing=north|south,powered=true"));
        assertFalse(BlockPackModule.variantMatches(state, "facing=east|west,powered=true"));
        assertFalse(BlockPackModule.variantMatches(state, "facing=north,powered=false"));
        assertFalse(BlockPackModule.variantMatches(state, "missing=value"));
    }
}
