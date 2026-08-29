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
}
