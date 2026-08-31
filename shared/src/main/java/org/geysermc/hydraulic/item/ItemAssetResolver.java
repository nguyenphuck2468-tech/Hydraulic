package org.geysermc.hydraulic.item;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.key.Key;
import net.minecraft.resources.Identifier;
import org.geysermc.hydraulic.platform.mod.ModInfo;
import org.geysermc.hydraulic.util.IOUtil;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves ordinary JSON item models without assuming a mod or renderer framework. */
final class ItemAssetResolver {
    private static final int MAX_JSON_BYTES = 8 * 1024 * 1024;
    private ItemAssetResolver() {
    }

    static ResolvedItemAsset resolve(ModInfo mod, Identifier item) {
        return resolve(mod, item, item, null);
    }

    static ResolvedItemAsset resolve(ModInfo mod, Identifier item, Identifier itemModel, Identifier block) {
        State state = new State(mod, itemModel.getNamespace());
        Path definition = mod.resolveFile("assets/" + itemModel.getNamespace() + "/items/" + itemModel.getPath() + ".json");
        if (definition != null) {
            scanItemDefinition(read(definition), state);
        }
        // Legacy models remain the reliable baseline when a 26.2 item
        // definition is absent or only selects another model at runtime.
        scanModel(Key.key(itemModel.getNamespace(), "item/" + itemModel.getPath()), state, false);
        if (!itemModel.equals(item)) {
            scanModel(Key.key(item.getNamespace(), "item/" + item.getPath()), state, false);
        }
        if (block != null) {
            scanModel(Key.key(block.getNamespace(), "block/" + block.getPath()), state, false);
            scanBlockState(read(mod.resolveFile("assets/" + block.getNamespace() + "/blockstates/" + block.getPath() + ".json")), state, block.getNamespace());
        }

        List<Key> layers = new ArrayList<>();
        state.textures.entrySet().stream()
                .filter(entry -> entry.getKey().matches("layer\\d+"))
                .sorted(Comparator.comparingInt(entry -> Integer.parseInt(entry.getKey().substring("layer".length()))))
                .map(entry -> resolveTexture(entry.getValue(), state, new HashSet<>()))
                .filter(java.util.Objects::nonNull)
                .forEach(layers::add);
        if (layers.isEmpty()) {
            Key particle = resolveTexture(state.textures.get("particle"), state, new HashSet<>());
            if (particle != null) layers.add(particle);
        }
        if (layers.isEmpty()) {
            state.textures.values().stream()
                    .map(value -> resolveTexture(value, state, new HashSet<>()))
                    .filter(java.util.Objects::nonNull)
                    .findFirst().ifPresent(layers::add);
        }
        List<Key> candidates = state.candidateTextures.stream()
                .map(value -> resolveTexture(value, state, new HashSet<>()))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        String reason = state.specialRenderer ? "special-renderer"
                : state.customTint ? "custom-tint"
                : state.unresolvedParent ? "unresolved-parent"
                : !state.dynamicModelKinds.isEmpty() && candidates.size() > 1 ? "dynamic-item-model"
                : layers.isEmpty() ? "missing-texture" : "layered-texture";
        return new ResolvedItemAsset(List.copyOf(layers), reason, candidates, List.copyOf(state.dynamicModelKinds));
    }

    private static void scanItemDefinition(JsonElement element, State state) {
        if (element == null) return;
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) scanItemDefinition(child, state);
            return;
        }
        if (!element.isJsonObject()) return;
        JsonObject object = element.getAsJsonObject();
        String type = string(object, "type");
        if (type != null) {
            String normalizedType = type.toLowerCase(java.util.Locale.ROOT);
            if (normalizedType.contains("special")) state.specialRenderer = true;
            if (normalizedType.contains("range_dispatch")) state.dynamicModelKinds.add("range_dispatch");
            else if (normalizedType.contains("select")) state.dynamicModelKinds.add("select");
            else if (normalizedType.contains("condition")) state.dynamicModelKinds.add("condition");
            else if (normalizedType.contains("composite")) state.dynamicModelKinds.add("composite");
        }
        if (object.has("tints") || object.has("tint")) state.customTint = true;

        JsonElement model = object.get("model");
        if (model != null && model.isJsonPrimitive() && model.getAsJsonPrimitive().isString()) {
            scanModel(key(model.getAsString(), state.namespace), state, false);
        } else if (model != null) {
            scanItemDefinition(model, state);
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (!entry.getKey().equals("model")) scanItemDefinition(entry.getValue(), state);
        }
    }

    private static void scanModel(Key key, State state, boolean parent) {
        if (!state.models.add(key.asString())) return;
        Path file = state.mod.resolveFile("assets/" + key.namespace() + "/models/" + key.value() + ".json");
        JsonObject model = read(file);
        if (model == null) {
            if (parent && !key.namespace().equals(Key.MINECRAFT_NAMESPACE)) state.unresolvedParent = true;
            return;
        }
        JsonObject textures = object(model, "textures");
        if (textures != null) {
            for (Map.Entry<String, JsonElement> entry : textures.entrySet()) {
                if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isString()) {
                    String value = entry.getValue().getAsString();
                    state.textures.putIfAbsent(entry.getKey(), value);
                    state.candidateTextures.add(value);
                }
            }
        }
        String parentKey = string(model, "parent");
        if (parentKey != null) scanModel(key(parentKey, key.namespace()), state, true);
    }

    private static void scanBlockState(JsonElement element, State state, String namespace) {
        if (element == null) return;
        if (element.isJsonArray()) {
            for (JsonElement entry : element.getAsJsonArray()) scanBlockState(entry, state, namespace);
            return;
        }
        if (!element.isJsonObject()) return;
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            JsonElement value = entry.getValue();
            if (entry.getKey().equals("model") && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                scanModel(key(value.getAsString(), namespace), state, false);
            } else {
                scanBlockState(value, state, namespace);
            }
        }
    }

    private static Key resolveTexture(String value, State state, Set<String> resolving) {
        if (value == null) return null;
        if (value.startsWith("#")) {
            String variable = value.substring(1);
            if (!resolving.add(variable)) return null;
            return resolveTexture(state.textures.get(variable), state, resolving);
        }
        return key(value, state.namespace);
    }

    private static JsonObject read(Path path) {
        if (path == null) return null;
        try {
            JsonElement parsed = JsonParser.parseString(IOUtil.readString(path, java.nio.charset.StandardCharsets.UTF_8, MAX_JSON_BYTES));
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static JsonObject object(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static String string(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }

    private static Key key(String value, String namespace) {
        return value.indexOf(':') >= 0 ? Key.key(value) : Key.key(namespace, value);
    }

    record ResolvedItemAsset(List<Key> textureLayers, String reasonCode, List<Key> candidateTextures,
                             List<String> dynamicModelKinds) {
    }

    private static final class State {
        private final ModInfo mod;
        private final String namespace;
        private final Map<String, String> textures = new LinkedHashMap<>();
        private final Set<String> models = new HashSet<>();
        private final Set<String> candidateTextures = new java.util.LinkedHashSet<>();
        private final Set<String> dynamicModelKinds = new java.util.LinkedHashSet<>();
        private boolean specialRenderer;
        private boolean customTint;
        private boolean unresolvedParent;

        private State(ModInfo mod, String namespace) {
            this.mod = mod;
            this.namespace = namespace;
        }
    }
}
