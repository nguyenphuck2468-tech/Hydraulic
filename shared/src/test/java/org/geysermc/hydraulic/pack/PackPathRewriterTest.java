package org.geysermc.hydraulic.pack;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackPathRewriterTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void shortensLongAssetsAndUpdatesJsonReferencesWithAndWithoutExtensions() throws IOException {
        Path texture = temporaryDirectory.resolve("textures/items/example/_hydraulic/" + "long_name_".repeat(7) + ".png");
        Files.createDirectories(texture.getParent());
        Files.writeString(texture, "texture");
        String oldPath = temporaryDirectory.relativize(texture).toString().replace('\\', '/');
        Files.writeString(temporaryDirectory.resolve("textures/item_texture.json"), """
                {"texture_data":{"example:item":{"textures":["%s"]}}}
                """.formatted(oldPath.substring(0, oldPath.length() - ".png".length())));
        Files.createDirectories(temporaryDirectory.resolve("models"));
        Files.writeString(temporaryDirectory.resolve("models/item.json"), """
                {"texture":"%s"}
                """.formatted(oldPath));

        PackPathRewriter.Result result = PackPathRewriter.rewrite(temporaryDirectory);
        Path shortened = temporaryDirectory.resolve(PackPathRewriter.shortenedPath(temporaryDirectory.relativize(texture)));

        assertEquals(1, result.rewritten());
        assertTrue(Files.isRegularFile(shortened));
        assertFalse(Files.exists(texture));
        String atlas = Files.readString(temporaryDirectory.resolve("textures/item_texture.json"));
        String model = Files.readString(temporaryDirectory.resolve("models/item.json"));
        assertFalse(atlas.contains(oldPath.substring(0, oldPath.length() - ".png".length())));
        assertFalse(model.contains(oldPath));
        assertTrue(JsonParser.parseString(atlas).toString().contains("h/"));
        assertTrue(JsonParser.parseString(model).toString().contains("h/"));
    }

    @Test
    void leavesEverythingUntouchedWhenTheShortTargetAlreadyExists() throws IOException {
        Path texture = temporaryDirectory.resolve("textures/items/example/_hydraulic/" + "long_name_".repeat(7) + ".png");
        Files.createDirectories(texture.getParent());
        Files.writeString(texture, "texture");
        Path collision = temporaryDirectory.resolve(PackPathRewriter.shortenedPath(temporaryDirectory.relativize(texture)));
        Files.createDirectories(collision.getParent());
        Files.writeString(collision, "existing");

        PackPathRewriter.Result result = PackPathRewriter.rewrite(temporaryDirectory);

        assertEquals(0, result.rewritten());
        assertTrue(result.collision());
        assertTrue(Files.isRegularFile(texture));
        assertEquals("existing", Files.readString(collision));
    }

    @Test
    void leavesLongAssetInPlaceWithoutAnExactJsonReference() throws IOException {
        Path texture = temporaryDirectory.resolve("textures/items/example/_hydraulic/" + "long_name_".repeat(7) + ".png");
        Files.createDirectories(texture.getParent());
        Files.writeString(texture, "texture");

        PackPathRewriter.Result result = PackPathRewriter.rewrite(temporaryDirectory);

        assertEquals(0, result.rewritten());
        assertFalse(result.collision());
        assertTrue(Files.isRegularFile(texture));
    }
}
