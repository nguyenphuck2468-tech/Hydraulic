package org.geysermc.hydraulic.pack.converter;

import org.geysermc.pack.converter.pipeline.AssetExtractor;
import org.geysermc.pack.converter.pipeline.ExtractionContext;
import org.geysermc.pack.converter.type.model.ModelStitcher;
import team.unnamed.creative.ResourcePack;
import team.unnamed.creative.model.Model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Stitches models independently so an unsupported model in one gameplay mod does
 * not prevent the remaining models and resource pack assets from being converted.
 */
public class CustomModelConverter implements AssetExtractor<Model> {
    private final ModelStitcher.Provider modelProvider;

    public CustomModelConverter(ModelStitcher.Provider modelProvider) {
        this.modelProvider = modelProvider;
    }

    @Override
    public Collection<Model> extract(ResourcePack pack, ExtractionContext context) {
        List<Model> stitchedModels = new ArrayList<>();
        for (Model model : pack.models()) {
            try {
                Model stitched = new ModelStitcher(this.modelProvider, model, context.logListener()).stitch();
                if (stitched != null) {
                    stitchedModels.add(stitched);
                }
            } catch (RuntimeException exception) {
                context.logListener().warn("Failed to stitch a model; skipping it and continuing conversion: " + exception.getMessage());
            }
        }
        return stitchedModels;
    }
}
