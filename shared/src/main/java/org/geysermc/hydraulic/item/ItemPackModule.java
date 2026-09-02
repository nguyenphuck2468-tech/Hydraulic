package org.geysermc.hydraulic.item;

import com.google.auto.service.AutoService;
import com.google.common.collect.Lists;
import com.mojang.logging.LogUtils;
import net.kyori.adventure.key.Key;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.DefaultedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.*;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomItemsEvent;
import org.geysermc.geyser.api.item.custom.v2.CustomItemBedrockOptions;
import org.geysermc.geyser.api.item.custom.v2.CustomItemDefinition;
import org.geysermc.geyser.api.item.custom.v2.NonVanillaCustomItemDefinition;
import org.geysermc.geyser.api.predicate.MinecraftPredicate;
import org.geysermc.geyser.api.predicate.PredicateStrategy;
import org.geysermc.geyser.api.predicate.context.item.ChargedProjectile;
import org.geysermc.geyser.api.predicate.context.item.ItemPredicateContext;
import org.geysermc.geyser.api.predicate.item.ItemConditionPredicate;
import org.geysermc.geyser.api.predicate.item.ItemMatchPredicate;
import org.geysermc.geyser.api.predicate.item.ItemRangeDispatchPredicate;
import org.geysermc.geyser.api.item.custom.v2.component.geyser.GeyserBlockPlacer;
import org.geysermc.geyser.api.item.custom.v2.component.geyser.GeyserChargeable;
import org.geysermc.geyser.api.item.custom.v2.component.geyser.GeyserItemDataComponents;
import org.geysermc.hydraulic.pack.PackLogListener;
import org.geysermc.hydraulic.pack.PackModule;
import org.geysermc.hydraulic.pack.TexturePackModule;
import org.geysermc.hydraulic.pack.context.PackEventContext;
import org.geysermc.hydraulic.pack.context.PackPostProcessContext;
import org.geysermc.hydraulic.pack.context.PackPreProcessContext;
import org.geysermc.hydraulic.platform.mod.ModInfo;
import org.geysermc.hydraulic.component.ComponentConverter;
import org.geysermc.hydraulic.util.HydraulicKey;
import org.geysermc.hydraulic.util.IOUtil;
import org.geysermc.hydraulic.util.PackUtil;
import org.geysermc.pack.bedrock.resource.BedrockResourcePack;
import org.geysermc.pack.converter.type.model.ModelStitcher;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import team.unnamed.creative.ResourcePack;
import team.unnamed.creative.item.*;
import team.unnamed.creative.item.property.*;
import team.unnamed.creative.model.Model;
import team.unnamed.creative.model.ModelTexture;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@AutoService(PackModule.class)
public class ItemPackModule extends TexturePackModule<ItemPackModule> {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Pattern RAW_TEXTURE = Pattern.compile("(?:[a-z0-9_.-]+:)?(?:item|block|gui)/[a-z0-9_./-]+");
    private final List<Identifier> itemsWith2dIcon = new ArrayList<>();
    private final List<Identifier> handheldItems = new ArrayList<>();
    private final Map<String, String> itemBuiltinTexture = new HashMap<>();
    private final Map<String, List<RuntimeVariant>> runtimeVariants = new HashMap<>();

    public ItemPackModule() {
        this.listenOn(GeyserDefineCustomItemsEvent.class, this::onDefineCustomItems);

        this.preProcess(this::preProcess);
        this.postProcess(this::postProcess);
    }

    private void handleModel(@NotNull PackPreProcessContext<ItemPackModule> context, ItemModel itemModel, Identifier itemLocation) {
        handleModel(context, itemModel, itemLocation, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private void handleModel(@NotNull PackPreProcessContext<ItemPackModule> context, ItemModel itemModel,
                             Identifier itemLocation, Set<ItemModel> visited) {
        if (itemModel == null || !visited.add(itemModel)) return;
        if (itemModel instanceof ReferenceItemModel referenceModel) {
            Key modelKey = referenceModel.model();

            List<Model> modelList = Lists.newArrayList(context.assets((pack) -> { // This can probably be done easier, but im not sure how
                Model model = pack.model(modelKey);
                if (model == null) return List.of();

                return List.of(model);
            }));
            if (modelList.isEmpty()) return;

            Model model = modelList.getFirst();
            Key modelParent = model.parent();
            if (modelParent == null) return;

            if (modelParent.value().equals("item/generated")) { // If the parent is item/generated, it's a 2D icon
                itemsWith2dIcon.add(itemLocation);
            } else if (modelParent.value().equals("item/handheld")) { // If the parent is item/handheld, it's handheld
                itemsWith2dIcon.add(itemLocation); // item/handheld has the parent item/generated, so lets assume it's 2D
                handheldItems.add(itemLocation);
            }
        } else if (itemModel instanceof SelectItemModel selectModel) {
            for (SelectItemModel.Case itemCase : selectModel.cases()) {
                handleModel(context, itemCase.model(), itemLocation, visited);
            }
            handleModel(context, selectModel.fallback(), itemLocation, visited);
        } else if (itemModel instanceof CompositeItemModel compositeModel) {
            // A composite can contain a 2D icon and a separate handheld model.
            // Inspect every child so either capability is preserved instead of
            // silently classifying the item from whichever child happens first.
            for (ItemModel child : compositeModel.models()) {
                handleModel(context, child, itemLocation, visited);
            }
        } else if (itemModel instanceof RangeDispatchItemModel rangeDispatchModel) {
            for (RangeDispatchItemModel.Entry entry : rangeDispatchModel.entries()) {
                handleModel(context, entry.model(), itemLocation, visited);
            }
            handleModel(context, rangeDispatchModel.fallback(), itemLocation, visited);
        }
    }

    private void preProcess(@NotNull PackPreProcessContext<ItemPackModule> context) {
        for (team.unnamed.creative.item.Item item : context.assets(ResourcePack::items)) {
            Identifier itemLocation = HydraulicKey.of(item.key()).identifier();
            handleModel(context, item.model(), itemLocation);
        }

//        for (Model model : context.assets(ResourcePack::models)) {
//            Key modelParent = model.parent();
//            if (modelParent != null) {
//                if (modelParent.value().equals("item/generated")) { // If the parent is item/generated, it's a 2D icon
//                    HydraulicKey key = HydraulicKey.of(model.key());
//                    key.path(key.path().replace("item/", ""));
//                    itemsWith2dIcon.add(key.location());
//                } else if (modelParent.value().equals("item/handheld")) { // If the parent is item/handheld, it's handheld
//                    HydraulicKey key = HydraulicKey.of(model.key());
//                    key.path(key.path().replace("item/", ""));
//                    itemsWith2dIcon.add(key.location()); // item/handheld has the parent item/generated, so lets assume it's 2D
//                    handheldItems.add(key.location());
//                }
//            }
//        }

        List<Item> items = context.registryValues(BuiltInRegistries.ITEM);
        PackLogListener packLogListener = new PackLogListener(context.logger());
        for (Item item : items) {
            Identifier itemLocation = BuiltInRegistries.ITEM.getKey(item);
            Identifier itemModelLocation = itemModelLocation(item, itemLocation);

            Model baseModel = context.modelProvider().model(Key.key(itemModelLocation.getNamespace(), "item/" + itemModelLocation.getPath()));
            if (baseModel == null && !itemModelLocation.equals(itemLocation)) {
                baseModel = context.modelProvider().model(Key.key(itemLocation.getNamespace(), "item/" + itemLocation.getPath()));
            }
            if (baseModel == null) {
                continue;
            }

            Model model = new ModelStitcher(context.modelProvider(), baseModel, packLogListener).stitch();
            if (model == null) {
                continue;
            }

            List<ModelTexture> layers = model.textures().layers();
            if (layers == null || layers.isEmpty()) {
                continue;
            }

            Key layer0 = layers.getFirst().key();

            if (layer0 != null && layer0.namespace().equals(Key.MINECRAFT_NAMESPACE)) {
                itemBuiltinTexture.put(itemLocation.toString(), PackUtil.getTextureName(layer0.toString()));
            }
        }
    }

    private void postProcess(@NotNull PackPostProcessContext<ItemPackModule> context) {
        ResourcePack assets = context.javaResourcePack();
        BedrockResourcePack bedrockPack = context.bedrockResourcePack();

        List<Item> items = context.registryValues(BuiltInRegistries.ITEM);

        context.logger().info("Items to convert: {} in mod {}", items.size(), context.mod().id());

        PackLogListener packLogListener = new PackLogListener(context.logger());
        for (Item item : items) {
            Identifier itemLocation = BuiltInRegistries.ITEM.getKey(item);
            List<String> unsupportedComponents = ComponentConverter.unsupportedComponents(item.components());
            if (!unsupportedComponents.isEmpty()) {
                context.report().outcome("item-component-dropped", itemLocation.toString());
                context.report().resolution("item-component-dropped", itemLocation.toString(),
                        String.join(",", unsupportedComponents));
            }
            Identifier itemModelLocation = itemModelLocation(item, itemLocation);
            Identifier blockLocation = item instanceof BlockItem blockItem ? BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()) : null;
            ItemAssetResolver.ResolvedItemAsset resolvedAsset = ItemAssetResolver.resolve(context.mod(), itemLocation, itemModelLocation, blockLocation);
            if (!resolvedAsset.dynamicModelKinds().isEmpty()) {
                context.report().outcome("item-dynamic-model", itemLocation.toString());
                context.report().resolution("item-dynamic-model", itemLocation.toString(),
                        resolvedAsset.dynamicModelKinds() + " candidates=" + resolvedAsset.candidateTextures());
            }

            team.unnamed.creative.item.Item creativeItem = assets.item(Key.key(itemModelLocation.toString()));
            if (creativeItem == null && !itemModelLocation.equals(itemLocation)) {
                creativeItem = assets.item(Key.key(itemLocation.toString()));
            }
            if (creativeItem != null) {
                runtimeVariants.remove(itemLocation.toString());
                List<RuntimeVariant> variants = new ArrayList<>();
                collectRuntimeVariants(context, bedrockPack, itemLocation, creativeItem.model(), List.of(), variants,
                        Collections.newSetFromMap(new IdentityHashMap<>()));
                if (!variants.isEmpty()) {
                    runtimeVariants.put(itemLocation.toString(), List.copyOf(variants));
                    context.report().outcome("item-runtime-variant", itemLocation.toString());
                    context.report().resolution("item-runtime-variant", itemLocation.toString(), Integer.toString(variants.size()));
                }
            }

            Model baseModel = assets.model(Key.key(itemModelLocation.getNamespace(), "item/" + itemModelLocation.getPath()));
            if (baseModel == null && !itemModelLocation.equals(itemLocation)) {
                baseModel = assets.model(Key.key(itemLocation.getNamespace(), "item/" + itemLocation.getPath()));
            }
            if (baseModel == null && item instanceof BlockItem) {
                // Modern block items frequently ship no legacy item/ model - their
                // inventory look comes straight from the block model.
                Identifier modelLocation = blockLocation == null ? itemLocation : blockLocation;
                baseModel = assets.model(Key.key(modelLocation.getNamespace(), "block/" + modelLocation.getPath()));
            }
            if (baseModel == null) {
                if (!tryFallbackTexture(context, assets, bedrockPack, itemLocation, "has no item model", resolvedAsset)) {
                    context.logger().warn("Item {} has no item model or texture, skipping", itemLocation);
                    context.report().outcome("item-unresolved", itemLocation.toString());
                    reportReason(context, itemLocation, "missing-model");
                }
                continue;
            }

            Model model = new ModelStitcher(context.modelProvider(), baseModel, packLogListener).stitch();
            if (model == null) {
                if (!tryFallbackTexture(context, assets, bedrockPack, itemLocation, "model could not be stitched", resolvedAsset)) {
                    context.logger().warn("Item {} model could not be stitched and has no texture fallback, skipping", itemLocation);
                    context.report().outcome("item-model-stitch-failed", itemLocation.toString());
                    reportReason(context, itemLocation, resolvedAsset.reasonCode().equals("layered-texture") ? "unresolved-parent" : resolvedAsset.reasonCode());
                }
                continue;
            }

            List<ModelTexture> layers = model.textures().layers();
            List<Key> textureLayers = resolvedAsset.textureLayers().isEmpty() ? modelLayers(layers) : resolvedAsset.textureLayers();
            if (textureLayers.isEmpty()) {
                if (!tryFallbackTexture(context, assets, bedrockPack, itemLocation, "has no layer texture", resolvedAsset)) {
                    // Block items can intentionally use block geometry, but they
                    // still need an outcome so coverage reports remain complete.
                    if (!(item instanceof BlockItem)) {
                        context.logger().warn("Item {} has no layer0 texture, skipping", itemLocation);
                    }
                    context.report().outcome(item instanceof BlockItem ? "item-block-model-no-layer" : "item-model-no-layer", itemLocation.toString());
                    reportReason(context, itemLocation, "missing-texture");
                }
                continue;
            }

            ItemTexture texture = writeItemTexture(context, bedrockPack, itemLocation, textureLayers);
            if (texture == null) {
                if (!tryFallbackTexture(context, assets, bedrockPack, itemLocation, itemModelLocation,
                        "model texture " + textureLayers.getFirst() + " is absent from Bedrock output", resolvedAsset)) {
                    context.logger().warn("Item {} model texture {} is absent from Bedrock output, skipping", itemLocation, textureLayers.getFirst());
                    context.report().outcome("item-missing-output-texture", itemLocation.toString());
                    reportReason(context, itemLocation, "missing-texture");
                }
                continue;
            }
            bedrockPack.addItemTexture(itemLocation.toString(), texture.path());
            String outcome = textureLayers.size() > 1 ? "item-layered-model" : "item-direct-model";
            context.report().outcome(outcome, itemLocation.toString());
            context.report().resolution(outcome, itemLocation.toString(), textureLayers.toString());
            reportReason(context, itemLocation, resolvedAsset.reasonCode());
            reportSourceRecovery(context, itemLocation, texture);
        }
    }

    private static boolean tryFallbackTexture(PackPostProcessContext<?> context, ResourcePack assets, BedrockResourcePack pack,
                                              Identifier item, String reason, ItemAssetResolver.ResolvedItemAsset resolvedAsset) {
        return tryFallbackTexture(context, assets, pack, item, item, reason, resolvedAsset);
    }

    private static boolean tryFallbackTexture(PackPostProcessContext<?> context, ResourcePack assets, BedrockResourcePack pack,
                                              Identifier item, Identifier itemModel, String reason, ItemAssetResolver.ResolvedItemAsset resolvedAsset) {
        List<TextureFallback> fallbacks = findFallbackTextures(context, assets, item, itemModel, resolvedAsset);
        if (fallbacks.isEmpty()) return false;
        for (TextureFallback fallback : fallbacks) {
            ItemTexture texture = writeItemTexture(context, pack, fallback.key());
            if (texture == null) continue;
            pack.addItemTexture(item.toString(), texture.path());
            context.logger().warn("Item {} {}; using texture fallback", item, reason);
            context.report().fallback("item-texture");
            String kind = fallback.rawSource() ? "item-source-texture-fallback" : fallback.rawRenderer() ? "item-raw-renderer-fallback" : "item-texture-fallback";
            context.report().outcome(kind, item.toString());
            context.report().resolution(kind, item.toString(), texture.source() != null ? texture.source() : fallback.key().toString());
            reportReason(context, item, fallback.reasonCode());
            reportSourceRecovery(context, item, texture);
            return true;
        }
        context.logger().warn("Item {} fallback textures are absent from Bedrock output, skipping", item);
        context.report().outcome("item-missing-output-texture", item.toString());
        reportReason(context, item, resolvedAsset.reasonCode());
        return true;
    }

    private static void reportSourceRecovery(PackPostProcessContext<?> context, Identifier item, ItemTexture texture) {
        if (texture.source() == null) return;
        context.report().fallback("item-source-texture");
        context.report().outcome("item-source-texture-recovery", item.toString());
        context.report().resolution("item-source-texture-recovery", item.toString(), texture.source());
    }

    private static List<TextureFallback> findFallbackTextures(PackPostProcessContext<?> context, ResourcePack assets, Identifier itemLocation,
                                                               Identifier itemModel, ItemAssetResolver.ResolvedItemAsset resolvedAsset) {
        Map<Key, TextureFallback> candidates = new LinkedHashMap<>();
        for (Key layer : resolvedAsset.textureLayers()) {
            candidates.putIfAbsent(layer, new TextureFallback(layer, false, false, resolvedAsset.reasonCode()));
        }
        for (Identifier location : itemModel.equals(itemLocation) ? List.of(itemLocation) : List.of(itemLocation, itemModel)) {
            Key texture = findNamedTexture(assets, location);
            if (texture != null) candidates.putIfAbsent(texture, new TextureFallback(texture, false, false, resolvedAsset.reasonCode()));

            texture = findRawTexture(context, assets, location);
            if (texture != null) candidates.putIfAbsent(texture, new TextureFallback(texture, true, false, resolvedAsset.reasonCode()));
        }

        for (String directory : List.of("item", "block")) {
            for (Identifier location : itemModel.equals(itemLocation) ? List.of(itemLocation) : List.of(itemLocation, itemModel)) {
                Key texture = Key.key(location.getNamespace(), directory + "/" + location.getPath());
                if (sourceTexture(context.mod(), texture) != null) {
                    candidates.putIfAbsent(texture, new TextureFallback(texture, false, true, resolvedAsset.reasonCode()));
                }
            }
        }
        return List.copyOf(candidates.values());
    }

    private static Key findNamedTexture(ResourcePack assets, Identifier itemLocation) {
        Key itemTexture = Key.key(itemLocation.getNamespace(), "item/" + itemLocation.getPath());
        Key blockTexture = Key.key(itemLocation.getNamespace(), "block/" + itemLocation.getPath());
        Key contains = null;
        for (var texture : assets.textures()) {
            if (texture.key().equals(itemTexture) || texture.key().equals(blockTexture)) {
                return texture.key();
            }
            String value = texture.key().value();
            if (contains == null && texture.key().namespace().equals(itemLocation.getNamespace())
                    && (value.endsWith("/" + itemLocation.getPath()) || value.contains(itemLocation.getPath()))) {
                contains = texture.key();
            }
        }
        return contains;
    }

    private static Key findRawTexture(PackPostProcessContext<?> context, ResourcePack assets, Identifier item) {
        var file = context.mod().resolveFile("assets/" + item.getNamespace() + "/items/" + item.getPath() + ".json");
        if (file == null) return null;
        try {
            Matcher matcher = RAW_TEXTURE.matcher(IOUtil.readString(file, StandardCharsets.UTF_8, 8 * 1024 * 1024));
            while (matcher.find()) {
                String value = matcher.group();
                Key key = value.indexOf(':') >= 0 ? Key.key(value) : Key.key(item.getNamespace(), value);
                for (var texture : assets.textures()) if (texture.key().equals(key)) return key;
            }
        } catch (Exception exception) {
            LOGGER.debug("Failed to scan raw item model for texture references: {}", file, exception);
            // A malformed raw item file is reported by Minecraft; it must not stop pack conversion.
        }
        return null;
    }

    /** Ensures the texture reference added to Geyser points at a real pack file. */
    private static ItemTexture writeItemTexture(PackPostProcessContext<?> context, BedrockResourcePack pack, Identifier item, List<Key> layers) {
        ItemTexture base = writeItemTexture(context, pack, layers.getFirst());
        if (base == null || layers.size() == 1) return base;

        List<BufferedImage> images = new ArrayList<>();
        for (Key layer : layers) {
            ItemTexture written = writeItemTexture(context, pack, layer);
            if (written == null) return base;
            Path imagePath = imagePath(context, pack, layer);
            if (imagePath == null) return base;
            try {
                BufferedImage image = ImageIO.read(imagePath.toFile());
                if (image == null) return base;
                images.add(image);
            } catch (Exception exception) {
                LOGGER.warn("Failed to read item texture layer {} for item {}", layer, item, exception);
                return base;
            }
        }
        int width = images.stream().mapToInt(BufferedImage::getWidth).max().orElse(0);
        int height = images.stream().mapToInt(BufferedImage::getHeight).max().orElse(0);
        if (width == 0 || height == 0) return base;

        String safeName = item.getNamespace() + "_" + item.getPath().replace('/', '_');
        String outputFile = "textures/items/" + context.mod().id() + "/_hydraulic/" + safeName + ".png";
        try {
            Path output = pack.directory().resolve(outputFile);
            Files.createDirectories(output.getParent());
            BufferedImage combined = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = combined.createGraphics();
            for (BufferedImage image : images) graphics.drawImage(image, 0, 0, null);
            graphics.dispose();
            ImageIO.write(combined, "PNG", output.toFile());
            return new ItemTexture(outputFile.substring(0, outputFile.lastIndexOf('.')), null);
        } catch (Exception exception) {
            LOGGER.warn("Failed to write combined item texture {} for item {}", outputFile, item, exception);
            return base;
        }
    }

    private static ItemTexture writeItemTexture(PackPostProcessContext<?> context, BedrockResourcePack pack, Key key) {
        String outputFile = getOutputFromModel(context, key);
        Path output = pack.directory().resolve(outputFile);
        if (Files.isRegularFile(output) || Files.isRegularFile(withExtension(output, ".tga"))) {
            return new ItemTexture(outputFile.substring(0, outputFile.lastIndexOf('.')), null);
        }

        Path source = sourceTexture(context.mod(), key);
        if (source == null) return null;
        try {
            Path target = withExtension(output, extension(source));
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            return new ItemTexture(outputFile.substring(0, outputFile.lastIndexOf('.')), "assets/" + key.namespace() + "/textures/" + key.value());
        } catch (Exception exception) {
            LOGGER.warn("Failed to copy source texture {} for item key {}", source, key, exception);
            return null;
        }
    }

    private static Path imagePath(PackPostProcessContext<?> context, BedrockResourcePack pack, Key key) {
        Path output = pack.directory().resolve(getOutputFromModel(context, key));
        if (Files.isRegularFile(output)) return output;
        Path tga = withExtension(output, ".tga");
        return Files.isRegularFile(tga) ? tga : null;
    }

    private static Path sourceTexture(ModInfo mod, Key key) {
        if (key.namespace().equals(Key.MINECRAFT_NAMESPACE)) return null;
        String source = "assets/" + key.namespace() + "/textures/" + key.value();
        Path png = mod.resolveFile(source + ".png");
        return png != null ? png : mod.resolveFile(source + ".tga");
    }

    private static Path withExtension(Path file, String extension) {
        String name = file.getFileName().toString();
        return file.resolveSibling(name.substring(0, name.lastIndexOf('.')) + extension);
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        return name.substring(name.lastIndexOf('.'));
    }

    private static List<Key> modelLayers(List<ModelTexture> layers) {
        if (layers == null) return List.of();
        return layers.stream().map(ModelTexture::key).filter(java.util.Objects::nonNull).toList();
    }

    private static Identifier itemModelLocation(Item item, Identifier fallback) {
        Identifier itemModel = item.components().get(DataComponents.ITEM_MODEL);
        return itemModel == null ? fallback : itemModel;
    }

    private static void collectRuntimeVariants(PackPostProcessContext<?> context, BedrockResourcePack pack, Identifier item,
                                               ItemModel model, List<MinecraftPredicate<? super ItemPredicateContext>> predicates,
                                               List<RuntimeVariant> variants, Set<ItemModel> visited) {
        if (model == null || !visited.add(model)) return;
        try {
            if (model instanceof ReferenceItemModel reference) {
                Model source = context.javaResourcePack().model(reference.model());
                if (source == null) return;
                Model stitched = new ModelStitcher(context.modelProvider(), source, new PackLogListener(context.logger())).stitch();
                if (stitched == null) return;
                List<Key> layers = modelLayers(stitched.textures().layers());
                if (layers.isEmpty()) return;
                ItemTexture texture = writeItemTexture(context, pack, item, layers);
                if (texture == null) return;
                String icon = item + "_runtime_" + variants.size();
                pack.addItemTexture(icon, texture.path());
                variants.add(new RuntimeVariant(icon, List.copyOf(predicates), variants.size() + 1));
                return;
            }
            if (model instanceof ConditionItemModel condition) {
                MinecraftPredicate<? super ItemPredicateContext> predicate = conditionPredicate(condition.condition());
                if (predicate == null) {
                    context.report().resolution("item-predicate-unsupported", item.toString(), propertyName(condition.condition()));
                    return;
                }
                collectRuntimeVariants(context, pack, item, condition.onTrue(), append(predicates, predicate), variants, visited);
                collectRuntimeVariants(context, pack, item, condition.onFalse(), append(predicates, predicate.negate()), variants, visited);
                return;
            }
            if (model instanceof RangeDispatchItemModel range) {
                for (RangeDispatchItemModel.Entry entry : range.entries()) {
                    MinecraftPredicate<? super ItemPredicateContext> predicate = rangePredicate(range.property(), range.scale(), entry.threshold());
                    if (predicate != null) collectRuntimeVariants(context, pack, item, entry.model(), append(predicates, predicate), variants, visited);
                    else context.report().resolution("item-predicate-unsupported", item.toString(), propertyName(range.property()));
                }
                return;
            }
            if (model instanceof SelectItemModel select) {
                for (SelectItemModel.Case itemCase : select.cases()) {
                    for (com.google.gson.JsonElement value : itemCase.when()) {
                        MinecraftPredicate<? super ItemPredicateContext> predicate = selectPredicate(select.property(), value);
                        if (predicate != null) collectRuntimeVariants(context, pack, item, itemCase.model(), append(predicates, predicate), variants, visited);
                        else context.report().resolution("item-predicate-unsupported", item.toString(), propertyName(select.property()));
                    }
                }
            }
        } finally {
            visited.remove(model);
        }
    }

    private static String propertyName(Object property) {
        if (property instanceof net.kyori.adventure.key.Keyed keyed) return keyed.key().asString();
        return property.getClass().getSimpleName();
    }

    private static List<MinecraftPredicate<? super ItemPredicateContext>> append(
            List<MinecraftPredicate<? super ItemPredicateContext>> predicates,
            MinecraftPredicate<? super ItemPredicateContext> predicate) {
        List<MinecraftPredicate<? super ItemPredicateContext>> result = new ArrayList<>(predicates);
        result.add(predicate);
        return result;
    }

    static MinecraftPredicate<? super ItemPredicateContext> conditionPredicate(ItemBooleanProperty property) {
        if (property instanceof HasComponentItemBooleanProperty component) {
            return ItemConditionPredicate.hasComponent(geyserIdentifier(component.component()));
        }
        if (property instanceof CustomModelDataItemBooleanProperty custom) return ItemConditionPredicate.customModelData(custom.index());
        if (!(property instanceof NoFieldItemBooleanProperty named)) return null;
        String key = named.key().value();
        return switch (key) {
            case "broken" -> ItemConditionPredicate.BROKEN;
            case "damaged" -> ItemConditionPredicate.DAMAGED;
            case "fishing_rod_cast" -> ItemConditionPredicate.FISHING_ROD_CAST;
            default -> null;
        };
    }

    static boolean supportsCondition(ItemBooleanProperty property) {
        if (property instanceof HasComponentItemBooleanProperty || property instanceof CustomModelDataItemBooleanProperty) return true;
        return property instanceof NoFieldItemBooleanProperty named
                && Set.of("broken", "damaged", "fishing_rod_cast").contains(named.key().value());
    }

    static MinecraftPredicate<? super ItemPredicateContext> rangePredicate(ItemNumericProperty property, float scale, float threshold) {
        double value = threshold / Math.max(scale, Float.MIN_NORMAL);
        if (property instanceof DamageItemNumericProperty damage) {
            return damage.normalize() ? ItemRangeDispatchPredicate.normalizedDamage(value) : ItemRangeDispatchPredicate.damage((int) value);
        }
        if (property instanceof CountItemNumericProperty count) {
            return count.normalize() ? ItemRangeDispatchPredicate.normalizedCount(value) : ItemRangeDispatchPredicate.count((int) value);
        }
        if (property instanceof CustomModelDataItemNumericProperty custom) {
            return ItemRangeDispatchPredicate.customModelData(custom.index(), (float) value);
        }
        if (property instanceof NoFieldItemNumericProperty named && named.key().value().equals("bundle/fullness")) {
            return ItemRangeDispatchPredicate.bundleFullness(value);
        }
        return null;
    }

    static boolean supportsRange(ItemNumericProperty property) {
        return property instanceof DamageItemNumericProperty || property instanceof CountItemNumericProperty
                || property instanceof CustomModelDataItemNumericProperty
                || property instanceof NoFieldItemNumericProperty named && named.key().value().equals("bundle/fullness");
    }

    static MinecraftPredicate<? super ItemPredicateContext> selectPredicate(ItemStringProperty property, com.google.gson.JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return null;
        String selected = value.getAsString();
        if (property instanceof CustomModelDataItemStringProperty custom) {
            return ItemMatchPredicate.customModelData(custom.index(), selected);
        }
        if (!(property instanceof NoFieldItemStringProperty named)) return null;
        return switch (named.key().value()) {
            case "charge_type" -> switch (selected.substring(selected.lastIndexOf(':') + 1)) {
                case "arrow" -> ItemMatchPredicate.chargeType(ChargedProjectile.ChargeType.ARROW);
                case "rocket" -> ItemMatchPredicate.chargeType(ChargedProjectile.ChargeType.ROCKET);
                default -> null;
            };
            case "trim_material" -> ItemMatchPredicate.trimMaterial(geyserIdentifier(selected));
            default -> null;
        };
    }

    static boolean supportsSelect(ItemStringProperty property) {
        return property instanceof CustomModelDataItemStringProperty
                || property instanceof NoFieldItemStringProperty named
                && Set.of("charge_type", "trim_material").contains(named.key().value());
    }

    private static org.geysermc.geyser.api.util.Identifier geyserIdentifier(String value) {
        return value.indexOf(':') >= 0 ? org.geysermc.geyser.api.util.Identifier.of(value)
                : org.geysermc.geyser.api.util.Identifier.of("minecraft", value);
    }

    private static void reportReason(PackPostProcessContext<?> context, Identifier item, String reason) {
        if (!"layered-texture".equals(reason)) context.report().resolution("item-reason", item.toString(), reason);
    }

    private record TextureFallback(Key key, boolean rawRenderer, boolean rawSource, String reasonCode) {
    }

    private record ItemTexture(String path, String source) {
    }

    @Override
    public boolean test(@NotNull PackPostProcessContext<ItemPackModule> context) {
        return !context.registryValues(BuiltInRegistries.ITEM).isEmpty();
    }

    private void onDefineCustomItems(PackEventContext<GeyserDefineCustomItemsEvent, ItemPackModule> context) {
        GeyserDefineCustomItemsEvent event = context.event();
        List<Item> items = context.registryValues(BuiltInRegistries.ITEM);

        DefaultedRegistry<Item> registry = BuiltInRegistries.ITEM;
        for (Item item : items) {
            Identifier itemLocation = registry.getKey(item);

            try {
                NonVanillaCustomItemDefinition.Builder customItemDefinition = NonVanillaCustomItemDefinition.builder(
                        org.geysermc.geyser.api.util.Identifier.of(itemLocation.toString()),
                        org.geysermc.geyser.api.util.Identifier.of(itemLocation.toString()),
                        registry.getId(item)
                )
                        .displayName("%" + item.getDescriptionId());

                CustomItemBedrockOptions.Builder customItemOptions = CustomItemBedrockOptions.builder()
                        .allowOffhand(true);

                // Allow minecraft namespace texture to be used (remapped as hydraulic)
                if (itemBuiltinTexture.containsKey(itemLocation.toString())) {
                    customItemOptions.icon(itemBuiltinTexture.get(itemLocation.toString()));
                }

                // Add the icon if it should have an icon
                boolean is2d = itemsWith2dIcon.contains(itemLocation);
                if (is2d) {
                    customItemOptions.icon(itemLocation.toString());
                }

                // Make it handheld if need be
                if (handheldItems.contains(itemLocation)) {
                    customItemOptions.displayHandheld(true);
                }

                // Set the creative mappings
                CreativeMappings.setup(item, customItemOptions);

                // Set all bedrock components using what java components we have
                ComponentConverter.setGeyserComponents(
                        item.components(),
                        customItemDefinition,
                        customItemOptions
                );

                // Set the needed component for bows to work correctly
                if (item instanceof BowItem) {
                    customItemDefinition.component(
                            GeyserItemDataComponents.CHARGEABLE,
                            GeyserChargeable.builder()
                                    .maxDrawDuration(1f)
                                    .chargeOnDraw(false)
                    );

                    // Include the default icon, this won't change in the hotbar when used but this works the best for now
                    customItemOptions.icon(itemLocation.toString());
                }

                // Set the needed component for crossbows to work correctly
                if (item instanceof CrossbowItem) {
                    customItemDefinition.component(
                            GeyserItemDataComponents.CHARGEABLE,
                            GeyserChargeable.builder()
                                    .maxDrawDuration(0f)
                                    .chargeOnDraw(true)
                    );

                    // Include the default icon, this won't change in the hotbar when used but this works the best for now
                    customItemOptions.icon(itemLocation.toString());
                }

                if (item instanceof BlockItem blockItem) {
                    // Set the block_placer component to the correct block
                    // This fixes animations sometimes not showing
                    Block block = blockItem.getBlock();

                    customItemDefinition.component(
                            GeyserItemDataComponents.BLOCK_PLACER,
                            GeyserBlockPlacer.of(HydraulicKey.of(BuiltInRegistries.BLOCK.getKey(block)), !is2d)
                    );

                    CreativeMappings.setupBlock(block, customItemOptions);
                }

                customItemDefinition.bedrockOptions(customItemOptions);

                event.register(customItemDefinition.build());
                for (RuntimeVariant variant : runtimeVariants.getOrDefault(itemLocation.toString(), List.of())) {
                    org.geysermc.geyser.api.util.Identifier variantId = org.geysermc.geyser.api.util.Identifier.of(
                            itemLocation.getNamespace(), itemLocation.getPath() + "_hydraulic_runtime_" + variant.priority());
                    CustomItemDefinition.Builder definition = CustomItemDefinition.builder(variantId, variantId)
                            .displayName("%" + item.getDescriptionId())
                            .priority(variant.priority())
                            .predicateStrategy(PredicateStrategy.AND)
                            .bedrockOptions(CustomItemBedrockOptions.builder().icon(variant.icon()).allowOffhand(true));
                    variant.predicates().forEach(definition::predicate);
                    event.register(org.geysermc.geyser.api.util.Identifier.of(itemLocation.toString()), definition.build());
                }
            } catch (Exception e) {
                context.logger().error("Unable to register {}:", itemLocation, e);
            }
        }
    }

    private record RuntimeVariant(String icon,
                                  List<MinecraftPredicate<? super ItemPredicateContext>> predicates,
                                  int priority) {
    }
}
