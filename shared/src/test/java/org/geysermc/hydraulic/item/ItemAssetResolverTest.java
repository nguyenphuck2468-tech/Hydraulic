package org.geysermc.hydraulic.item;

import net.minecraft.resources.Identifier;
import org.geysermc.hydraulic.platform.mod.ModInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ItemAssetResolverTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesInheritedLayerTexturesInOrder() throws IOException {
        write("assets/example/models/item/base.json", "{\"textures\":{\"layer0\":\"example:item/base\"}}");
        write("assets/example/models/item/child.json", "{\"parent\":\"example:item/base\",\"textures\":{\"layer1\":\"example:item/overlay\"}}");
        write("assets/example/items/gadget.json", "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"example:item/child\"}}");

        ItemAssetResolver.ResolvedItemAsset resolved = ItemAssetResolver.resolve(mod(), Identifier.fromNamespaceAndPath("example", "gadget"));

        assertEquals(List.of("example:item/base", "example:item/overlay"), resolved.textureLayers().stream().map(Object::toString).toList());
        assertEquals("layered-texture", resolved.reasonCode());
    }

    @Test
    void reportsSpecialRendererAndTintWhileStillFindingItsIconLayer() throws IOException {
        write("assets/example/models/item/icon.json", "{\"textures\":{\"layer0\":\"example:item/icon\"}}");
        write("assets/example/items/special.json", "{\"model\":{\"type\":\"minecraft:special\",\"model\":\"example:item/icon\"},\"tints\":[{\"type\":\"minecraft:constant\"}]} ");

        ItemAssetResolver.ResolvedItemAsset resolved = ItemAssetResolver.resolve(mod(), Identifier.fromNamespaceAndPath("example", "special"));

        assertEquals(List.of("example:item/icon"), resolved.textureLayers().stream().map(Object::toString).toList());
        assertEquals("special-renderer", resolved.reasonCode());
    }

    @Test
    void reportsUnresolvedParentWithoutDiscardingChildTexture() throws IOException {
        write("assets/example/models/item/broken.json", "{\"parent\":\"example:item/missing\",\"textures\":{\"layer0\":\"example:item/icon\"}}");

        ItemAssetResolver.ResolvedItemAsset resolved = ItemAssetResolver.resolve(mod(), Identifier.fromNamespaceAndPath("example", "broken"));

        assertEquals(List.of("example:item/icon"), resolved.textureLayers().stream().map(Object::toString).toList());
        assertEquals("unresolved-parent", resolved.reasonCode());
    }

    @Test
    void followsTheRegisteredItemModelAndBlockstateModel() throws IOException {
        write("assets/example/items/registered_model.json", "{\"model\":\"example:item/other\"}");
        write("assets/example/models/item/other.json", "{\"textures\":{\"layer0\":\"example:item/registered\"}}");
        write("assets/example/blockstates/display_block.json", "{\"variants\":{\"\":{\"model\":\"example:block/display\"}}}");
        write("assets/example/models/block/display.json", "{\"textures\":{\"all\":\"example:block/display\"}}");

        ItemAssetResolver.ResolvedItemAsset fromItemModel = ItemAssetResolver.resolve(mod(),
                Identifier.fromNamespaceAndPath("example", "registry_name"),
                Identifier.fromNamespaceAndPath("example", "registered_model"), null);
        ItemAssetResolver.ResolvedItemAsset fromBlockState = ItemAssetResolver.resolve(mod(),
                Identifier.fromNamespaceAndPath("example", "block_item"),
                Identifier.fromNamespaceAndPath("example", "block_item"),
                Identifier.fromNamespaceAndPath("example", "display_block"));

        assertEquals(List.of("example:item/registered"), fromItemModel.textureLayers().stream().map(Object::toString).toList());
        assertEquals(List.of("example:block/display"), fromBlockState.textureLayers().stream().map(Object::toString).toList());
    }

    @Test
    void retainsEverySelectAndRangeCandidateForDiagnostics() throws IOException {
        write("assets/example/items/tool.json", """
                {"model":{"type":"minecraft:select","cases":[
                  {"when":"a","model":{"type":"minecraft:model","model":"example:item/a"}},
                  {"when":"b","model":{"type":"minecraft:range_dispatch","entries":[
                    {"threshold":0.5,"model":{"type":"minecraft:model","model":"example:item/b"}}
                  ],"fallback":{"type":"minecraft:model","model":"example:item/c"}}}
                ]}}
                """);
        write("assets/example/models/item/a.json", "{\"textures\":{\"layer0\":\"example:item/a\"}}");
        write("assets/example/models/item/b.json", "{\"textures\":{\"layer0\":\"example:item/b\"}}");
        write("assets/example/models/item/c.json", "{\"textures\":{\"layer0\":\"example:item/c\"}}");

        ItemAssetResolver.ResolvedItemAsset resolved = ItemAssetResolver.resolve(mod(), Identifier.fromNamespaceAndPath("example", "tool"));

        assertEquals(List.of("select", "range_dispatch"), resolved.dynamicModelKinds());
        assertEquals(List.of("example:item/a", "example:item/b", "example:item/c"),
                resolved.candidateTextures().stream().map(Object::toString).toList());
        assertEquals("dynamic-item-model", resolved.reasonCode());
    }

    private ModInfo mod() {
        return new ModInfo("example", "example", "Example", "1", null, List.of(temporaryDirectory));
    }

    private void write(String resource, String content) throws IOException {
        Path file = temporaryDirectory.resolve(resource);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
