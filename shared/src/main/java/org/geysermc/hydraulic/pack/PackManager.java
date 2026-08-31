package org.geysermc.hydraulic.pack;

import com.google.common.collect.ListMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.entity.EntityType;
import org.geysermc.event.Event;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.hydraulic.Constants;
import org.geysermc.hydraulic.HydraulicImpl;
import org.geysermc.hydraulic.pack.context.PackEventContext;
import org.geysermc.hydraulic.pack.context.PackPostProcessContext;
import org.geysermc.hydraulic.pack.context.PackPreProcessContext;
import org.geysermc.hydraulic.pack.converter.CustomModelConverter;
import org.geysermc.hydraulic.util.IOUtil;
import org.geysermc.hydraulic.pack.modules.MetadataPackModule;
import org.geysermc.hydraulic.platform.mod.ModInfo;
import org.geysermc.pack.converter.PackConverter;
import org.geysermc.pack.converter.type.entity.EntityModelScanner;
import org.geysermc.pack.converter.type.entity.ReflectionInput;
import org.geysermc.pack.converter.pipeline.AssetConverters;
import org.geysermc.pack.converter.pipeline.ConverterPipeline;
import org.geysermc.pack.converter.type.model.ModelStitcher;
import org.geysermc.pack.converter.util.NioDirectoryFileTreeReader;
import org.geysermc.pack.converter.util.VanillaPackProvider;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import team.unnamed.creative.ResourcePack;
import team.unnamed.creative.model.Model;
import team.unnamed.creative.serialize.minecraft.MinecraftResourcePackReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.Arrays;
import java.util.stream.Stream;

/**
 * Manages packs within Hydraulic. Most of the pack conversion
 * management is done within this class, and it is also responsible
 * for loading the packs onto the server.
 */
public class PackManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Increment when the generated Bedrock-pack contract changes. This keeps
     * cached packs from surviving a Hydraulic update that changes conversion.
     */
    // Bump whenever generated pack semantics change so a restart cannot reuse
    // an archive missing newly required files or bindings.
    public static final String PACK_GENERATION_REVISION = "24";
    public static final String PACK_GENERATION_MARKER = "hydraulic-generation.json";

    static final Set<String> IGNORED_MODS = Set.of(
            // Fabric
            "geyser-fabric",
            "fabric-permissions-api-v0",

            // NeoForge
            "geyser-neoforge",
            "neoforge",
            "minecraft",

            // Common
            "floodgate",
            "mixinextras",
            "cloud"
    );

    private final HydraulicImpl hydraulic;
    private final Path vanillaPath;
    private final PackProfile profile;
    private final List<PackModule<?>> modules = new ArrayList<>();

    private final ListMultimap<String, ModInfo> namespacesToMods = MultimapBuilder.hashKeys().arrayListValues(1).build();
    private final ListMultimap<String, Identifier> modsToBlocks = MultimapBuilder.hashKeys().arrayListValues().build();
    private final ListMultimap<String, Identifier> modsToItems = MultimapBuilder.hashKeys().arrayListValues().build();
    private final ListMultimap<String, EntityType<?>> modsToEntities = MultimapBuilder.hashKeys().arrayListValues().build();

    private List<ConverterPipeline<?, ?>> packConverters;
    private ModelStitcher.Provider modelProvider;
    private Path clientRuntime;

    public PackManager(HydraulicImpl hydraulic) {
        this.hydraulic = hydraulic;
        this.vanillaPath = hydraulic.dataFolder(Constants.MOD_ID).resolve("cache/vanilla-assets.zip");
        this.profile = PackProfile.load(hydraulic.dataFolder(Constants.MOD_ID), LOGGER);
        LOGGER.info("Hydraulic pack profile: {}", this.profile.id());
    }

    /**
     * Initializes the pack manager.
     */
    public void initialize() {
        initializeModLookups();

        final Collection<ModInfo> mods = this.hydraulic.mods();
        final Map<String, List<ResourcePack>> modPacks = Maps.newHashMapWithExpectedSize(mods.size());
        for (final ModInfo mod : mods) {
            List<ResourcePack> resourcePacks = new ArrayList<>();
            for (Path root : mod.roots()) {
                try {
                    resourcePacks.add(MinecraftResourcePackReader.minecraft().read(NioDirectoryFileTreeReader.read(root)));
                } catch (RuntimeException exception) {
                    LOGGER.warn("Failed to read resource root {} for mod {}; continuing with its remaining roots", root, mod.id(), exception);
                }
            }
            modPacks.put(mod.id(), resourcePacks);
        }

        try {
            Files.createDirectories(this.getVanillaPath().getParent());
        } catch (IOException e) {
            LOGGER.error("Failed to create cache dir");
        }

        VanillaPackProvider.create(
                this.getVanillaPath(),
                SharedConstants.getCurrentVersion().id(),
                new PackLogListener(LOGGER)
        );

        // Runtime Java-model extraction needs the unstripped client classes;
        // the vanilla asset cache above intentionally removes them.
        clientRuntime = hydraulic.dataFolder(Constants.MOD_ID).resolve(
                "cache/client-runtime-" + SharedConstants.getCurrentVersion().id() + ".jar");
        VanillaPackProvider.createClientRuntime(
                clientRuntime,
                SharedConstants.getCurrentVersion().id(),
                new PackLogListener(LOGGER)
        );

        modelProvider = createModelProvider(mods, modPacks, this.getVanillaPath());

        // The GeckoLib pipelines are flagged experimental upstream, but they must
        // run unconditionally here: without them no entity geometry or animation
        // is ever written, while EntityPackModule still emits client-entity
        // definitions referencing geometry.<ns>.<name> - the real Bedrock client
        // then silently renders nothing for every custom mob (observed live
        // 2026-08-24: packs shipped textures/render controllers but zero
        // geometry files, so all 144 registered mobs were invisible).
        this.packConverters = new ArrayList<>(AssetConverters.converters(true));
        this.packConverters.remove(AssetConverters.MODEL);
        this.packConverters.remove(AssetConverters.MANIFEST);
        this.packConverters.add(AssetConverters.create(
                new CustomModelConverter(modelProvider),
                AssetConverters.MODEL,
                AssetConverters.MODEL
        ));

        for (PackModule<?> module : ServiceLoader.load(PackModule.class)) {
            this.modules.add(module);

            GeyserApi.api().eventBus().register(this.hydraulic, module);
            module.eventListeners().forEach((eventClass, listeners) -> {
                GeyserApi.api().eventBus().subscribe(this.hydraulic, eventClass, this::callEvents);
            });

            for (ModInfo mod : mods) {
                if (IGNORED_MODS.contains(mod.id())) {
                    continue;
                }

                if (module.hasPreProcessors()) {
                    try {
                        module.preProcess0(new PackPreProcessContext(this.hydraulic, mod, module, modPacks.get(mod.id()), modelProvider));
                    } catch (Throwable t) {
                        LOGGER.error("Failed to pre-process mod {} for module {}", mod.id(), module.getClass().getSimpleName(), t);
                    }
                }
            }
        }

        GeyserApi.api().eventBus().register(this.hydraulic, new PackListener(this.hydraulic, this));
    }

    /**
     * Creates the pack for the given mod.
     *
     * @param mod the mod to create the pack for
     * @param packPath the path to the pack
     * @return whether a pack was published, safely skipped as metadata-only, or failed
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    PackCreationResult createPack(@NotNull ModInfo mod, @NotNull Path packPath, @NotNull String fingerprint) {
        long startedAt = System.nanoTime();
        int blockCount = this.modsToBlocks.get(mod.id()).size();
        int itemCount = this.modsToItems.get(mod.id()).size();
        int entityCount = this.modsToEntities.get(mod.id()).size();
        ConversionReport report = new ConversionReport();
        Path stagedPack = stagedPackPath(packPath);
        LOGGER.info("Converting {} [blocks={}, items={}, entities={}, roots={}]", mod.id(), blockCount, itemCount, entityCount, mod.roots().size());
        if (profile == PackProfile.LITE && blockCount == 0 && itemCount == 0 && entityCount == 0) {
            cacheMetadataOnlyPack(packPath, fingerprint, mod.id());
            LOGGER.info("Lite profile omitted resource-only pack for mod {}", mod.id());
            return PackCreationResult.METADATA_ONLY;
        }
        List<ConverterPipeline<?, ?>> pipelines = new ArrayList<>(packConverters);
        pipelines.add(AssetConverters.create(new MetadataPackModule(mod, fingerprint)));

        PackPackager packager = new PackPackager();
        PackConverter converter = new PackConverter()
                .packName(mod.name())
                .logListener(new PackLogListener(LoggerFactory.getLogger(LOGGER.getName() + "/" + mod.id())))
                .converters(pipelines)
                .inputs(mod.roots())
                .output(stagedPack)
                .vanillaPackPath(vanillaPath)
                .textureSubdirectory(mod.namespace())
                .reflectionEntityIds(this.modsToEntities.get(mod.id()).stream()
                        .map(type -> BuiltInRegistries.ENTITY_TYPE.getKey(type).toString()).toList())
                .reflectionInput(reflectionInput(mod))
                .packageHandler(packager);

        converter.postProcessor((javaPack, bedrockPack) -> {
            long postProcessStartedAt = System.nanoTime();
            JsonObject generationMarker = new JsonObject();
            generationMarker.addProperty("revision", PACK_GENERATION_REVISION);
            generationMarker.addProperty("fingerprint", fingerprint);
            generationMarker.addProperty("profile", profile.id());
            generationMarker.addProperty("blocks", blockCount);
            generationMarker.addProperty("items", itemCount);
            generationMarker.addProperty("entities", entityCount);
            bedrockPack.addExtraFile(generationMarker, PACK_GENERATION_MARKER);

            try {
                for (PackModule<?> module : this.modules) {
                    PackPostProcessContext context = new PackPostProcessContext(this.hydraulic, mod, module, converter, javaPack, bedrockPack, packPath, modelProvider, report);
                    if (!module.test(context)) {
                        continue;
                    }

                    module.postProcess0(context);
                }
                PackTextureOptimizer.Result optimized = PackTextureOptimizer.optimize(bedrockPack.directory(), profile);
                report.resolution("pack-profile", "selected", profile.id());
                report.resolution("texture-pixels", "before", Long.toString(optimized.originalPixels()));
                report.resolution("texture-pixels", "after", Long.toString(optimized.outputPixels()));
                report.outcome("texture-resized", optimized.resized());
            } finally {
                report.timing("post_process", (System.nanoTime() - postProcessStartedAt) / 1_000_000);
            }
        });

        try {
            Files.deleteIfExists(stagedPack);

            for (final Path root : mod.roots()) {
                for (SourceResourceValidator.Finding finding : SourceResourceValidator.validate(root)) {
                    LOGGER.warn("Isolated malformed source resource for {}: {}", mod.id(), finding.description());
                    report.outcome("resource-isolated", finding.resource());
                    report.resolution("resource-diagnostics", finding.resource(), finding.description());
                }
            }
            long rootsStartedAt = System.nanoTime();
            converter.convert();
            for (EntityModelScanner.Diagnostic diagnostic : converter.entityModelDiagnostics()) {
                report.fallback("entity-reflection");
                report.outcome("entity-reflection-fallback", diagnostic.path());
                report.resolution("entity-reflection-fallback", diagnostic.path(), diagnostic.detail());
            }
            LOGGER.info("Conversion input {} combined {} root(s) in {} ms", mod.id(), mod.roots().size(),
                    (System.nanoTime() - rootsStartedAt) / 1_000_000);
        } catch (IOException | RuntimeException exception) {
            discardStagedPack(stagedPack, mod.id());
            LOGGER.error("Failed to convert mod {} to pack", mod.id(), exception);
            return PackCreationResult.FAILED;
        }
        long convertedAt = System.nanoTime();

        try {
            converter.pack();
        } catch (IOException | RuntimeException exception) {
            discardStagedPack(stagedPack, mod.id());
            LOGGER.error("Failed to export pack for mod {}", mod.id(), exception);
            return PackCreationResult.FAILED;
        }
        if (packager.metadataOnly()) {
            cacheMetadataOnlyPack(packPath, fingerprint, mod.id());
            return PackCreationResult.METADATA_ONLY;
        }
        long packagedAt = System.nanoTime();

        boolean created = Files.isRegularFile(stagedPack);
        if (created) {
            try {
                PackArchiveValidator.Result validation = PackArchiveValidator.validate(stagedPack);
                if (!validation.valid()) {
                    discardStagedPack(stagedPack, mod.id());
                    LOGGER.error("Discarded invalid pack for {}: {}", mod.id(), validation.errors());
                    return PackCreationResult.FAILED;
                }
                if (validation.metadataOnly()) {
                    discardStagedPack(stagedPack, mod.id());
                    cacheMetadataOnlyPack(packPath, fingerprint, mod.id());
                    return PackCreationResult.METADATA_ONLY;
                }
                if (!validation.warnings().isEmpty()) {
                    report.validationWarnings(validation.warnings());
                    int shown = Math.min(20, validation.warnings().size());
                    LOGGER.warn("Pack validation warnings for {} [{} total; showing {}]: {}{}", mod.id(), validation.warnings().size(), shown,
                            String.join("; ", validation.warnings().subList(0, shown)), validation.warnings().size() > shown ? " ..." : "");
                }
                long validatedAt = System.nanoTime();
                long assetsMillis = (convertedAt - startedAt) / 1_000_000;
                long packageMillis = (packagedAt - convertedAt) / 1_000_000;
                long validationMillis = (validatedAt - packagedAt) / 1_000_000;
                long archiveBytes = Files.size(stagedPack);
                report.timing("input", assetsMillis);
                report.timing("package", packageMillis);
                report.timing("validation", validationMillis);
                report.timing("total", (validatedAt - startedAt) / 1_000_000);
                LOGGER.info("Conversion report {} [blocks={}, items={}, entities={}, fallback={}, files={}, {} ms, {} bytes]", mod.id(),
                        blockCount, itemCount, entityCount, report.fallbackSummary(), validation.files(),
                        (validatedAt - startedAt) / 1_000_000, archiveBytes);
                LOGGER.info("Conversion timings {} [assets={} ms, package={} ms, validation={} ms]", mod.id(),
                        assetsMillis, packageMillis, validationMillis);
                publish(stagedPack, packPath);
                PackListener.deleteMetadataOnlyMarker(packPath);
                Path reportPath = this.hydraulic.dataFolder(Constants.MOD_ID).resolve("reports").resolve(mod.id() + ".json");
                try {
                    Files.createDirectories(reportPath.getParent());
                    writeStringAtomically(reportPath, report.json(blockCount, itemCount, entityCount, archiveBytes).toString());
                } catch (IOException exception) {
                    LOGGER.warn("Could not write conversion report for {}", mod.id(), exception);
                }
            } catch (IOException exception) {
                discardStagedPack(stagedPack, mod.id());
                LOGGER.error("Discarded unreadable staged pack for {}", mod.id(), exception);
                return PackCreationResult.FAILED;
            }
        }
        return created ? PackCreationResult.CREATED : PackCreationResult.FAILED;
    }

    static Path stagedPackPath(Path packPath) {
        return packPath.resolveSibling(packPath.getFileName() + ".part");
    }

    static void publish(Path stagedPack, Path packPath) throws IOException {
        try {
            Files.move(stagedPack, packPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException ignored) {
            Files.move(stagedPack, packPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void writeStringAtomically(Path target, String contents) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".part");
        Files.writeString(temporary, contents, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void cacheMetadataOnlyPack(Path packPath, String fingerprint, String modId) {
        try {
            PackListener.writeMetadataOnlyMarker(packPath, fingerprint);
            Files.deleteIfExists(packPath);
            LOGGER.info("Cached metadata-only result for {}: no converted Bedrock assets", modId);
        } catch (IOException exception) {
            LOGGER.warn("Skipped metadata-only pack for {}, but could not persist its cache marker", modId, exception);
        }
    }

    Quality qualityFor(String modId) {
        Path report = this.hydraulic.dataFolder(Constants.MOD_ID).resolve("reports").resolve(modId + ".json");
        try {
            return qualityFromReport(JsonParser.parseString(IOUtil.readString(report, StandardCharsets.UTF_8, 8 * 1024 * 1024)).getAsJsonObject());
        } catch (Exception ignored) {
            return Quality.EMPTY;
        }
    }

    static Quality qualityFromReport(JsonObject report) {
        JsonObject outcomes = report.getAsJsonObject("outcomes");
        if (outcomes == null) return Quality.EMPTY;
        int nativeGeometries = outcome(outcomes, "entity-native-geometry");
        int nativeGenericAnimation = outcome(outcomes, "entity-native-generic-animation");
        int hitboxFallbacks = outcome(outcomes, "entity-hitbox");
        int unresolvedItems = outcome(outcomes, "item-unresolved") + outcome(outcomes, "item-missing-output-texture")
                + outcome(outcomes, "item-model-stitch-failed") + outcome(outcomes, "item-model-no-layer");
        return new Quality(Math.max(0, nativeGeometries - nativeGenericAnimation), nativeGenericAnimation, hitboxFallbacks, unresolvedItems);
    }

    private static int outcome(JsonObject outcomes, String key) {
        return outcomes.has(key) ? outcomes.get(key).getAsInt() : 0;
    }

    private static void discardStagedPack(Path stagedPack, String modId) {
        try {
            Files.deleteIfExists(stagedPack);
        } catch (IOException exception) {
            LOGGER.warn("Could not remove incomplete staged pack for {} at {}", modId, stagedPack, exception);
        }
    }

    private ReflectionInput reflectionInput(ModInfo mod) {
        Path sourceJar = mod.sourceJar();
        if (sourceJar == null) return null;
        List<Path> classpath = new ArrayList<>();
        for (ModInfo installed : hydraulic.mods()) {
            Path jar = installed.sourceJar();
            if (jar != null) classpath.add(jar);
        }
        Arrays.stream(System.getProperty("java.class.path", "").split(java.io.File.pathSeparator))
                .map(Path::of).filter(Files::exists).forEach(classpath::add);
        // Minecraft exposes JOML through the launcher rather than java.class.path.
        // The reflection loader is intentionally isolated, so pass that exact
        // runtime location instead of bundling a second, potentially incompatible
        // JOML copy into Hydraulic.
        addCodeSource(classpath, "org.joml.Quaternionfc");
        addCodeSource(classpath, "org.joml.Matrix4fc");
        return new ReflectionInput(sourceJar, classpath, Files.isRegularFile(clientRuntime) ? clientRuntime : null);
    }

    static void addCodeSource(List<Path> classpath, String className) {
        try {
            Class<?> type = Class.forName(className, false, PackManager.class.getClassLoader());
            if (type.getProtectionDomain() == null || type.getProtectionDomain().getCodeSource() == null) return;
            URI location = type.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path path = Path.of(location);
            if ((Files.isRegularFile(path) || Files.isDirectory(path)) && !classpath.contains(path)) classpath.add(path);
        } catch (ClassNotFoundException | LinkageError | SecurityException | URISyntaxException ignored) {
            // The parser will emit its normal per-entity diagnostic if an
            // optional model-library dependency is genuinely unavailable.
        }
    }

    enum PackCreationResult {
        CREATED,
        METADATA_ONLY,
        FAILED
    }

    record Quality(int fullNativeGeometries, int nativeGeometriesWithGenericAnimation, int hitboxGeometryFallbacks,
                   int unresolvedItemAssets) {
        static final Quality EMPTY = new Quality(0, 0, 0, 0);

        Quality plus(Quality other) {
            return new Quality(fullNativeGeometries + other.fullNativeGeometries,
                    nativeGeometriesWithGenericAnimation + other.nativeGeometriesWithGenericAnimation,
                    hitboxGeometryFallbacks + other.hitboxGeometryFallbacks,
                    unresolvedItemAssets + other.unresolvedItemAssets);
        }
    }

    private void callEvents(@NotNull Event event) {
        for (ModInfo mod : this.hydraulic.mods()) {
            if (IGNORED_MODS.contains(mod.id())) {
                continue;
            }

            this.callEvent(mod, event);
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private void callEvent(@NotNull ModInfo mod, @NotNull Event event) {
        for (PackModule<?> module : this.modules) {
            module.call(event.getClass(), new PackEventContext(this.hydraulic, mod, module, event));
        }
    }

    private void initializeModLookups() {
        // Step 1: Lookup which namespaces are contained by which mods
        final Multimap<String, ModInfo> namespacesToMods = this.namespacesToMods;
        namespacesToMods.clear();
        for (final ModInfo mod : hydraulic.mods()) {
            for (final Path root : mod.roots()) {
                final Path assets = root.resolve("assets");
                if (!Files.isDirectory(assets)) continue;
                try (Stream<Path> stream = Files.list(assets)) {
                    stream.filter(Files::isDirectory)
                        .map(Path::getFileName)
                        .map(Path::toString)
                        .filter(namespace -> !namespace.equals("minecraft"))
                        .forEach(namespace -> namespacesToMods.put(namespace, mod));
                } catch (IOException e) {
                    LOGGER.error("Failed to list namespaces for mod {}", mod.id(), e);
                }
            }
        }

        // Step 2: Use namespace information to lookup which mods contains what block models
        final Multimap<String, Identifier> modsToBlocks = this.modsToBlocks;
        modsToBlocks.clear();
        for (final Identifier block : BuiltInRegistries.BLOCK.keySet()) {
            if (block.getNamespace().equals("minecraft")) continue;
            for (final ModInfo mod : namespacesToMods.get(block.getNamespace())) {
                final Path checkFile = mod.resolveFile("assets/" + block.getNamespace() + "/blockstates/" + block.getPath() + ".json");
                if (checkFile != null) {
                    modsToBlocks.put(mod.id(), block);
                    break;
                } else {
                    LOGGER.warn("Failed to find path for block state {}, skipping", block);
                }
            }
        }

        // Step 3: Use namespace information to lookup which mods contains what item models
        // There's no ordering requirement between this and Step 2.
        final Multimap<String, Identifier> modsToItems = this.modsToItems;
        modsToItems.clear();
        for (final Identifier itemId : BuiltInRegistries.ITEM.keySet()) {
            if (itemId.getNamespace().equals("minecraft")) continue;

            Item item = BuiltInRegistries.ITEM.getValue(itemId);
            Identifier itemModel = item.components().get(DataComponents.ITEM_MODEL);
            // Item model is missing, can't do much here
            if (itemModel == null) {
                LOGGER.warn("Failed to find item model component for item {}, skipping", item);
                continue;
            }

            for (final ModInfo mod : namespacesToMods.get(itemId.getNamespace())) {
                final Path checkFile = mod.resolveFile("assets/" + itemModel.getNamespace() + "/items/" + itemModel.getPath() + ".json");
                if (checkFile != null) {
                    modsToItems.put(mod.id(), itemId);
                    break;
                } else {
                    LOGGER.warn("Failed to find path for item {}, skipping", item);
                }
            }
        }

        // Step 4: Map non-vanilla entity types to their mods by namespace.
        // Unlike blocks/items there is no asset file to verify - an entity type is
        // attributed to the mod owning its namespace, assets are matched later by
        // the EntityPackModule using naming conventions.
        final Multimap<String, EntityType<?>> modsToEntities = this.modsToEntities;
        modsToEntities.clear();
        for (final EntityType<?> entityType : BuiltInRegistries.ENTITY_TYPE) {
            final Identifier entityKey = BuiltInRegistries.ENTITY_TYPE.getKey(entityType);
            if (entityKey == null || entityKey.getNamespace().equals("minecraft")) continue;

            for (final ModInfo mod : namespacesToMods.get(entityKey.getNamespace())) {
                modsToEntities.put(mod.id(), entityType);
                break;
            }
        }
    }

    /**
     * Creates a {@link ModelStitcher.Provider} that first searches mods, then the Vanilla pack.
     *
     * @param mods The mods to search through.
     * @param modPacks A {@link Map} from mod ID to a {@link List} of {@link ResourcePack}s contained within that mod.
     *                 There may be multiple {@link ResourcePack}s in a mod if there are multiple resource roots for the
     *                 mod.
     * @return A {@link ModelStitcher.Provider} that searches through mods and the Vanilla pack.
     */
    private static ModelStitcher.Provider createModelProvider(
        Collection<ModInfo> mods,
        Map<String, List<ResourcePack>> modPacks,
        Path vanillaPath
    ) {
        final List<ResourcePack> flattenedPacks = mods.stream()
            .map(ModInfo::id)
            .map(modPacks::get)
            .flatMap(List::stream)
            .toList();

        ResourcePack vanillaResourcePack = MinecraftResourcePackReader.minecraft().readFromZipFile(vanillaPath);

        return key -> {
            for (final ResourcePack pack : flattenedPacks) {
                final Model model = pack.model(key);
                if (model != null) {
                    return model;
                }
            }
            return vanillaResourcePack.model(key);
        };
    }

    public ListMultimap<String, ModInfo> getNamespacesToMods() {
        return namespacesToMods;
    }

    public ListMultimap<String, Identifier> getModsToBlocks() {
        return modsToBlocks;
    }

    public ListMultimap<String, Identifier> getModsToItems() {
        return modsToItems;
    }

    /**
     * Gets the mod id to entity type multimap, containing every non-vanilla
     * entity type attributed to the mod owning its namespace.
     *
     * @return the mod id to entity type multimap
     */
    public ListMultimap<String, EntityType<?>> getModsToEntities() {
        return modsToEntities;
    }

    public Path getVanillaPath() {
        return vanillaPath;
    }

    PackProfile profile() {
        return profile;
    }
}
