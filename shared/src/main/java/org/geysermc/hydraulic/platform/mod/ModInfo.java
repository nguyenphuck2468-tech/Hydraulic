package org.geysermc.hydraulic.platform.mod;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.util.Collection;

public record ModInfo(
    @NotNull String id,
    @NotNull String namespace,
    @NotNull String name,
    @NotNull String version,
    @Nullable Path iconPath,
    @NotNull Collection<Path> roots
) {
    @Nullable
    public Path resolveFile(String file) {
        for (final Path path : roots) {
            final Path root = path.toAbsolutePath().normalize();
            final Path resolved = root.resolve(file.replace("/", path.getFileSystem().getSeparator())).normalize();
            if (!resolved.startsWith(root)) continue;
            if (Files.isRegularFile(resolved)) {
                return resolved;
            }
        }
        return null;
    }

    /** Returns the loader's exact source jar when these roots were mounted from one. */
    @Nullable
    public Path sourceJar() {
        for (Path root : roots) {
            if (Files.isRegularFile(root)) return root;
            if ("jar".equals(root.getFileSystem().provider().getScheme())) {
                try {
                    String uri = root.toUri().toString();
                    int separator = uri.indexOf("!/");
                    if (!uri.startsWith("jar:") || separator < 0) continue;
                    Path jar = Path.of(URI.create(uri.substring("jar:".length(), separator)));
                    if (Files.isRegularFile(jar)) return jar;
                } catch (RuntimeException ignored) {
                    // Development directories have no source jar to reflect.
                }
            }
        }
        return null;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof ModInfo other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
