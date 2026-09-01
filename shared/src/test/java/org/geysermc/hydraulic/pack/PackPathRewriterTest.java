package org.geysermc.hydraulic.pack;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void cleansPreparedPartsWhenSecondJsonWriteFails() throws IOException {
        Fixture fixture = fixtureWithTwoAssets();
        AtomicInteger writes = new AtomicInteger();

        assertThrows(IOException.class, () -> PackPathRewriter.rewrite(temporaryDirectory, new PackPathRewriter.Operations() {
            @Override
            public void write(Path path, byte[] bytes) throws IOException {
                if (writes.incrementAndGet() == 2) throw new IOException("injected second JSON write");
                PackPathRewriter.Operations.super.write(path, bytes);
            }
        }));

        assertTrue(Files.exists(fixture.first()));
        assertTrue(Files.exists(fixture.second()));
        assertNoParts();
    }

    @Test
    void rollsBackFirstAssetWhenSecondMoveFails() throws IOException {
        Fixture fixture = fixtureWithTwoAssets();
        AtomicInteger moves = new AtomicInteger();

        assertThrows(IOException.class, () -> PackPathRewriter.rewrite(temporaryDirectory, new PackPathRewriter.Operations() {
            @Override
            public void move(Path source, Path target) throws IOException {
                if (moves.incrementAndGet() == 2) throw new IOException("injected second asset move");
                PackPathRewriter.Operations.super.move(source, target);
            }
        }));

        assertTrue(Files.exists(fixture.first()));
        assertTrue(Files.exists(fixture.second()));
        assertNoParts();
    }

    @Test
    void restoresAssetsAndFirstJsonWhenSecondJsonPublishFails() throws IOException {
        Fixture fixture = fixtureWithTwoAssets();
        String firstJson = Files.readString(temporaryDirectory.resolve("models/first.json"));
        String secondJson = Files.readString(temporaryDirectory.resolve("models/second.json"));
        AtomicInteger replaces = new AtomicInteger();

        assertThrows(IOException.class, () -> PackPathRewriter.rewrite(temporaryDirectory, new PackPathRewriter.Operations() {
            @Override
            public void replace(Path source, Path target) throws IOException {
                if (replaces.incrementAndGet() == 2) throw new IOException("injected second JSON publish");
                PackPathRewriter.Operations.super.replace(source, target);
            }
        }));

        assertTrue(Files.exists(fixture.first()));
        assertTrue(Files.exists(fixture.second()));
        assertEquals(firstJson, Files.readString(temporaryDirectory.resolve("models/first.json")));
        assertEquals(secondJson, Files.readString(temporaryDirectory.resolve("models/second.json")));
        assertNoParts();
    }

    @Test
    void preservesOriginalFailureAndCleansPartsWhenRollbackAlsoFails() throws IOException {
        fixtureWithTwoAssets();
        AtomicInteger moves = new AtomicInteger();

        IOException failure = assertThrows(IOException.class, () -> PackPathRewriter.rewrite(temporaryDirectory, new PackPathRewriter.Operations() {
            @Override
            public void move(Path source, Path target) throws IOException {
                int call = moves.incrementAndGet();
                if (call == 2) throw new IOException("injected forward failure");
                if (call == 3) throw new IOException("injected rollback failure");
                PackPathRewriter.Operations.super.move(source, target);
            }
        }));

        assertEquals("injected forward failure", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertNoParts();
    }

    private Fixture fixtureWithTwoAssets() throws IOException {
        Path first = temporaryDirectory.resolve("textures/items/example/" + "first_long_name_".repeat(6) + ".png");
        Path second = temporaryDirectory.resolve("textures/items/example/" + "second_long_name_".repeat(6) + ".png");
        Files.createDirectories(first.getParent());
        Files.writeString(first, "first");
        Files.writeString(second, "second");
        String firstRef = temporaryDirectory.relativize(first).toString().replace('\\', '/');
        String secondRef = temporaryDirectory.relativize(second).toString().replace('\\', '/');
        Files.createDirectories(temporaryDirectory.resolve("models"));
        Files.writeString(temporaryDirectory.resolve("models/first.json"), "{\"texture\":\"" + firstRef + "\"}");
        Files.writeString(temporaryDirectory.resolve("models/second.json"), "{\"texture\":\"" + secondRef + "\"}");
        return new Fixture(first, second);
    }

    private void assertNoParts() throws IOException {
        try (var paths = Files.walk(temporaryDirectory)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().endsWith(".part")));
        }
    }

    private record Fixture(Path first, Path second) {
    }
}
