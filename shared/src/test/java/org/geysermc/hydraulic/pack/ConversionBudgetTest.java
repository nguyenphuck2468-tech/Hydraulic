package org.geysermc.hydraulic.pack;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConversionBudgetTest {
    @AfterEach
    void clearSystemProperties() {
        System.clearProperty("hydraulic.budget.totalMs");
        System.clearProperty("hydraulic.budget.perModMs");
        System.clearProperty("hydraulic.budget.perModMs.alexsmobs");
        System.clearProperty("hydraulic.budget.perModMs.biomesoplenty");
    }

    @Test
    void defaultsWhenNoConfigOrProperties(@TempDir Path tmp) {
        ConversionBudget budget = ConversionBudget.load(null);
        assertEquals(ConversionBudget.DEFAULT_TOTAL_MS, budget.totalMs());
        assertEquals(ConversionBudget.DEFAULT_PER_MOD_MS, budget.defaultPerModMs());
        assertEquals(ConversionBudget.DEFAULT_PER_MOD_MS, budget.ceilingFor("anymod"));
    }

    @Test
    void systemPropertyOverridesDefault() {
        System.setProperty("hydraulic.budget.totalMs", "50000");
        System.setProperty("hydraulic.budget.perModMs", "8000");
        ConversionBudget budget = ConversionBudget.load(null);
        assertEquals(50_000L, budget.totalMs());
        assertEquals(8_000L, budget.defaultPerModMs());
        assertEquals(8_000L, budget.ceilingFor("anymod"));
    }

    @Test
    void perModSystemPropertyOverridesDefault() {
        System.setProperty("hydraulic.budget.perModMs", "5000");
        System.setProperty("hydraulic.budget.perModMs.alexsmobs", "15000");
        ConversionBudget budget = ConversionBudget.load(null);
        assertEquals(15_000L, budget.ceilingFor("alexsmobs"));
        assertEquals(5_000L, budget.ceilingFor("biomesoplenty"));
    }

    @Test
    void jsonFileLoadedWhenPresent(@TempDir Path tmp) throws IOException {
        Path config = tmp.resolve("budget.json");
        Files.writeString(config,
                "{\"totalMs\": 40000, \"perModMs\": 7000}\n", StandardCharsets.UTF_8);
        ConversionBudget budget = ConversionBudget.load(config);
        assertEquals(40_000L, budget.totalMs());
        assertEquals(7_000L, budget.defaultPerModMs());
        assertEquals(7_000L, budget.ceilingFor("anymod"));
    }

    @Test
    void systemPropertyBeatsJsonFile(@TempDir Path tmp) throws IOException {
        Path config = tmp.resolve("budget.json");
        Files.writeString(config,
                "{\"totalMs\": 40000, \"perModMs\": 7000}\n", StandardCharsets.UTF_8);
        System.setProperty("hydraulic.budget.totalMs", "60000");
        System.setProperty("hydraulic.budget.perModMs", "9000");
        ConversionBudget budget = ConversionBudget.load(config);
        assertEquals(60_000L, budget.totalMs());
        assertEquals(9_000L, budget.defaultPerModMs());
    }

    @Test
    void invalidSystemPropertyIgnored() {
        System.setProperty("hydraulic.budget.totalMs", "not-a-number");
        System.setProperty("hydraulic.budget.perModMs.alexsmobs", "-10");
        ConversionBudget budget = ConversionBudget.load(null);
        assertEquals(ConversionBudget.DEFAULT_TOTAL_MS, budget.totalMs());
        assertEquals(ConversionBudget.DEFAULT_PER_MOD_MS, budget.ceilingFor("alexsmobs"));
    }

    @Test
    void malformedJsonFileFallsBackToDefaults(@TempDir Path tmp) throws IOException {
        Path config = tmp.resolve("budget.json");
        Files.writeString(config, "{not valid json", StandardCharsets.UTF_8);
        ConversionBudget budget = ConversionBudget.load(config);
        assertEquals(ConversionBudget.DEFAULT_TOTAL_MS, budget.totalMs());
        assertEquals(ConversionBudget.DEFAULT_PER_MOD_MS, budget.defaultPerModMs());
    }

    @Test
    void missingJsonFileFallsBackToDefaults(@TempDir Path tmp) {
        ConversionBudget budget = ConversionBudget.load(tmp.resolve("does-not-exist.json"));
        assertEquals(ConversionBudget.DEFAULT_TOTAL_MS, budget.totalMs());
        assertEquals(ConversionBudget.DEFAULT_PER_MOD_MS, budget.defaultPerModMs());
    }

    @Test
    void ceilingForNullModIdFallsBackToDefault() {
        ConversionBudget budget = ConversionBudget.load(null);
        assertEquals(ConversionBudget.DEFAULT_PER_MOD_MS, budget.ceilingFor(null));
    }
}