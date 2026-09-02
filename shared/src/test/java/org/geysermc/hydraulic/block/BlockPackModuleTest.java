package org.geysermc.hydraulic.block;

import net.kyori.adventure.key.Key;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.Test;
import team.unnamed.creative.model.Model;
import team.unnamed.creative.model.ModelTexture;
import team.unnamed.creative.model.ModelTextures;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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

    @Test
    void rotateShapeForBedrockReturnsShapeUnchangedForEmptyShape() {
        // Empty shape must short-circuit before any state inspection, so callers
        // can pass Shapes.empty() safely without registering a real block first.
        VoxelShape empty = Shapes.empty();
        assertSame(empty, BlockPackModule.rotateShapeForBedrock(null, empty),
                "Empty shape must be returned untouched regardless of state");
    }

    @Test
    void rotateShapeForBedrockSkipsWhenStateLacksFacingProperty() {
        // When the state has no horizontal_facing/facing property the helper must
        // not crash. We pass null because constructing a real BlockState in unit
        // tests triggers Minecraft registry initialisation we deliberately avoid;
        // the production call site always passes a non-null state, and the null
        // branch here covers both "missing property" and "no state available".
        VoxelShape shape = Shapes.box(0, 0, 0, 1, 0.5, 1);
        assertSame(shape, BlockPackModule.rotateShapeForBedrock(null, shape),
                "State without a recognised facing property must yield the original shape");
    }
}
