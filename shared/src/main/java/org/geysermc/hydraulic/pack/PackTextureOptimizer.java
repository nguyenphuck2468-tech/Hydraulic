package org.geysermc.hydraulic.pack;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/** Applies bounded, reference-safe image reductions without renaming assets. */
final class PackTextureOptimizer {
    private PackTextureOptimizer() {
    }

    static Result optimize(Path packDirectory, PackProfile profile) {
        return optimize(packDirectory, profile, null);
    }

    static Result optimize(Path packDirectory, PackProfile profile, Path cacheDirectory) {
        Path textures = packDirectory.resolve("textures");
        if (!Files.isDirectory(textures)) return Result.EMPTY;
        List<ImageInfo> images = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(textures)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".png"))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(path -> {
                        ImageInfo info = inspect(path);
                        if (info != null) images.add(info);
                    });
        } catch (IOException ignored) {
            return Result.EMPTY;
        }

        long originalPixels = images.stream().mapToLong(ImageInfo::pixels).sum();
        if (profile == PackProfile.FULL || originalPixels == 0) {
            return result(images, 0, originalPixels, originalPixels);
        }
        double totalScale = originalPixels > profile.maxTexturePixels()
                ? Math.sqrt((double) profile.maxTexturePixels() / originalPixels) : 1.0;
        int changed = 0;
        int cacheHits = 0;
        long outputPixels = 0;
        for (ImageInfo image : images) {
            double edgeScale = Math.min(1.0, (double) profile.maxTextureEdge() / Math.max(image.width(), image.height()));
            double scale = Math.min(edgeScale, totalScale);
            int width = Math.max(1, (int) Math.floor(image.width() * scale));
            int height = Math.max(1, (int) Math.floor(image.height() * scale));
            if (width != image.width() || height != image.height()) {
                ResizeResult resize = resize(image.path(), width, height, cacheDirectory);
                if (resize.changed()) changed++;
                if (resize.cacheHit()) cacheHits++;
            }
            else {
                width = image.width();
                height = image.height();
            }
            outputPixels += (long) width * height;
        }
        Result result = result(images, changed, originalPixels, outputPixels);
        return new Result(result.images(), result.resized(), cacheHits, result.originalPixels(), result.outputPixels(),
                result.largestTexture(), result.largestWidth(), result.largestHeight());
    }

    private static Result result(List<ImageInfo> images, int changed, long originalPixels, long outputPixels) {
        ImageInfo largest = images.stream().max(Comparator.comparingLong(ImageInfo::pixels)).orElse(null);
        return new Result(images.size(), changed, 0, originalPixels, outputPixels,
                largest == null ? null : largest.path().toString().replace('\\', '/'),
                largest == null ? 0 : largest.width(), largest == null ? 0 : largest.height());
    }

    private static ImageInfo inspect(Path path) {
        try (ImageInputStream input = ImageIO.createImageInputStream(path.toFile())) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                return new ImageInfo(path, reader.getWidth(0), reader.getHeight(0));
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static ResizeResult resize(Path path, int targetWidth, int targetHeight, Path cacheDirectory) {
        Path temporary = path.resolveSibling(path.getFileName() + ".part");
        try {
            Path cached = cachePath(path, targetWidth, targetHeight, cacheDirectory);
            if (cached != null && Files.isRegularFile(cached)) {
                Files.copy(cached, temporary, StandardCopyOption.REPLACE_EXISTING);
                replace(temporary, path);
                return new ResizeResult(true, true);
            }
            BufferedImage source;
            try (ImageInputStream input = ImageIO.createImageInputStream(path.toFile())) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) return ResizeResult.NONE;
                ImageReader reader = readers.next();
                try {
                    reader.setInput(input, true, true);
                    var parameters = reader.getDefaultReadParam();
                    int sample = Math.max(1, Math.min(reader.getWidth(0) / targetWidth, reader.getHeight(0) / targetHeight));
                    parameters.setSourceSubsampling(sample, sample, 0, 0);
                    source = reader.read(0, parameters);
                } finally {
                    reader.dispose();
                }
            }
            BufferedImage output = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = output.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
            } finally {
                graphics.dispose();
            }
            if (!ImageIO.write(output, "png", temporary.toFile())) return ResizeResult.NONE;
            if (cached != null) {
                Files.createDirectories(cached.getParent());
                Path cachePart = cached.resolveSibling(cached.getFileName() + ".part");
                Files.copy(temporary, cachePart, StandardCopyOption.REPLACE_EXISTING);
                replace(cachePart, cached);
            }
            replace(temporary, path);
            return new ResizeResult(true, false);
        } catch (IOException | RuntimeException ignored) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignoredDelete) {
            }
            return ResizeResult.NONE;
        }
    }

    private static Path cachePath(Path path, int width, int height, Path cacheDirectory) throws IOException {
        if (cacheDirectory == null) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                for (int read; (read = input.read(buffer)) >= 0; ) digest.update(buffer, 0, read);
            }
            digest.update((byte) 0);
            digest.update((width + "x" + height).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return cacheDirectory.resolve(HexFormat.of().formatHex(digest.digest()) + ".png");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void replace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record ImageInfo(Path path, int width, int height) {
        long pixels() {
            return (long) width * height;
        }
    }

    private record ResizeResult(boolean changed, boolean cacheHit) {
        static final ResizeResult NONE = new ResizeResult(false, false);
    }

    record Result(int images, int resized, int cacheHits, long originalPixels, long outputPixels,
                  String largestTexture, int largestWidth, int largestHeight) {
        static final Result EMPTY = new Result(0, 0, 0, 0, 0, null, 0, 0);
    }
}
