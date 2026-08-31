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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

/** Applies bounded, reference-safe image reductions without renaming assets. */
final class PackTextureOptimizer {
    private PackTextureOptimizer() {
    }

    static Result optimize(Path packDirectory, PackProfile profile) {
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
            return new Result(images.size(), 0, originalPixels, originalPixels);
        }
        double totalScale = originalPixels > profile.maxTexturePixels()
                ? Math.sqrt((double) profile.maxTexturePixels() / originalPixels) : 1.0;
        int changed = 0;
        long outputPixels = 0;
        for (ImageInfo image : images) {
            double edgeScale = Math.min(1.0, (double) profile.maxTextureEdge() / Math.max(image.width(), image.height()));
            double scale = Math.min(edgeScale, totalScale);
            int width = Math.max(1, (int) Math.floor(image.width() * scale));
            int height = Math.max(1, (int) Math.floor(image.height() * scale));
            if ((width != image.width() || height != image.height()) && resize(image.path(), width, height)) changed++;
            else {
                width = image.width();
                height = image.height();
            }
            outputPixels += (long) width * height;
        }
        return new Result(images.size(), changed, originalPixels, outputPixels);
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

    private static boolean resize(Path path, int targetWidth, int targetHeight) {
        Path temporary = path.resolveSibling(path.getFileName() + ".part");
        try {
            BufferedImage source;
            try (ImageInputStream input = ImageIO.createImageInputStream(path.toFile())) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) return false;
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
            if (!ImageIO.write(output, "png", temporary.toFile())) return false;
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | RuntimeException ignored) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignoredDelete) {
            }
            return false;
        }
    }

    private record ImageInfo(Path path, int width, int height) {
        long pixels() {
            return (long) width * height;
        }
    }

    record Result(int images, int resized, long originalPixels, long outputPixels) {
        static final Result EMPTY = new Result(0, 0, 0, 0);
    }
}
