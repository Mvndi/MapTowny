package me.silverwolfg11.maptowny.managers;

import com.palmergames.bukkit.towny.TownySettings;
import com.palmergames.bukkit.towny.TownyUniverse;
import com.palmergames.bukkit.towny.object.Town;
import com.palmergames.bukkit.towny.object.TownBlock;
import me.silverwolfg11.maptowny.MapTowny;
import me.silverwolfg11.maptowny.objects.Polygon;
import me.silverwolfg11.maptowny.objects.StaticTB;
import me.silverwolfg11.maptowny.objects.TBCluster;
import me.silverwolfg11.maptowny.platform.MapLayer;
import me.silverwolfg11.maptowny.platform.MapPlatform;
import me.silverwolfg11.maptowny.platform.MapWorld;
import me.silverwolfg11.maptowny.util.PolygonUtil;
import org.bukkit.World;
import org.bukkit.block.Biome;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.io.IOException;
import java.awt.Color;
import java.util.UUID;
import java.nio.file.Path;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.Map;
import java.util.Set;

/** Renders Towny's configured town-plot spacing as a map-only buffer. */
final class NationProtectionLayer {
    private static final String KEY = "nation_protection";
    private static final String MARKER_PREFIX = "nation_protection_";

    private final MapTowny plugin;
    private final MapPlatform platform;
    private final Map<String, MapLayer> layers = new HashMap<>();
    private final Map<String, Map<ProtectionGroup, Set<Long>>> renderedClaims = new HashMap<>();
    private boolean refreshing;
    private volatile boolean closed;
    private int renderedRadius = -1;

    NationProtectionLayer(MapTowny plugin, MapPlatform platform, TownyLayerManager towns) {
        this.plugin = plugin;
        this.platform = platform;
        for (String worldName : plugin.config().getEnabledWorlds()) {
            World world = plugin.getServer().getWorld(worldName);
            if (world == null || !platform.isWorldEnabled(world)) continue;
            MapWorld mapWorld = platform.getWorld(world);
            if (mapWorld != null) layers.put(worldName, mapWorld.registerLayer(KEY, plugin.config().getNationProtectionLayerOptions()));
        }
    }

    void refresh() {
        if (closed || refreshing) return;
        // Capture all Towny state, including membership and colours, on its scheduler.
        Map<String, Set<Long>> claims = new HashMap<>();
        Map<String, Map<ProtectionGroup, Set<Long>>> groups = new HashMap<>();
        layers.keySet().forEach(name -> {
            claims.put(name, new HashSet<>());
            groups.put(name, new HashMap<>());
        });
        boolean nationColors = plugin.config().useNationProtectionMapColor();
        for (Town town : TownyUniverse.getInstance().getTowns()) {
            var nation = nationColors ? town.getNationOrNull() : null;
            ProtectionGroup group = new ProtectionGroup(town.getUUID(), town.getName(),
                    nation == null ? null : nation.getMapColor());
            for (TownBlock block : town.getTownBlocks()) {
                String name = block.getWorld().getName();
                Set<Long> worldClaims = claims.get(name);
                if (worldClaims == null) continue;
                long cell = StaticTB.hashPos(block.getX(), block.getZ());
                worldClaims.add(cell);
                groups.get(name).computeIfAbsent(group, unused -> new HashSet<>()).add(cell);
            }
        }
        int radius = TownySettings.getMinDistanceFromTownPlotblocks();
        int size = TownySettings.getTownBlockSize();
        if (radius == renderedRadius && groups.equals(renderedClaims)) return;
        refreshing = true;
        List<CompletableFuture<Void>> renders = new ArrayList<>();
        groups.forEach((name, worldGroups) -> {
            World world = plugin.getServer().getWorld(name);
            MapLayer layer = layers.get(name);
            if (world == null) return;
            UUID worldId = world.getUID();
            long seed = world.getSeed();
            int sampleY = plugin.config().getProtectionBiomeSampleY();
            List<String> exclusions = resolveExcludedBiomes();
            Set<Long> worldClaims = claims.get(name);
            Set<String> currentMarkers = new HashSet<>();
            for (ProtectionGroup group : worldGroups.keySet())
                currentMarkers.add(MARKER_PREFIX + name + "_" + group.id);
            layer.removeMarkers(key -> key.startsWith(MARKER_PREFIX) && !currentMarkers.contains(key));
            ScanQueue scans = new ScanQueue();
            for (var entry : worldGroups.entrySet()) {
                ProtectionGroup group = entry.getKey();
                Set<Long> groupClaims = entry.getValue();
                Path directory = plugin.getDataFolder().toPath().resolve("nation-protection-cache");
                NationProtectionCache cache = new NationProtectionCache(directory.resolve(worldId.toString()), group.id);
                renders.add(renderGroup(world, worldId, seed, name, group, cache, worldClaims, groupClaims,
                        radius, size, sampleY, exclusions, scans).thenAcceptAsync(polygons -> {
                    if (closed) return;
                    String marker = MARKER_PREFIX + name + "_" + group.id;
                    layer.removeMarkers(key -> key.equals(marker));
                    if (polygons.isEmpty()) return;
                    var options = plugin.config().buildNationProtectionMarkerOptions();
                    if (group.color != null) options.fillColor(group.color).strokeColor(group.color);
                    String tooltip = group.name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                            + " (buffer-zone)";
                    options.name(group.name).clickTooltip(tooltip).hoverTooltip(tooltip);
                    layer.addMultiPolyMarker(marker, polygons, options.build());
                }, plugin.getScheduler().getExecutor()));
            }
        });
        CompletableFuture.allOf(renders.toArray(CompletableFuture[]::new)).whenCompleteAsync((unused, error) -> {
            refreshing = false;
            if (closed) return;
            if (error != null) plugin.getLogger().log(Level.WARNING, "Unable to refresh nation protection overlay", error);
            else {
                renderedClaims.clear();
                renderedClaims.putAll(groups);
                renderedRadius = radius;
            }
        }, plugin.getScheduler().getExecutor());
    }

    private CompletableFuture<List<Polygon>> renderGroup(World world, UUID worldId, long seed, String name, ProtectionGroup group,
            NationProtectionCache cache, Set<Long> worldClaims, Set<Long> groupClaims,
            int radius, int size, int sampleY, List<String> exclusions, ScanQueue scans) {
        String label = name + (group.name == null ? "" : "/" + group.name);
        return CompletableFuture.supplyAsync(() -> {
            String fingerprint = groupFingerprint(worldId, seed, worldClaims, groupClaims,
                    radius, size, sampleY, exclusions, true);
            List<Polygon> cached = null;
            try {
                cached = cache.read(fingerprint);
            } catch (IOException error) {
                plugin.getLogger().log(Level.WARNING, "Ignoring invalid nation protection cache for " + label, error);
            }
            return new CacheResult(fingerprint, cached);
        }, plugin.getScheduler().getAsyncExecutor()).thenCompose(entry -> {
            if (entry.polygons != null) {
                plugin.getLogger().info("Loaded nation protection cache for " + label + " (" + entry.polygons.size() + " polygons; no biome scan)");
                return CompletableFuture.completedFuture(entry.polygons);
            }
            return scans.enqueue(() -> CompletableFuture.supplyAsync(() -> {
                if (closed) throw new java.util.concurrent.CancellationException();
                Set<Long> cells = new HashSet<>(bufferCells(groupClaims, radius));
                cells.removeAll(worldClaims);
                return cells;
            }, plugin.getScheduler().getAsyncExecutor()).thenCompose(cells -> {
                CompletableFuture<Collection<StaticTB>> filtered = new CompletableFuture<>();
                plugin.getScheduler().scheduleTask(() -> sampleBatch(world, cells.iterator(), new ArrayList<>(), filtered, size, sampleY, Set.copyOf(exclusions)));
                return filtered;
            }).thenApplyAsync(cells -> buildPolygons(cells, size), plugin.getScheduler().getAsyncExecutor())
                    .thenApplyAsync(polygons -> {
                if (closed) throw new java.util.concurrent.CancellationException();
                try {
                    cache.write(entry.fingerprint, polygons);
                    plugin.getLogger().info("Saved nation protection cache for " + label + " (" + polygons.size() + " polygons)");
                } catch (IOException error) {
                    plugin.getLogger().log(Level.WARNING, "Unable to save nation protection cache for " + label, error);
                }
                return polygons;
            }, plugin.getScheduler().getAsyncExecutor()));
        });
    }

    static String groupFingerprint(UUID worldId, long seed, Set<Long> worldClaims, Set<Long> groupClaims,
        int radius, int size, int sampleY, List<String> exclusions, boolean nationColors) {
        Set<Long> relevantClaims = worldClaims;
        if (nationColors) {
            // Only claims inside this group's buffer can change its unclaimed holes.
            relevantClaims = new HashSet<>(bufferCells(groupClaims, radius));
            relevantClaims.retainAll(worldClaims);
        }
        String fingerprint = NationProtectionCache.fingerprint(
                worldId, seed, relevantClaims, radius, size, sampleY, exclusions);
        if (nationColors) fingerprint = "local-v3-biome-tags:" + fingerprint + NationProtectionCache.fingerprint(
                worldId, seed, groupClaims, radius, size, sampleY, exclusions);
        return fingerprint;
    }

    private record ProtectionGroup(UUID id, String name, Color color) {}
    /** Cache reads run independently; only biome scans share the per-world tick budget. */
    private static final class ScanQueue {
        private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);

        synchronized CompletableFuture<List<Polygon>> enqueue(
                java.util.function.Supplier<CompletableFuture<List<Polygon>>> scan) {
            CompletableFuture<List<Polygon>> next = tail.handle((unused, error) -> null)
                    .thenCompose(unused -> scan.get());
            tail = next.handle((unused, error) -> null);
            return next;
        }
    }
    private record CacheResult(String fingerprint, List<Polygon> polygons) {}

    private static List<Polygon> buildPolygons(Collection<StaticTB> cells, int size) {
        List<Polygon> polygons = new ArrayList<>();
        for (TBCluster cluster : TBCluster.findClusters(cells)) {
            PolygonUtil.PolyFormResult result = PolygonUtil.getPolyInfoFromCluster(cluster, size);
            if (!result.getPolygonPoints().isEmpty()) {
                List<List<me.silverwolfg11.maptowny.objects.Point2D>> holes = result.getNegativeSpaceClusters().stream()
                        .map(hole -> PolygonUtil.getPolyInfoFromCluster(hole, size, false).getPolygonPoints())
                        .toList();
                polygons.add(new Polygon(result.getPolygonPoints(), holes));
            }
        }
        return polygons;
    }

    private static Set<Long> bufferCells(Set<Long> claimed, int radius) {
        if (radius <= 0) return Set.of();
        Map<Integer, List<int[]>> rows = new HashMap<>();
        for (long hash : claimed) rows.computeIfAbsent(StaticTB.rawZ(hash), unused -> new ArrayList<>())
                .add(new int[]{StaticTB.rawX(hash), StaticTB.rawX(hash)});
        Map<Integer, List<int[]>> expanded = new HashMap<>();
        // Expand contiguous runs rather than visiting (2r+1)^2 cells per claim.
        rows.forEach((z, runs) -> {
            for (int[] run : mergeRuns(runs)) {
                for (int dz = -radius; dz <= radius; dz++) {
                    expanded.computeIfAbsent(z + dz, unused -> new ArrayList<>())
                            .add(new int[]{run[0] - radius, run[1] + radius});
                }
            }
        });
        Set<Long> cells = new HashSet<>();
        expanded.forEach((z, runs) -> {
            for (int[] run : mergeRuns(runs)) {
                for (int x = run[0]; x <= run[1]; x++) {
                    long cell = StaticTB.hashPos(x, z);
                    if (!claimed.contains(cell)) cells.add(cell);
                }
            }
        });
        return cells;
    }

    private static List<int[]> mergeRuns(List<int[]> runs) {
        runs.sort(java.util.Comparator.comparingInt(run -> run[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] run : runs) {
            if (merged.isEmpty() || run[0] > merged.get(merged.size() - 1)[1] + 1)
                merged.add(run.clone());
            else merged.get(merged.size() - 1)[1] = Math.max(merged.get(merged.size() - 1)[1], run[1]);
        }
        return merged;
    }

    private void sampleBatch(World world, Iterator<Long> cells, Collection<StaticTB> result,
                             CompletableFuture<Collection<StaticTB>> completion, int size, int sampleY, Set<String> exclusions) {
        if (closed || world == null) {
            completion.cancel(false);
            return;
        }
        try {
            // Noise-biome lookup does not load/generate chunks. Bound both work and time
            // on the global scheduler; never query Bukkit world state from a worker.
            long deadline = System.nanoTime() + 2_000_000L;
            int remaining = plugin.config().getProtectionChunksPerBatch();
            while (remaining-- > 0 && cells.hasNext()) {
                long cell = cells.next();
                int x = StaticTB.rawX(cell) * size + size / 2;
                int z = StaticTB.rawZ(cell) * size + size / 2;
                if (!exclusions.contains(world.getBiome(x, sampleY, z).getKey().toString()))
                    result.add(StaticTB.fromHashed(cell));
                if (System.nanoTime() >= deadline) break;
            }
            if (cells.hasNext()) plugin.getScheduler().scheduleTask(() -> sampleBatch(world, cells, result, completion, size, sampleY, exclusions));
            else completion.complete(result);
        } catch (RuntimeException error) {
            completion.completeExceptionally(error);
        }
    }

    private List<String> resolveExcludedBiomes() {
        var registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME);
        Set<String> excluded = new HashSet<>();
        for (String entry : plugin.config().getProtectionExcludedBiomeTags()) {
            try {
                var tag = RegistryKey.BIOME.tagKey(entry);
                if (registry.hasTag(tag)) {
                    for (Biome biome : registry.getTagValues(tag)) excluded.add(biome.getKey().toString());
                } else {
                    // Configuration also accepts individual biome keys.
                    Biome biome = registry.get(org.bukkit.NamespacedKey.fromString(entry));
                    if (biome != null) excluded.add(biome.getKey().toString());
                }
            } catch (IllegalArgumentException error) {
                plugin.getLogger().warning("Invalid excluded biome tag or key: " + entry);
            }
        }
        return excluded.stream().sorted().toList();
    }

    void close() {
        closed = true;
        for (Map.Entry<String, MapLayer> entry : layers.entrySet()) {
            World world = plugin.getServer().getWorld(entry.getKey());
            if (world == null) continue;
            MapWorld mapWorld = platform.getWorld(world);
            if (mapWorld != null) mapWorld.unregisterLayer(KEY);
        }
        layers.clear();
    }
}
