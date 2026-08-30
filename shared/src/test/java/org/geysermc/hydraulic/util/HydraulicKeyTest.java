package org.geysermc.hydraulic.util;

import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HydraulicKeyTest {
    @Test
    void equalKeysWorkInHashCollections() {
        var values = new HashMap<HydraulicKey, String>();
        values.put(new HydraulicKey("example", "widget"), "found");

        assertEquals("found", values.get(new HydraulicKey("example", "widget")));
    }
}
