package org.geysermc.hydraulic.mixin.ext;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.io.InputStream;

/**
 * Makes Geyser's locale resources available when its languages submodule is not
 * present in the runtime artifact. Hydraulic bundles the language files instead.
 */
@Mixin(targets = "org.geysermc.geyser.platform.mod.GeyserModBootstrap", remap = false)
public abstract class GeyserModBootstrapResourceMixin {
    @Inject(
            method = "getResourceOrNull(Ljava/lang/String;)Ljava/io/InputStream;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void hydraulic$resolveBundledResource(String resource, CallbackInfoReturnable<InputStream> cir) {
        if (!resource.startsWith("languages/texts/")) {
            return;
        }

        ModContainer hydraulic = FabricLoader.getInstance()
                .getModContainer("hydraulic")
                .orElse(null);
        if (hydraulic == null) {
            return;
        }

        // Prefer Fabric's mod root so this works both from the development
        // runtime and from the final bundled Hydraulic jar.
        var path = hydraulic.findPath(resource).orElse(null);
        if (path != null) {
            try {
                cir.setReturnValue(path.getFileSystem().provider().newInputStream(path));
                return;
            } catch (IOException ignored) {
                // Fall through to the classloader fallback below.
            }
        }

        InputStream stream = GeyserModBootstrapResourceMixin.class
                .getClassLoader()
                .getResourceAsStream(resource);
        if (stream != null) {
            cir.setReturnValue(stream);
        }
    }
}
