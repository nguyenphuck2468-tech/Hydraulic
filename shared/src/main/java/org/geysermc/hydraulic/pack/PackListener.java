package org.geysermc.hydraulic.pack;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.SharedConstants;
import org.geysermc.event.PostOrder;
import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineResourcePacksEvent;
import org.geysermc.geyser.api.pack.PackCodec;
import org.geysermc.geyser.api.pack.ResourcePack;
import org.geysermc.geyser.api.pack.option.PriorityOption;
import org.geysermc.hydraulic.Constants;
import org.geysermc.hydraulic.HydraulicImpl;
import org.geysermc.hydraulic.platform.mod.ModInfo;
import org.geysermc.hydraulic.storage.ModStorage;
import org.geysermc.hydraulic.util.FormatUtil;
import org.geysermc.hydraulic.util.PackUtil;
import org.geysermc.pack.bedrock.resource.Manifest;
import org.geysermc.pack.converter.util.VanillaPackProvider;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipFile;

/**
 * Listens for events related to packs.
 */
public class PackListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static final ExecutorService THREAD_POOL;

    private final HydraulicImpl hydraulic;
    private final PackManager manager;

    static {
        THREAD_POOL = Executors.newFixedThreadPool(
            Math.max(1, Runtime.getRuntime().availableProcessors() * 3 / 8),
            new ThreadFactoryBuilder()
                .setNameFormat(Constants.MOD_NAME + " Conversion Thread #%d")
                .setUncaughtExceptionHandler((thread, throwable) -> LOGGER.error("Uncaught exception in thread {}", thread.getName(), throwable))
                .build()
        );
    }

    public PackListener(HydraulicImpl hydraulic, PackManager manager) {
        this.hydraulic = hydraulic;
        this.manager = manager;

        hydraulic.registerServerStop(server -> {
            THREAD_POOL.shutdown(); // Prevents the server from locking up on stop
        });

        warmVanillaPack();
    }

    /**
     * Starts fetching the vanilla pack cache as early as possible. The first
     * download of a new Minecraft version can take minutes on slow networks,
     * and the pack conversion joins on the server thread - if the download
     * only starts there, the server watchdog kills the server before the
     * cache is ready. Warming from mod init (before the server thread and
     * its watchdog exist) means the conversion usually finds a finished
     * cache; VanillaPackProvider serialises concurrent callers.
     */
    private void warmVanillaPack() {
        String version;
        try {
            SharedConstants.tryDetectVersion();
            version = SharedConstants.getCurrentVersion().id();
        } catch (Throwable t) {
            LOGGER.debug("Could not determine Minecraft version early, deferring vanilla pack fetch", t);
            return;
        }

        // The conversion-time path resolves its version from this property and,
        // when unset, must fetch Mojang's manifest before it can even compare
        // the cache - a network call made while the server thread joins the
        // conversion. Publishing the detected version lets that path hit the
        // warmed cache without touching the network.
        System.setProperty("packconverter.vanillaVersion", version);

        LOGGER.info("Pre-fetching vanilla pack for Minecraft {}...", version);
        CompletableFuture.runAsync(
                () -> VanillaPackProvider.create(this.manager.getVanillaPath(), version, new PackLogListener(LOGGER)),
                THREAD_POOL);
    }

    @Subscribe(postOrder = PostOrder.LATE)
    public void onLoadResourcePacks(GeyserDefineResourcePacksEvent event) {
        // Check if hydraulic has updated since the last pack conversion
        // This is so we can regenerate packs on update in case the pack generation logic has changed
        ModInfo hydraulicMod = this.hydraulic.mod(Constants.MOD_ID);
        String hydraulicFingerprint = PackUtil.getModUUID(hydraulicMod.roots()).toString();
        boolean hydraulicUpdated = checkNeedsConversion(hydraulicMod, this.hydraulic.modStorage(hydraulicMod).pack(), hydraulicFingerprint);

        if (hydraulicUpdated) {
            LOGGER.info("Hydraulic has updated since the last pack conversion, regenerating all packs!");
        }

        // Go over all mods and load the pack or mark them for conversion
        Map<String, PackRequest> packsToLoad = new HashMap<>();
        for (ModInfo mod : this.hydraulic.mods()) {
            if (PackManager.IGNORED_MODS.contains(mod.id())) {
                continue;
            }

            // Ignore generated mods
            if (mod.id().startsWith("generated_")) {
                continue;
            }

            ModStorage storage = this.hydraulic.modStorage(mod);

            Path packPath = storage.pack();
            String fingerprint = PackUtil.getModUUID(mod.roots()).toString();
            if (this.hydraulic.isDev() || hydraulicUpdated || checkNeedsConversion(mod, packPath, fingerprint)) {
                packsToLoad.put(mod.id(), new PackRequest(mod, packPath, fingerprint));
            } else {
                // We don't need to convert the pack, just register it
                LOGGER.info("Reusing converted pack for mod {} [revision={}]", mod.id(), PackManager.PACK_GENERATION_REVISION);
                event.register(ResourcePack.create(PackCodec.path(packPath)), PriorityOption.NORMAL);
            }
        }

        if (packsToLoad.isEmpty()) {
            return;
        }

        LOGGER.info("Found {} packs to convert!", packsToLoad.size());

        long start = System.currentTimeMillis();

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (var entry : packsToLoad.entrySet()) {
            futures.add(CompletableFuture.runAsync(() -> {
                LOGGER.info("Converting pack for mod {}", entry.getKey());
                try {
                    PackRequest request = entry.getValue();
                    if (this.manager.createPack(request.mod(), request.packPath(), request.fingerprint())) {
                        event.register(ResourcePack.create(PackCodec.path(request.packPath())), PriorityOption.NORMAL);
                    }
                } catch (Throwable t) {
                    LOGGER.error("Failed to convert pack for mod {}", entry.getKey(), t);
                }
            }, THREAD_POOL));
        }

        // Wait for all futures to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        LOGGER.info("Converted {} packs for mods in {}", packsToLoad.size(), FormatUtil.humanReadableFormat(System.currentTimeMillis() - start));
    }

    /**
     * Checks if the pack needs to be converted based on the generated UUID.
     * This allows pack regeneration if the mod file has changed.
     *
     * @param mod The mod to check.
     * @param packPath The path to the pack.
     * @return {@code true} if the pack needs to be converted.
     */
    private boolean checkNeedsConversion(ModInfo mod, Path packPath, String fingerprint) {
        try (ZipFile zip = new ZipFile(packPath.toFile())) {
            var manifestEntry = zip.getEntry("manifest.json");
            var generationMarkerEntry = zip.getEntry(PackManager.PACK_GENERATION_MARKER);
            if (manifestEntry == null || generationMarkerEntry == null) {
                return true;
            }

            try (InputStream markerStream = zip.getInputStream(generationMarkerEntry);
                 InputStreamReader markerReader = new InputStreamReader(markerStream)) {
                JsonObject marker = GSON.fromJson(markerReader, JsonObject.class);
                if (marker == null || !marker.has("revision")
                        || !PackManager.PACK_GENERATION_REVISION.equals(marker.get("revision").getAsString())
                        || !marker.has("fingerprint")
                        || !fingerprint.equals(marker.get("fingerprint").getAsString())) {
                    return true;
                }
            }

            try (InputStream inputStream = zip.getInputStream(manifestEntry);
                 InputStreamReader reader = new InputStreamReader(inputStream)) {
                Manifest manifest = GSON.fromJson(reader, Manifest.class);
                if (manifest == null || manifest.header() == null || manifest.header().uuid() == null) {
                    return true;
                }

                return !fingerprint.equals(manifest.header().uuid());
            }
        } catch (IOException | RuntimeException exception) {
            // A partial or invalid cached archive must be regenerated rather than
            // preventing Geyser from loading every other converted pack.
            return true;
        }
    }

    private record PackRequest(ModInfo mod, Path packPath, String fingerprint) {
    }
}

