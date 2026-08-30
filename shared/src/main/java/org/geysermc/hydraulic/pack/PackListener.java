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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.ZipFile;

/**
 * Listens for events related to packs.
 */
public class PackListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static final long STARTUP_CONVERSION_BUDGET_MILLIS = 35_000;
    private static final int CONVERSION_THREADS = Math.max(1, Math.min(2,
            (Runtime.getRuntime().availableProcessors() + 1) / 2));
    private static final ExecutorService THREAD_POOL;

    private final HydraulicImpl hydraulic;
    private final PackManager manager;

    static {
        THREAD_POOL = Executors.newFixedThreadPool(
            CONVERSION_THREADS,
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
            THREAD_POOL.shutdownNow(); // Staged archives make interruption safe on server stop.
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
        CompletableFuture.runAsync(() ->
                VanillaPackProvider.create(this.manager.getVanillaPath(), version, new PackLogListener(LOGGER)));
    }

    @Subscribe(postOrder = PostOrder.LATE)
    public void onLoadResourcePacks(GeyserDefineResourcePacksEvent event) {
        long startedAt = System.nanoTime();
        PackPlan plan;
        LOGGER.info("Planning Hydraulic packs with {} conversion worker(s) and a {} ms startup budget",
                CONVERSION_THREADS, STARTUP_CONVERSION_BUDGET_MILLIS);
        CompletableFuture<PackPlan> planning = CompletableFuture.supplyAsync(this::planPacks, THREAD_POOL);
        try {
            plan = planning.get(STARTUP_CONVERSION_BUDGET_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            planning.cancel(true);
            LOGGER.error("Skipped Hydraulic pack planning after {} ms to keep server startup below the watchdog limit",
                    STARTUP_CONVERSION_BUDGET_MILLIS);
            return;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOGGER.error("Interrupted while planning Hydraulic packs");
            return;
        } catch (ExecutionException exception) {
            LOGGER.error("Failed to plan Hydraulic packs", exception.getCause());
            return;
        }
        LOGGER.info("Pack planning completed in {} ms [reuse={}, conversion={}]",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
                plan.reusable().size(), plan.toConvert().size());

        for (PackRequest request : plan.reusable()) {
            LOGGER.info("Reusing converted pack for mod {} [revision={}]", request.mod().id(), PackManager.PACK_GENERATION_REVISION);
            event.register(ResourcePack.create(PackCodec.path(request.packPath())), PriorityOption.NORMAL);
        }

        List<PackRequest> packsToLoad = plan.toConvert();
        if (packsToLoad.isEmpty()) {
            logSummary(plan, List.of(), 0, 0, List.of());
            return;
        }

        LOGGER.info("Found {} packs to convert!", packsToLoad.size());

        List<CompletableFuture<PackResult>> futures = new ArrayList<>();
        for (PackRequest request : packsToLoad) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                LOGGER.info("Converting pack for mod {}", request.mod().id());
                try {
                    long conversionStarted = System.nanoTime();
                    return new PackResult(request.mod().id(), request.packPath(),
                            this.manager.createPack(request.mod(), request.packPath(), request.fingerprint()),
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - conversionStarted));
                } catch (Throwable t) {
                    LOGGER.error("Failed to convert pack for mod {}", request.mod().id(), t);
                    return new PackResult(request.mod().id(), request.packPath(), PackManager.PackCreationResult.FAILED, 0);
                }
            }, THREAD_POOL));
        }

        List<PackResult> completed = awaitCompleted(futures, remainingBudgetMillis(startedAt));
        int registered = 0;
        int skippedEmpty = 0;
        for (PackResult result : completed) {
            if (result.outcome() == PackManager.PackCreationResult.CREATED) {
                event.register(ResourcePack.create(PackCodec.path(result.packPath())), PriorityOption.NORMAL);
                registered++;
            } else if (result.outcome() == PackManager.PackCreationResult.METADATA_ONLY) {
                skippedEmpty++;
            }
        }

        List<String> unfinishedMods = new ArrayList<>();
        for (int index = 0; index < futures.size(); index++) {
            if (!futures.get(index).isDone()) {
                unfinishedMods.add(packsToLoad.get(index).mod().id());
            }
        }
        if (!unfinishedMods.isEmpty()) {
            futures.stream().filter(future -> !future.isDone()).forEach(future -> future.cancel(true));
            LOGGER.error("Skipped {} pack conversion(s) after {} ms to keep server startup below the watchdog limit: {}; completed archives remain available on the next start",
                    unfinishedMods.size(), STARTUP_CONVERSION_BUDGET_MILLIS, String.join(", ", unfinishedMods));
        }

        LOGGER.info("Registered {} of {} converted packs in {}", registered, packsToLoad.size(),
                FormatUtil.humanReadableFormat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)));
        logSummary(plan, completed, registered, skippedEmpty, unfinishedMods);
    }

    private PackPlan planPacks() {
        // Check if hydraulic has updated since the last pack conversion
        // This is so we can regenerate packs on update in case the pack generation logic has changed
        ModInfo hydraulicMod = this.hydraulic.mod(Constants.MOD_ID);
        String hydraulicFingerprint = PackUtil.getModUUID(hydraulicMod.roots()).toString();
        boolean hydraulicUpdated = needsConversion(this.hydraulic.modStorage(hydraulicMod).pack(), hydraulicFingerprint);

        if (hydraulicUpdated) {
            LOGGER.info("Hydraulic has updated since the last pack conversion, regenerating all packs!");
        }

        // Go over all mods and load the pack or mark them for conversion
        List<PackRequest> reusable = new ArrayList<>();
        List<PackRequest> packsToLoad = new ArrayList<>();
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
            if (this.hydraulic.isDev() || hydraulicUpdated || needsConversion(packPath, fingerprint)) {
                packsToLoad.add(new PackRequest(mod, packPath, fingerprint));
            } else {
                reusable.add(new PackRequest(mod, packPath, fingerprint));
            }
        }
        return new PackPlan(reusable, packsToLoad);
    }

    /** Waits only within the startup budget, then collects every completed result. */
    static <T> List<T> awaitCompleted(List<CompletableFuture<T>> futures, long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        Set<T> completed = new LinkedHashSet<>();
        for (CompletableFuture<T> future : futures) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            try {
                completed.add(future.get(remaining, TimeUnit.NANOSECONDS));
            } catch (TimeoutException ignored) {
                break;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException | CancellationException exception) {
                LOGGER.error("Pack conversion task failed before completion", exception);
            }
        }
        for (CompletableFuture<T> future : futures) {
            if (future.isDone() && !future.isCompletedExceptionally() && !future.isCancelled()) {
                completed.add(future.getNow(null));
            }
        }
        return List.copyOf(completed);
    }

    private static long remainingBudgetMillis(long startedAt) {
        return Math.max(0, STARTUP_CONVERSION_BUDGET_MILLIS
                - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
    }

    /**
     * Checks if the pack needs to be converted based on the generated UUID.
     * This allows pack regeneration if the mod file has changed.
     *
     * @param packPath The path to the pack.
     * @param fingerprint The source-mod fingerprint expected in the generated pack.
     * @return {@code true} if the pack needs to be converted.
     */
    static boolean needsConversion(Path packPath, String fingerprint) {
        if (packPath.getFileName().toString().endsWith(".part")) return true;
        try (ZipFile zip = new ZipFile(packPath.toFile())) {
            var manifestEntry = zip.getEntry("manifest.json");
            var generationMarkerEntry = zip.getEntry(PackManager.PACK_GENERATION_MARKER);
            if (manifestEntry == null || generationMarkerEntry == null) {
                return true;
            }
            if (!PackArchiveValidator.hasAssets(zip)) return true;

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

    private void logSummary(PackPlan plan, List<PackResult> completed, int converted, int skippedEmpty, List<String> deferred) {
        int detected = plan.reusable().size() + plan.toConvert().size();
        LOGGER.info("Hydraulic: {} detected | {} reused | {} converted | {} skipped-empty | {} deferred",
                detected, plan.reusable().size(), converted, skippedEmpty, deferred.size());
        List<PackResult> slowest = completed.stream().sorted(java.util.Comparator.comparingLong(PackResult::millis).reversed()).limit(2).toList();
        if (!slowest.isEmpty()) {
            LOGGER.info("Slowest: {}", slowest.stream().map(result -> result.modId() + " " + result.millis() + "ms (conversion)")
                    .collect(java.util.stream.Collectors.joining(", ")));
        }
        PackManager.Quality quality = java.util.stream.Stream.concat(plan.reusable().stream().map(request -> request.mod().id()),
                        completed.stream().map(PackResult::modId))
                .map(manager::qualityFor)
                .reduce(PackManager.Quality.EMPTY, PackManager.Quality::plus);
        LOGGER.info("Quality: {} native geometries | {} generic entity fallbacks | {} unresolved item assets",
                quality.nativeGeometries(), quality.genericEntityFallbacks(), quality.unresolvedItemAssets());
    }

    private record PackRequest(ModInfo mod, Path packPath, String fingerprint) {
    }

    private record PackPlan(List<PackRequest> reusable, List<PackRequest> toConvert) {
    }

    private record PackResult(String modId, Path packPath, PackManager.PackCreationResult outcome, long millis) {
    }
}

