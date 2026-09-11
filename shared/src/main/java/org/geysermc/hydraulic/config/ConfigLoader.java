package org.geysermc.hydraulic.config;

import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.interfaces.InterfaceDefaultOptions;
import org.spongepowered.configurate.transformation.ConfigurationTransformation;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.LoggerFactory;

public class ConfigLoader {
    private static final ConfigurationTransformation.Versioned TRANSFORMER = ConfigurationTransformation.versionedBuilder()
        .versionKey("config-version")
        .addVersion(1, ConfigurationTransformation.builder().build())
        .build();

    public static HydraulicConfig loadConfig(File configFile) throws ConfigurateException {
        return loadConfig(configFile, message -> LoggerFactory.getLogger(ConfigLoader.class).error("[Hydraulic][entity] {}", message));
    }

    public static HydraulicConfig loadConfig(File configFile, Consumer<String> errors) throws ConfigurateException {
        YamlConfigurationLoader loader = createLoader(configFile);

        CommentedConfigurationNode node = loader.load();
        boolean originallyEmpty = !configFile.exists() || node.isNull();

        int currentVersion = TRANSFORMER.version(node);
        TRANSFORMER.apply(node);
        int newVersion = TRANSFORMER.version(node);

        // Parse independently so one malformed entry cannot prevent other bindings or mods loading.
        // Preserve the original YAML when saving; invalid operator input must not disappear.
        CommentedConfigurationNode parsed = node.copy();
        var bindingsNode = node.node("entity-bindings");
        List<EntityBinding> bindings = new ArrayList<>();
        var firstIdentifiers = new HashMap<String, String>();
        var firstGeometries = new HashMap<String, String>();
        var conflicts = new HashSet<String>();
        if (!bindingsNode.virtual() && !bindingsNode.isList()) errors.accept("entity-bindings must be a YAML list");
        int index = 0;
        for (var entry : bindingsNode.childrenList()) {
            String location = "entity-bindings[" + index++ + "]";
            try {
                EntityBinding binding = entry.get(EntityBinding.class);
                if (binding == null) throw new IllegalArgumentException("Missing binding");
                binding.validate();
                String label = location + " " + binding.label();
                String previous = firstIdentifiers.putIfAbsent(binding.bedrockEntityIdentifier, label);
                if (previous != null) {
                    conflicts.add(binding.bedrockEntityIdentifier);
                    errors.accept("Duplicate bedrockEntityIdentifier: " + previous + " conflicts with " + label);
                }
                String geometryOwner = firstGeometries.putIfAbsent(binding.geometryIdentifier, binding.bedrockEntityIdentifier);
                if (geometryOwner != null && !geometryOwner.equals(binding.bedrockEntityIdentifier)) {
                    conflicts.add(geometryOwner);
                    conflicts.add(binding.bedrockEntityIdentifier);
                    errors.accept("Duplicate geometryIdentifier " + binding.geometryIdentifier + ": " + geometryOwner + " conflicts with " + label);
                }
                bindings.add(binding);
            } catch (Exception failure) {
                errors.accept(location + ": " + failure.getMessage());
            }
        }
        bindings.removeIf(binding -> conflicts.contains(binding.bedrockEntityIdentifier));
        parsed.node("entity-bindings").setList(EntityBinding.class, bindings);
        HydraulicConfig config = parsed.get(HydraulicConfig.class);

        // Keep ordering
        CommentedConfigurationNode newRoot = CommentedConfigurationNode.root(loader.defaultOptions());
        newRoot.set(config);
        if (!bindingsNode.virtual()) newRoot.node("entity-bindings").from(bindingsNode);

        if (originallyEmpty || currentVersion != newVersion) {
            loader.save(newRoot);
        }

        return config;
    }

    private static YamlConfigurationLoader createLoader(File configFile) {
        return YamlConfigurationLoader.builder()
            .file(configFile)
            .indent(2)
            .nodeStyle(NodeStyle.BLOCK)
            .defaultOptions(InterfaceDefaultOptions::addTo)
            .build();
    }
}
