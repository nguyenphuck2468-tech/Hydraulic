package org.geysermc.hydraulic.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.InputStream;

/**
 * Makes Hydraulic's bundled Geyser resources visible to the Geyser mod bootstrap.
 *
 * GeyserModBootstrap normally resolves resources from the Geyser platform container.
 * Hydraulic bundles Geyser as a runtime dependency, so locale resources shipped by
 * Hydraulic are not necessarily visible through that platform resolver.
 */
@Mixin(targets = "org.geysermc.geyser.platform.mod.GeyserModBootstrap")
public abstract class GeyserModBootstrapResourceMixin {
    @Inject(method = "getResourceOrNull", at = @At("HEAD"), cancellable = true)
    private void hydraulic$resolveBundledResource(String resource, CallbackInfoReturnable<InputStream> cir) {
        InputStream stream = GeyserModBootstrapResourceMixin.class.getClassLoader().getResourceAsStream(resource);
        if (stream != null) {
            cir.setReturnValue(stream);
        }
    }
}
