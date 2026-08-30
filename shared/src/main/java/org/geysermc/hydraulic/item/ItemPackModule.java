package org.geysermc.hydraulic.item;

import com.google.auto.service.AutoService;
import com.google.common.collect.Lists;
import net.kyori.adventure.key.Key;
import net.minecraft.core.DefaultedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.*;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomItemsEvent;
import org.geysermc.geyser.api.item.custom.v2.CustomItemBedrockOptions;
import org.geysermc.geyser.api.item.custom.v2.NonVanillaCustomItemDefinition;
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
import org.geysermc.hydraulic.util.PackUtil;
import org.geysermc.pack.bedrock.resource.BedrockResourcePack;
import org.geysermc.pack.converter.type.model.ModelStitcher;
import org.jetbrains.annotations.NotNull;
import team.unnamed.creative.ResourcePack;
import team.unnamed.creative.item.*;
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
    private static final Pattern RAW_TEXTURE = Pattern.compile("(?:[a-z0-9_.-]+:)?(?:item|block|gui)/[a-z0-9_./-]+");
    private final List<Identifier> itemsWith2dIcon = new ArrayList<>();
    private final List<Identifier> handheldItems = new ArrayList<>();
    private final Map<String, String> itemBuiltinTexture = new HashMap<>();

    public ItemPackModule() {
        this.listenOn(GeyserDefineCustomItemsEvent.class, this::onDefineCustomItems);

        this.preProcess(this::preProcess);
        this.postProcess(this::postProcess);
    }

    private void handleModel(@NotNull PackPreProcessContext<ItemPackModule> context, ItemModel itemModel, Identifier itemLocation) {
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
        } else if (itemModel instanceof SelectItemModel selectModel) { // See if we can actually do select models here
            handleModel(context, selectModel.fallback(), itemLocation);
        } else if (itemModel instanceof CompositeItemModel compositeModel) {
            // A composite can contain a 2D icon and a separate handheld model.
            // Inspect every child so either capability is preserved instead of
            // silently classifying the item from whichever child happens first.
            for (ItemModel child : compositeModel.models()) {
                handleModel(context, child, itemLocation);
            }
        } else if (itemModel instanceof RangeDispatchItemModel rangeDispatchModel) {
            handleModel(context, rangeDispatchModel.fallback(), itemLocation);
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

            Model baseModel = context.modelProvider().model(Key.key(itemLocation.getNamespace(), "item/" + itemLocation.getPath()));
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
            ItemAssetResolver.ResolvedItemAsset resolvedAsset = ItemAssetResolver.resolve(context.mod(), itemLocation);

            Model baseModel = assets.model(Key.key(itemLocation.getNamespace(), "item/" + itemLocation.getPath()));
            if (baseModel == null && item instanceof BlockItem) {
                // Modern block items frequently ship no legacy item/ model - their
                // inventory look comes straight from the block model.
                baseModel = assets.model(Key.key(itemLocation.getNamespace(), "block/" + itemLocation.getPath()));
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
                context.logger().warn("Item {} model texture {} is absent from Bedrock output, skipping", itemLocation, textureLayers.getFirst());
                context.report().outcome("item-missing-output-texture", itemLocation.toString());
                reportReason(context, itemLocation, "missing-texture");
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
        TextureFallback fallback = findFallbackTexture(context, assets, item, resolvedAsset);
        if (fallback == null) return false;

        ItemTexture texture = writeItemTexture(context, pack, fallback.key());
        if (texture == null) {
            context.logger().warn("Item {} fallback texture {} is absent from Bedrock output, skipping", item, fallback.key());
            context.report().outcome("item-missing-output-texture", item.toString());
            return true;
        }
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

    private static void reportSourceRecovery(PackPostProcessContext<?> context, Identifier item, ItemTexture texture) {
        if (texture.source() == null) return;
        context.report().fallback("item-source-texture");
        context.report().outcome("item-source-texture-recovery", item.toString());
        context.report().resolution("item-source-texture-recovery", item.toString(), texture.source());
    }

    private static TextureFallback findFallbackTexture(PackPostProcessContext<?> context, ResourcePack assets, Identifier itemLocation,
                                                        ItemAssetResolver.ResolvedItemAsset resolvedAsset) {
        if (!resolvedAsset.textureLayers().isEmpty()) {
            return new TextureFallback(resolvedAsset.textureLayers().getFirst(), true, false, resolvedAsset.reasonCode());
        }
        Key texture = findNamedTexture(assets, itemLocation);
        if (texture != null) return new TextureFallback(texture, false, false, resolvedAsset.reasonCode());

        texture = findRawTexture(context, assets, itemLocation);
        if (texture != null) return new TextureFallback(texture, true, false, resolvedAsset.reasonCode());

        for (String directory : List.of("item", "block")) {
            texture = Key.key(itemLocation.getNamespace(), directory + "/" + itemLocation.getPath());
            if (sourceTexture(context.mod(), texture) != null) return new TextureFallback(texture, false, true, resolvedAsset.reasonCode());
        }
        return null;
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
            Matcher matcher = RAW_TEXTURE.matcher(Files.readString(file, StandardCharsets.UTF_8));
            while (matcher.find()) {
                String value = matcher.group();
                Key key = value.indexOf(':') >= 0 ? Key.key(value) : Key.key(item.getNamespace(), value);
                for (var texture : assets.textures()) if (texture.key().equals(key)) return key;
            }
        } catch (Exception ignored) {
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
            } catch (Exception ignored) {
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
        } catch (Exception ignored) {
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
        } catch (Exception ignored) {
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
            } catch (Exception e) {
                context.logger().error("Unable to register {}:", itemLocation, e);
            }
        }
    }
}
