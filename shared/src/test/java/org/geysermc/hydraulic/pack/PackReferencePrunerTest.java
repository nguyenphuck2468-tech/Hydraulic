package org.geysermc.hydraulic.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackReferencePrunerTest {
    @TempDir
    Path root;

    @Test
    void prunesOnlyUnreferencedGeneratedAssets() throws Exception {
        write("entity/example.entity.json", """
                {"minecraft:client_entity":{"description":{"geometry":{"default":"geometry.example.used"},
                "textures":{"default":"textures/entity/used"},
                "animations":{"idle":"animation.example.used"},
                "animation_controllers":["controller.animation.example.used"],
                "render_controllers":["controller.render.example.used"]}}}
                """);
        write("models/entity/used.geo.json", geometry("geometry.example.used"));
        write("models/entity/orphan.geo.json", geometry("geometry.example.orphan"));
        write("animations/used.json", "{\"animations\":{\"animation.example.used\":{}}}");
        write("animations/orphan.json", "{\"animations\":{\"animation.example.orphan\":{}}}");
        write("animation_controllers/used.json", "{\"animation_controllers\":{\"controller.animation.example.used\":{}}}");
        write("animation_controllers/orphan.json", "{\"animation_controllers\":{\"controller.animation.example.orphan\":{}}}");
        write("render_controllers/used.json", "{\"render_controllers\":{\"controller.render.example.used\":{}}}");
        write("render_controllers/orphan.json", "{\"render_controllers\":{\"controller.render.example.orphan\":{}}}");
        write("textures/entity/used.png", "used");
        write("textures/entity/orphan.png", "orphan");

        PackReferencePruner.Result result = PackReferencePruner.prune(root);

        assertEquals(5, result.total());
        assertTrue(Files.exists(root.resolve("models/entity/used.geo.json")));
        assertFalse(Files.exists(root.resolve("models/entity/orphan.geo.json")));
        assertTrue(Files.exists(root.resolve("textures/entity/used.png")));
        assertFalse(Files.exists(root.resolve("textures/entity/orphan.png")));
    }

    @Test
    void keepsMalformedJsonAndItsDefinitionsConservatively() throws Exception {
        write("models/entity/broken.geo.json", "not-json");

        PackReferencePruner.Result result = PackReferencePruner.prune(root);

        assertEquals(0, result.total());
        assertTrue(Files.exists(root.resolve("models/entity/broken.geo.json")));
    }

    @Test
    void rewritesExactReferencesBeforeDeletingDuplicateTexture() throws Exception {
        write("entity/example.entity.json", """
                {"minecraft:client_entity":{"description":{"textures":{"first":"textures/entity/a","second":"textures/entity/b"}}}}
                """);
        write("textures/entity/a.png", "same-bytes");
        write("textures/entity/b.png", "same-bytes");

        PackReferencePruner.Result result = PackReferencePruner.prune(root);

        assertEquals(1, result.duplicateTextures());
        assertTrue(Files.exists(root.resolve("textures/entity/a.png")));
        assertFalse(Files.exists(root.resolve("textures/entity/b.png")));
        String entity = Files.readString(root.resolve("entity/example.entity.json"));
        assertTrue(entity.contains("textures/entity/a"));
        assertFalse(entity.contains("textures/entity/b"));
    }

    @Test
    void retainsDefinitionsReferencedByConditionalObjectKeys() throws Exception {
        write("entity/example.entity.json", """
                {"minecraft:client_entity":{"description":{
                  "animation_controllers":[{"controller.animation.example.used":"query.is_alive"}],
                  "render_controllers":[{"controller.render.example.used":"query.is_alive"}]}}}
                """);
        write("animation_controllers/used.json", """
                {"animation_controllers":{"controller.animation.example.used":{"states":{"default":{
                  "animations":[{"animation.example.used":"query.is_moving"}]}}}}}
                """);
        write("render_controllers/used.json", "{\"render_controllers\":{\"controller.render.example.used\":{}}}");
        write("animations/used.json", "{\"animations\":{\"animation.example.used\":{}}}");

        PackReferencePruner.prune(root);

        assertTrue(Files.exists(root.resolve("animation_controllers/used.json")));
        assertTrue(Files.exists(root.resolve("render_controllers/used.json")));
        assertTrue(Files.exists(root.resolve("animations/used.json")));
    }

    private String geometry(String identifier) {
        return "{\"minecraft:geometry\":[{\"description\":{\"identifier\":\"" + identifier + "\"}}]}";
    }

    private void write(String relative, String contents) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
    }
}
