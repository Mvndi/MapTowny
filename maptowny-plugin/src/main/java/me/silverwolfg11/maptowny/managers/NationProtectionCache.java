package me.silverwolfg11.maptowny.managers;

import me.silverwolfg11.maptowny.objects.Point2D;
import me.silverwolfg11.maptowny.objects.Polygon;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Disk IO and hashing run exclusively on the map worker executor. */
final class NationProtectionCache {
    private static final int VERSION = 1;
    private static final int MAGIC = 0x4d544e50;
    private static final int MAX_POINTS = 2_000_000;
    private final Path file;

    NationProtectionCache(Path directory, UUID worldId) {
        file = directory.resolve(worldId + ".bin.gz");
    }

    static String fingerprint(UUID worldId, long seed, Set<Long> claims, int radius,
                              int blockSize, int sampleY, List<String> excludedBiomes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DataOutputStream output = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
                output.writeInt(VERSION);
                output.writeUTF(worldId.toString());
                output.writeLong(seed);
                output.writeInt(radius);
                output.writeInt(blockSize);
                output.writeInt(sampleY);
                List<String> exclusions = new ArrayList<>(excludedBiomes);
                Collections.sort(exclusions);
                output.writeInt(exclusions.size());
                for (String exclusion : exclusions) output.writeUTF(exclusion);
                long[] sortedClaims = claims.stream().mapToLong(Long::longValue).sorted().toArray();
                output.writeInt(sortedClaims.length);
                for (long claim : sortedClaims) output.writeLong(claim);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException error) {
            throw new IllegalStateException("Unable to fingerprint nation protection inputs", error);
        }
    }

    List<Polygon> read(String fingerprint) throws IOException {
        if (!Files.exists(file)) return null;
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION || !input.readUTF().equals(fingerprint)) return null;
            int count = bounded(input.readInt(), 100_000);
            List<Polygon> polygons = new ArrayList<>();
            int[] pointsRemaining = {MAX_POINTS};
            for (int i = 0; i < count; i++) {
                List<Point2D> outline = readRing(input, pointsRemaining);
                int holesCount = bounded(input.readInt(), 100_000);
                List<List<Point2D>> holes = new ArrayList<>();
                for (int h = 0; h < holesCount; h++) holes.add(readRing(input, pointsRemaining));
                polygons.add(new Polygon(outline, holes));
            }
            // Consume the gzip trailer too, so truncated/corrupt entries cannot be accepted.
            if (input.read() != -1) throw new IOException("Unexpected cache data");
            return polygons;
        }
    }

    void write(String fingerprint, List<Polygon> polygons) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
        try {
            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(temporary))))) {
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                output.writeUTF(fingerprint);
                output.writeInt(polygons.size());
                for (Polygon polygon : polygons) {
                    writeRing(output, polygon.getPoints());
                    output.writeInt(polygon.getNegativeSpace().size());
                    for (List<Point2D> hole : polygon.getNegativeSpace()) writeRing(output, hole);
                }
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static int bounded(int value, int maximum) throws IOException {
        if (value < 0 || value > maximum) throw new IOException("Invalid cache collection size");
        return value;
    }

    private static List<Point2D> readRing(DataInputStream input, int[] remaining) throws IOException {
        int size = bounded(input.readInt(), remaining[0]);
        if (size < 3) throw new IOException("Invalid cache polygon");
        remaining[0] -= size;
        List<Point2D> points = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            double x = input.readDouble(), z = input.readDouble();
            if (!Double.isFinite(x) || !Double.isFinite(z)) throw new IOException("Invalid cache coordinate");
            points.add(Point2D.of(x, z));
        }
        return points;
    }

    private static void writeRing(DataOutputStream output, List<Point2D> ring) throws IOException {
        output.writeInt(ring.size());
        for (Point2D point : ring) {
            output.writeDouble(point.x());
            output.writeDouble(point.z());
        }
    }
}
