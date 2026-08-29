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

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

            Model baseModel = assets.model(Key.key(itemLocation.getNamespace(), "item/" + itemLocation.getPath()));
            if (baseModel == null && item instanceof BlockItem) {
                // Modern block items frequently ship no legacy item/ model - their
                // inventory look comes straight from the block model.
                baseModel = assets.model(Key.key(itemLocation.getNamespace(), "block/" + itemLocation.getPath()));
            }
            if (baseModel == null) {
                Key fallbackTexture = findFallbackTexture(assets, itemLocation);
                if (fallbackTexture == null) fallbackTexture = findRawTexture(context, assets, itemLocation);
                if (fallbackTexture == null) {
                    context.logger().warn("Item {} has no item model or texture, skipping", itemLocation);
                    context.report().outcome("item-unresolved");
                    continue;
                }
                bedrockPack.addItemTexture(itemLocation.toString(), getOutputFromModel(context, fallbackTexture).replace(".png", ""));
                context.logger().warn("Item {} has no item model; using texture fallback", itemLocation);
                context.report().fallback("item-texture");
                context.report().outcome("item-texture-fallback");
                context.report().outcome("item-raw-renderer-fallback");
                continue;
            }

            Model model = new ModelStitcher(context.modelProvider(), baseModel, packLogListener).stitch();

            List<ModelTexture> layers = model.textures().layers();
            if (layers == null || layers.isEmpty()) {
                // Don't warn if a block as they can use the block model
                if (!(item instanceof BlockItem)) {
                    context.logger().warn("Item {} has no layer0 texture, skipping", itemLocation);
                }

                continue;
            }

            ModelTexture layer0 = layers.getFirst();
            String outputLoc = getOutputFromModel(context, layer0.key()); // TODO: sort this out, layer0.key() can be null, but the method we use doesn't want that
            bedrockPack.addItemTexture(itemLocation.toString(), outputLoc.replace(".png", ""));
            context.report().outcome("item-direct-model");
        }
    }

    private static Key findFallbackTexture(ResourcePack assets, Identifier itemLocation) {
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
