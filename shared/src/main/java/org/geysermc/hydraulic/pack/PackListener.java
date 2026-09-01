package org.geysermc.hydraulic.pack;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.SharedConstants;
import org.geysermc.event.PostOrder;
import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineResourcePacksEvent;
import org.geysermc.geyser.api.pack.PackCodec;
import org.geysermc.geyser.api.pack.ResourcePack;
import org.geysermc.geyser.api.pack.option.PriorityOption;
import org.geysermc.hydraulic.Constants;
import org.geysermc.hydraulic.entity.EntityEventRegistrar;
import org.geysermc.hydraulic.util.IOUtil;
import org.geysermc.hydraulic.HydraulicImpl;
import org.geysermc.hydraulic.platform.mod.ModInfo;
import org.geysermc.hydraulic.storage.ModStorage;
import org.geysermc.hydraulic.util.FormatUtil;
import org.geysermc.hydraulic.util.PackUtil;
import org.geysermc.pack.bedrock.resource.Manifest;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ConcurrentHashMap;
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
    private final HydraulicImpl hydraulic;
    private final PackManager manager;
    private final Map<String, CompletableFuture<PackResult>> conversions = new ConcurrentHashMap<>();
    private ExecutorService threadPool = newThreadPool();

    public PackListener(HydraulicImpl hydraulic, PackManager manager) {
        this.hydraulic = hydraulic;
        this.manager = manager;

        hydraulic.registerServerStop(server -> {
            synchronized (this) {
                threadPool.shutdownNow(); // Staged archives make interruption safe on server stop.
            }
        });

    }

    @Subscribe(postOrder = PostOrder.LATE)
    public void onLoadResourcePacks(GeyserDefineResourcePacksEvent event) {
        long startedAt = System.nanoTime();
        EntityEventRegistrar.resetPackBacked();
        long preparationBudget = Math.min(10_000, remainingBudgetMillis(startedAt));
        if (!manager.awaitPreparation(preparationBudget)) {
            LOGGER.error("Hydraulic pack inputs were not ready after {} ms; pack delivery is deferred to the next resource-pack event or server start",
                    preparationBudget);
            return;
        }
        PackPlan plan;
        LOGGER.info("Planning Hydraulic packs with {} conversion worker(s) and a {} ms startup budget",
                CONVERSION_THREADS, STARTUP_CONVERSION_BUDGET_MILLIS);
        ExecutorService executor = executor();
        CompletableFuture<PackPlan> planning = CompletableFuture.supplyAsync(this::planPacks, executor);
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
        LOGGER.info("Pack planning completed in {} ms [reuse={}, skipped-empty={}, conversion={}]",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
                plan.reusable().size(), plan.cachedEmpty().size(), plan.toConvert().size());

        for (PackRequest request : plan.reusable()) {
            LOGGER.info("Reusing converted pack for mod {} [revision={}]", request.mod().id(), PackManager.PACK_GENERATION_REVISION);
            int restoredEntities = EntityEventRegistrar.restorePackBacked(request.packPath());
            if (restoredEntities > 0) {
                LOGGER.info("Restored {} pack-backed entities for mod {}", restoredEntities, request.mod().id());
            }
            event.register(ResourcePack.create(PackCodec.path(request.packPath())), PriorityOption.NORMAL);
        }
        List<PackRequest> packsToLoad = plan.toConvert();
        if (packsToLoad.isEmpty()) {
            logDeliveryPlan(plan.reusable().stream().map(PackRequest::packPath).toList());
            logSummary(plan, List.of(), 0, 0, List.of());
            return;
        }

        LOGGER.info("Found {} packs to convert!", packsToLoad.size());

        List<CompletableFuture<PackResult>> futures = new ArrayList<>();
        for (PackRequest request : packsToLoad) {
            String conversionKey = request.mod().id() + ":" + request.fingerprint();
            CompletableFuture<PackResult> conversion = conversions.computeIfAbsent(conversionKey, ignored -> CompletableFuture.supplyAsync(() -> {
                LOGGER.info("Converting pack for mod {}", request.mod().id());
                try {
                    long conversionStarted = System.nanoTime();
                    return new PackResult(request.mod().id(), request.packPath(),
                            this.manager.createPack(request.mod(), request.packPath(), request.fingerprint()),
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - conversionStarted));
                } catch (Exception | LinkageError exception) {
                    LOGGER.error("Failed to convert pack for mod {}", request.mod().id(), exception);
                    return new PackResult(request.mod().id(), request.packPath(), PackManager.PackCreationResult.FAILED, 0);
                }
            }, executor));
            conversion.whenComplete((result, failure) -> {
                if (failure != null || result == null || result.outcome() == PackManager.PackCreationResult.FAILED) {
                    conversions.remove(conversionKey, conversion);
                }
            });
            futures.add(conversion);
        }

        List<PackResult> completed = awaitCompleted(futures, remainingBudgetMillis(startedAt));
        int registered = 0;
        int newlySkippedEmpty = 0;
        for (PackResult result : completed) {
            if (result.outcome() == PackManager.PackCreationResult.CREATED) {
                event.register(ResourcePack.create(PackCodec.path(result.packPath())), PriorityOption.NORMAL);
                registered++;
            } else if (result.outcome() == PackManager.PackCreationResult.METADATA_ONLY) {
                newlySkippedEmpty++;
            }
        }

        List<String> unfinishedMods = new ArrayList<>();
        for (int index = 0; index < futures.size(); index++) {
            if (!futures.get(index).isDone()) {
                unfinishedMods.add(packsToLoad.get(index).mod().id());
            }
        }
        if (!unfinishedMods.isEmpty()) {
            LOGGER.error("Deferred registration of {} pack conversion(s) after {} ms to keep server startup below the watchdog limit: {}; workers may finish atomic archives for the next start",
                    unfinishedMods.size(), STARTUP_CONVERSION_BUDGET_MILLIS, String.join(", ", unfinishedMods));
        }

        LOGGER.info("Registered {} of {} converted packs in {}", registered, packsToLoad.size(),
                FormatUtil.humanReadableFormat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)));
        logSummary(plan, completed, registered, newlySkippedEmpty, unfinishedMods);
        List<Path> delivered = new ArrayList<>(plan.reusable().stream().map(PackRequest::packPath).toList());
        completed.stream().filter(result -> result.outcome() == PackManager.PackCreationResult.CREATED)
                .map(PackResult::packPath).forEach(delivered::add);
        logDeliveryPlan(delivered);
    }

    private PackPlan planPacks() {
        Map<String, String> sourceFingerprints = this.hydraulic.mods().stream()
                .collect(java.util.stream.Collectors.toMap(ModInfo::id,
                        mod -> PackUtil.getModUUID(mod.roots()).toString(), (left, right) -> right,
                        java.util.TreeMap::new));
        String contextFingerprint = PackUtil.getContextUUID(sourceFingerprints).toString();

        // Go over all mods and load the pack or mark them for conversion
        List<PackRequest> reusable = new ArrayList<>();
        List<PackRequest> cachedEmpty = new ArrayList<>();
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
            String fingerprint = PackUtil.getPackUUID(sourceFingerprints.get(mod.id()), contextFingerprint,
                    SharedConstants.getCurrentVersion().id(), manager.identityRevision()).toString();
            PackRequest request = new PackRequest(mod, packPath, fingerprint);
            CacheStatus status = this.hydraulic.isDev() ? CacheStatus.CONVERT : cacheStatus(packPath, fingerprint);
            switch (status) {
                case REUSE -> reusable.add(request);
                case SKIPPED_EMPTY -> cachedEmpty.add(request);
                case CONVERT -> packsToLoad.add(request);
            }
        }
        return new PackPlan(reusable, cachedEmpty, packsToLoad);
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

    private synchronized ExecutorService executor() {
        if (threadPool.isShutdown()) threadPool = newThreadPool();
        return threadPool;
    }

    private static ExecutorService newThreadPool() {
        return Executors.newFixedThreadPool(CONVERSION_THREADS,
                new ThreadFactoryBuilder()
                        .setNameFormat(Constants.MOD_NAME + " Conversion Thread #%d")
                        .setUncaughtExceptionHandler((thread, throwable) ->
                                LOGGER.error("Uncaught exception in thread {}", thread.getName(), throwable))
                        .build());
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

    /** Selects the one startup action allowed for a mod fingerprint. */
    static CacheStatus cacheStatus(Path packPath, String fingerprint) {
        if (!needsConversion(packPath, fingerprint)) {
            try {
                deleteMetadataOnlyMarker(packPath);
            } catch (IOException exception) {
                LOGGER.warn("Could not remove stale metadata-only marker for {}", packPath, exception);
            }
            return CacheStatus.REUSE;
        }
        if (isCachedMetadataOnly(packPath, fingerprint)) {
            try {
                Files.deleteIfExists(packPath);
            } catch (IOException exception) {
                LOGGER.warn("Could not remove invalid archive superseded by metadata marker {}", packPath, exception);
            }
            return CacheStatus.SKIPPED_EMPTY;
        }
        return CacheStatus.CONVERT;
    }

    static Path metadataOnlyMarkerPath(Path packPath) {
        return packPath.resolveSibling(packPath.getFileName() + ".empty.json");
    }

    static void writeMetadataOnlyMarker(Path packPath, String fingerprint) throws IOException {
        JsonObject marker = new JsonObject();
        marker.addProperty("revision", PackManager.PACK_GENERATION_REVISION);
        marker.addProperty("fingerprint", fingerprint);
        marker.addProperty("outcome", "metadata-only");
        Path markerPath = metadataOnlyMarkerPath(packPath);
        Files.createDirectories(markerPath.getParent());
        PackManager.writeStringAtomically(markerPath, marker.toString());
    }

    static void deleteMetadataOnlyMarker(Path packPath) throws IOException {
        Files.deleteIfExists(metadataOnlyMarkerPath(packPath));
    }

    private static boolean isCachedMetadataOnly(Path packPath, String fingerprint) {
        Path markerPath = metadataOnlyMarkerPath(packPath);
        try {
            JsonObject marker = JsonParser.parseString(IOUtil.readString(markerPath, StandardCharsets.UTF_8, 64 * 1024)).getAsJsonObject();
            return marker.has("revision")
                    && PackManager.PACK_GENERATION_REVISION.equals(marker.get("revision").getAsString())
                    && marker.has("fingerprint") && fingerprint.equals(marker.get("fingerprint").getAsString())
                    && marker.has("outcome") && "metadata-only".equals(marker.get("outcome").getAsString());
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private void logSummary(PackPlan plan, List<PackResult> completed, int converted, int newlySkippedEmpty, List<String> deferred) {
        int detected = plan.reusable().size() + plan.cachedEmpty().size() + plan.toConvert().size();
        int skippedEmpty = plan.cachedEmpty().size() + newlySkippedEmpty;
        int failed = (int) completed.stream().filter(result -> result.outcome() == PackManager.PackCreationResult.FAILED).count();
        LOGGER.info("Hydraulic: {} detected | {} reused | {} converted | {} skipped-empty | {} failed | {} deferred",
                detected, plan.reusable().size(), converted, skippedEmpty, failed, deferred.size());
        List<PackResult> slowest = completed.stream().sorted(java.util.Comparator.comparingLong(PackResult::millis).reversed()).limit(2).toList();
        if (!slowest.isEmpty()) {
            LOGGER.info("Slowest: {}", slowest.stream().map(result -> result.modId() + " " + result.millis() + "ms (conversion)")
                    .collect(java.util.stream.Collectors.joining(", ")));
        }
        PackManager.Quality quality = java.util.stream.Stream.concat(plan.reusable().stream().map(request -> request.mod().id()),
                        completed.stream().map(PackResult::modId))
                .map(manager::qualityFor)
                .reduce(PackManager.Quality.EMPTY, PackManager.Quality::plus);
        LOGGER.info("Quality: {} full native geometries | {} native geometries with generic animation | {} hitbox geometry fallbacks | {} unresolved item assets",
                quality.fullNativeGeometries(), quality.nativeGeometriesWithGenericAnimation(), quality.hitboxGeometryFallbacks(),
                quality.unresolvedItemAssets());
    }

    private static void logDeliveryPlan(List<Path> packs) {
        long bytes = 0;
        for (Path pack : packs) {
            try {
                bytes += Files.size(pack);
            } catch (IOException ignored) {
            }
        }
        LOGGER.info("Bedrock delivery plan: {} pack(s), {} bytes; client download/import/apply timing requires a real Bedrock session",
                packs.size(), bytes);
    }

    private record PackRequest(ModInfo mod, Path packPath, String fingerprint) {
    }

    enum CacheStatus { REUSE, SKIPPED_EMPTY, CONVERT }

    private record PackPlan(List<PackRequest> reusable, List<PackRequest> cachedEmpty, List<PackRequest> toConvert) {
    }

    private record PackResult(String modId, Path packPath, PackManager.PackCreationResult outcome, long millis) {
    }
}

