package org.geysermc.hydraulic.util;

import net.minecraft.world.phys.shapes.Shapes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FallbackGeometryFixtureTest {
    @Test
    void preservesEveryVoxelShapeBoxInGeneratedGeometry() {
        var shape = Shapes.or(
                Shapes.box(0, 0, 0, 1, 0.5, 1),
                Shapes.box(0.375, 0.5, 0.375, 0.625, 1, 0.625));
        var model = GeoUtil.fromShape(shape, "geometry.fixture.wall");

        assertEquals("geometry.fixture.wall", model.geometry().getFirst().description().identifier());
        assertEquals(2, model.geometry().getFirst().bones().size());
    }

    @Test
    void groupsFallbackBonesAndUsesTheActualSilhouetteForCulling() {
        var shape = Shapes.or(
                Shapes.box(-2, 0, -1, 2, 2, 1),
                Shapes.box(-0.5, 2, -0.5, 0.5, 4, 0.5));
        var model = GeoUtil.fromShape(shape, "geometry.fixture.large", true);
        var geometry = model.geometry().getFirst();

        assertEquals("bone_0", geometry.bones().get(1).parent());
        assertEquals(4.8f, geometry.description().visibleBoundsWidth());
        assertEquals(4.8f, geometry.description().visibleBoundsHeight());
    }

    @Test
    void supportsAnEmptyCollisionShape() {
        var model = GeoUtil.fromShape(Shapes.empty(), "geometry.fixture.empty");

        assertEquals(0, model.geometry().getFirst().bones().size());
        assertEquals(2f, model.geometry().getFirst().description().visibleBoundsWidth());
    }
}
