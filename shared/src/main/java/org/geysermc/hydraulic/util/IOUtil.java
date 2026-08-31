package org.geysermc.hydraulic.util;

import org.apache.commons.io.function.IOFunction;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

public class IOUtil {
    public static byte[] readBytes(Path path, int maxBytes) throws IOException {
        if (maxBytes < 0 || Files.size(path) > maxBytes) throw new IOException("File exceeds " + maxBytes + " bytes: " + path);
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) throw new IOException("File exceeds " + maxBytes + " bytes: " + path);
            return bytes;
        }
    }

    public static String readString(Path path, Charset charset, int maxBytes) throws IOException {
        return new String(readBytes(path, maxBytes), charset);
    }

    public static <T, R> Function<T, R> uncheckFunction(IOFunction<T, R> function) {
        return t -> {
            try {
                return function.apply(t);
            } catch (IOException e) {
                throw rethrow(e);
            }
        };
    }

    @SuppressWarnings("unchecked")
    public static <T extends Throwable> T rethrow(IOException exception) throws T {
        throw (T)exception;
    }
}
