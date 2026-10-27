package me.silverwolfg11.maptowny.managers;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.Files;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

final class SiegeIconLoader {
    private static final String BUILT_IN = "built-in";
    private static final String HTTP = "http";
    private static final String HTTPS = "https";
    private static final String FILE = "file";
    private static final String SCHEME_SEPARATOR = ":";
    private static final String INVALID_IMAGE = "Siege icon is not a supported image: ";
    private static final String UNSUPPORTED_SCHEME = "Unsupported siege icon URL scheme: ";
    private static final String LOAD_FAILURE = "Unable to load siege icon; using built-in image: ";
    private static final int TIMEOUT_MILLIS = 5_000;
    private static final Set<String> URL_SCHEMES = Set.of(HTTP, HTTPS, FILE);

    private final File directory;
    private final Logger logger;

    SiegeIconLoader(File directory, Logger logger) {
        this.directory = directory;
        this.logger = logger;
    }

    BufferedImage loadImage(String source, BufferedImage fallback) {
        if (source.isBlank() || BUILT_IN.equalsIgnoreCase(source.strip()))
            return fallback;
        try (InputStream stream = openStream(source.strip())) {
            BufferedImage image = ImageIO.read(stream);
            if (image == null)
                throw new IOException(INVALID_IMAGE + source);
            return image;
        } catch (IOException | IllegalArgumentException exception) {
            logger.log(Level.WARNING, LOAD_FAILURE + source, exception);
            return fallback;
        }
    }

    private InputStream openStream(String source) throws IOException {
        if (!source.contains(SCHEME_SEPARATOR))
            return openFile(source);
        URI uri = URI.create(source);
        if (!URL_SCHEMES.contains(uri.getScheme()))
            throw new IOException(UNSUPPORTED_SCHEME + uri.getScheme());
        URLConnection connection = uri.toURL().openConnection();
        connection.setConnectTimeout(TIMEOUT_MILLIS);
        connection.setReadTimeout(TIMEOUT_MILLIS);
        connection.setUseCaches(false);
        return connection.getInputStream();
    }

    private InputStream openFile(String source) throws IOException {
        File file = new File(source);
        if (!file.isAbsolute())
            file = new File(directory, source);
        return Files.newInputStream(file.toPath());
    }
}
