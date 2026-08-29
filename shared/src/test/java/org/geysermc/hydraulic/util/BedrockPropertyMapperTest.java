package org.geysermc.hydraulic.util;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BedrockPropertyMapperTest {
    @Test
    void compactsLargeIntegerPropertiesToBedrockLimit() {
        List<Integer> age = java.util.stream.IntStream.rangeClosed(0, 25).boxed().toList();

        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
                BedrockPropertyMapper.values(age));
        assertEquals(0, BedrockPropertyMapper.value(age, 0));
        assertEquals(15, BedrockPropertyMapper.value(age, 25));
        assertEquals(9, BedrockPropertyMapper.value(age, 15));
    }
}
