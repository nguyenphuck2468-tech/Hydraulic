package org.geysermc.hydraulic.item;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.key.Key;
import net.minecraft.resources.Identifier;
import org.geysermc.hydraulic.platform.mod.ModInfo;

import java.nio.file.Files;
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
    private ItemAssetResolver() {
    }

    static ResolvedItemAsset resolve(ModInfo mod, Identifier item) {
        State state = new State(mod, item.getNamespace());
        Path definition = mod.resolveFile("assets/" + item.getNamespace() + "/items/" + item.getPath() + ".json");
        if (definition != null) {
            scanItemDefinition(read(definition), state);
        }
        // Legacy models remain the reliable baseline when a 26.2 item
        // definition is absent or only selects another model at runtime.
        scanModel(Key.key(item.getNamespace(), "item/" + item.getPath()), state, false);

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
        String reason = state.specialRenderer ? "special-renderer"
                : state.customTint ? "custom-tint"
                : state.unresolvedParent ? "unresolved-parent"
                : layers.isEmpty() ? "missing-texture" : "layered-texture";
        return new ResolvedItemAsset(List.copyOf(layers), reason);
    }

    private static void scanItemDefinition(JsonElement element, State state) {
        if (element == null || !element.isJsonObject()) return;
        JsonObject object = element.getAsJsonObject();
        String type = string(object, "type");
        if (type != null && type.toLowerCase(java.util.Locale.ROOT).contains("special")) state.specialRenderer = true;
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
                    state.textures.putIfAbsent(entry.getKey(), entry.getValue().getAsString());
                }
            }
        }
        String parentKey = string(model, "parent");
        if (parentKey != null) scanModel(key(parentKey, key.namespace()), state, true);
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
            JsonElement parsed = JsonParser.parseString(Files.readString(path));
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

    record ResolvedItemAsset(List<Key> textureLayers, String reasonCode) {
    }

    private static final class State {
        private final ModInfo mod;
        private final String namespace;
        private final Map<String, String> textures = new LinkedHashMap<>();
        private final Set<String> models = new HashSet<>();
        private boolean specialRenderer;
        private boolean customTint;
        private boolean unresolvedParent;

        private State(ModInfo mod, String namespace) {
            this.mod = mod;
            this.namespace = namespace;
        }
    }
}
