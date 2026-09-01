package org.geysermc.hydraulic.storage;

import com.mojang.logging.LogUtils;
import org.geysermc.hydraulic.Constants;
import org.geysermc.hydraulic.HydraulicImpl;
import org.geysermc.hydraulic.block.Materials;
import org.geysermc.hydraulic.platform.mod.ModInfo;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Stores data relevant to a mod.
 */
public class ModStorage {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MATERIALS_FORMAT_VERSION = 1;

    private ModInfo mod;
    private Materials materials = new Materials();
    private Path pack;

    private ModStorage(@NotNull ModInfo mod) {
        this.mod = mod;
        this.pack = storagePath(mod).resolve(mod.id() + ".mcpack");
    }

    /**
     * Gets the materials for this mod.
     *
     * @return the materials
     */
    @NotNull
    public Materials materials() {
        return this.materials;
    }

    /**
     * Sets the materials for this mod.
     *
     * @param materials the materials
     */
    public void materials(@NotNull Materials materials) {
        this.materials = materials;
    }

    /**
     * Gets the path to the pack for this mod.
     *
     * @return the path to the pack
     */
    @NotNull
    public Path pack() {
        return this.pack;
    }

    /**
     * Saves the mod storage.
     */
    public void save() {
        try {
            Path path = storagePath(this.mod);
            if (Files.notExists(path)) {
                Files.createDirectories(path);
            }

            JsonObject document = new JsonObject();
            document.addProperty("version", MATERIALS_FORMAT_VERSION);
            document.add("materials", Constants.GSON.toJsonTree(this.materials));
            writeRecoverable(path.resolve("materials.json"), Constants.GSON.toJson(document));
        } catch (IOException e) {
            LOGGER.error("Failed to save mod storage for {}", this.mod.id(), e);
        }
    }

    /**
     * Loads the mod storage for the given mod.
     *
     * @param mod the mod
     * @return the mod storage
     */
    public static ModStorage load(@NotNull ModInfo mod) {
        Path path = storagePath(mod);
        ModStorage storage = new ModStorage(mod);

        if (Files.notExists(path)) {
            return storage;
        }

        Path materialsPath = path.resolve("materials.json");
        try {
            storage.materials(readMaterials(materialsPath));
        } catch (IOException | RuntimeException primary) {
            try {
                storage.materials(readMaterials(backupPath(materialsPath)));
                LOGGER.warn("Recovered mod storage for {} from backup", mod.id());
            } catch (IOException | RuntimeException backup) {
                LOGGER.error("Failed to load mod storage for {}; starting with empty materials", mod.id(), primary);
            }
        }

        return storage;
    }

    private static Path storagePath(@NotNull ModInfo mod) {
        return HydraulicImpl.instance().dataFolder(Constants.MOD_ID)
                .resolve("storage")
                .resolve(mod.id());
    }

    static Materials readMaterials(Path path) throws IOException {
        var parsed = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
        if (!parsed.isJsonObject()) throw new IOException("materials document is not an object");
        JsonObject object = parsed.getAsJsonObject();
        if (!object.has("version")) {
            Materials legacy = Constants.GSON.fromJson(object, Materials.class);
            if (legacy == null) throw new IOException("legacy materials document is empty");
            return legacy;
        }
        if (object.get("version").getAsInt() != MATERIALS_FORMAT_VERSION || !object.has("materials")) {
            throw new IOException("unsupported materials format version");
        }
        Materials materials = Constants.GSON.fromJson(object.get("materials"), Materials.class);
        if (materials == null) throw new IOException("materials document is empty");
        return materials;
    }

    private static void writeRecoverable(Path target, String contents) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".part");
        Files.writeString(temporary, contents, StandardCharsets.UTF_8);
        if (Files.isRegularFile(target)) {
            Files.copy(target, backupPath(target), StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path backupPath(Path target) {
        return target.resolveSibling(target.getFileName() + ".bak");
    }
}
