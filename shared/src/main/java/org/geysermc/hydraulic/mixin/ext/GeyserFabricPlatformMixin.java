package org.geysermc.hydraulic.mixin.ext;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.geyser.platform.fabric.GeyserFabricPlatform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.io.InputStream;

/**
 * Geyser-Fabric resolves bundled resources from the Geyser mod container itself.
 *
 * In development/runtime-test builds the Geyser dependency can be resolved from
 * a cached artifact that was built without the Geyser languages submodule. The
 * locale files are supplied by Hydraulic instead, so expose them as a fallback
 * without changing Geyser's own bootstrap implementation.
 */
@Mixin(GeyserFabricPlatform.class)
public abstract class GeyserFabricPlatformMixin {
    @Inject(method = "resolveResource", at = @At("HEAD"), cancellable = true)
    private void hydraulic$resolveBundledResource(String resource, CallbackInfoReturnable<@Nullable InputStream> cir) {
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
            // Fall back to Geyser's normal resource resolver.
        }
    }
}
