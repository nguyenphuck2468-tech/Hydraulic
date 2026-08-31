package org.geysermc.hydraulic.block;

import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;
import team.unnamed.creative.model.Model;
import team.unnamed.creative.model.ModelTexture;
import team.unnamed.creative.model.ModelTextures;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void mergesOnlyMultipartModelsWithCompatibleMaterialBindings() {
        ModelTextures stone = ModelTextures.builder()
                .addVariable("all", ModelTexture.ofKey(Key.key("example", "block/stone"))).build();
        ModelTextures wood = ModelTextures.builder()
                .addVariable("all", ModelTexture.ofKey(Key.key("example", "block/wood"))).build();
        Model first = Model.model().key(Key.key("example", "block/first")).textures(stone).build();
        Model second = Model.model().key(Key.key("example", "block/second")).textures(stone).build();
        Model incompatible = Model.model().key(Key.key("example", "block/third")).textures(wood).build();
        Key mergedKey = Key.key("example", "block/hydraulic_merged/test");

        Model merged = BlockPackModule.mergeCompatibleModels(List.of(first, second), mergedKey);

        assertEquals(mergedKey, merged.key());
        assertEquals(stone, merged.textures());
        assertNull(BlockPackModule.mergeCompatibleModels(List.of(first, incompatible), mergedKey));
    }
}
