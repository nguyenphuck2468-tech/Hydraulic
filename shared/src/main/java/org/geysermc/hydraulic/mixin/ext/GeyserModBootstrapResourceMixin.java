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
 * Supplies Geyser's bundled language resources from Hydraulic when the Geyser
 * development/runtime artifact was built without its languages submodule.
 *
 * Hooking GeyserModBootstrap keeps this independent of the Fabric-specific
 * Geyser platform implementation and therefore avoids a compile-time dependency
 * on a platform class.
 */
@Mixin(targets = "org.geysermc.geyser.platform.mod.GeyserModBootstrap")
public abstract class GeyserModBootstrapResourceMixin {
    @Inject(method = "getResourceOrNull", at = @At("HEAD"), cancellable = true)
    private void hydraulic$resolveBundledResource(String resource, CallbackInfoReturnable<InputStream> cir) {
        if (!resource.startsWith("languages/texts/")) {
            return;
        }

        ModContainer hydraulic = FabricLoader.getInstance().getModContainer("hydraulic").orElse(null);
        if (hydraulic == null) {
            return;
        }

        var path = hydraulic.findPath(resource).orElse(null);
        if (path == null) {
            return;
        }

        try {
            cir.setReturnValue(path.getFileSystem().provider().newInputStream(path));
        } catch (IOException ignored) {
            // Fall through to Geyser's normal resource resolver.
        }
    }
}
