package org.geysermc.hydraulic.util;

import java.util.ArrayList;
import java.util.List;

/** Maps Java integer property values into Bedrock's 16-value limit. */
public final class BedrockPropertyMapper {
    private static final int MAX_VALUES = 16;

    private BedrockPropertyMapper() {
    }

    public static List<Integer> values(List<Integer> values) {
        if (values.size() <= MAX_VALUES) {
            return values;
        }
        List<Integer> compacted = new ArrayList<>(MAX_VALUES);
        for (int index = 0; index < MAX_VALUES; index++) {
            compacted.add(index);
        }
        return compacted;
    }

    public static int value(List<Integer> values, int value) {
        if (values.size() <= MAX_VALUES) {
            return value;
        }
        return Math.round(values.indexOf(value) * (MAX_VALUES - 1f) / (values.size() - 1f));
    }
}
