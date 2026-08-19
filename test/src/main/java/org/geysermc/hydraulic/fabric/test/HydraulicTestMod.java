package org.geysermc.hydraulic.fabric.test;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;
import java.util.List;

public class HydraulicTestMod implements ModInitializer {
    public static final String MOD_ID = "hydraulic_test_mod";

    private static final List<String> REQUIRED_RESOURCES = List.of(
            "assets/hydraulic_test_mod/blockstates/golden_barrel.json",
            "assets/hydraulic_test_mod/items/barrel_sword.json",
            "assets/hydraulic_test_mod/items/barrel_pack.json"
    );

    @Override
    public void onInitialize() {
        ModItems.init();
        ModBlocks.init();
        verifyGeneratedResources();
    }

    private static void verifyGeneratedResources() {
        var modContainer = FabricLoader.getInstance().getModContainer(MOD_ID)
                .orElseThrow(() -> new IllegalStateException("Hydraulic test mod container is missing"));

        for (String resource : REQUIRED_RESOURCES) {
            Path path = modContainer.findPath(resource).orElse(null);
            if (path == null || !java.nio.file.Files.isRegularFile(path)) {
                throw new IllegalStateException("Missing generated resource: " + resource);
            }
        }
    }
}
