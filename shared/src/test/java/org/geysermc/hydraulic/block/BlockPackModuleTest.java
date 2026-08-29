package org.geysermc.hydraulic.block;

import net.minecraft.world.level.block.state.properties.IntegerProperty;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockPackModuleTest {
    @Test
    void compactsLargeIntegerPropertiesToBedrockLimit() {
        IntegerProperty age = IntegerProperty.create("age", 0, 25);

        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
                BlockPackModule.bedrockValues(age));
        assertEquals(0, BlockPackModule.bedrockValue(age, 0));
        assertEquals(15, BlockPackModule.bedrockValue(age, 25));
        assertEquals(9, BlockPackModule.bedrockValue(age, 15));
    }
}
