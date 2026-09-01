package org.geysermc.hydraulic.pack;

import java.nio.file.Path;
import org.geysermc.hydraulic.entity.EntityEventRegistrar;

/** Separate-JVM probe used by the restart cache regression test. */
public final class PackCacheProbe {
    private PackCacheProbe() {
    }

    public static void main(String[] args) {
        Path archive = Path.of(args[0]);
        System.out.println(PackListener.cacheStatus(archive, args[1])
                + " entities=" + EntityEventRegistrar.restorePackBacked(archive));
    }
}
