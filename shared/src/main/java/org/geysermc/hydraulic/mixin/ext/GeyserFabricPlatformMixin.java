package org.geysermc.hydraulic.mixin.ext;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.io.InputStream;

/**
 * Geyser-Fabric resolves bundled resources from the Geyser mod container itself.
 * In development/runtime-test builds the cached Geyser artifact can be missing
 * the languages submodule, so Hydraulic supplies the locale resources as a fallback.
 */
@Mixin(targets = "org.geysermc.geyser.platform.fabric.GeyserFabricPlatform")
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
