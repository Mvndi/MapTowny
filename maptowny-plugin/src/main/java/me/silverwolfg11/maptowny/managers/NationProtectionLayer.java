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
        ProtectionGroup neutral = new ProtectionGroup(new UUID(0, 0), null, null);
        for (Town town : TownyUniverse.getInstance().getTowns()) {
            var nation = nationColors ? town.getNationOrNull() : null;
            ProtectionGroup group = nation == null ? neutral
                    : new ProtectionGroup(nation.getUUID(), nation.getName(), nation.getMapColor());
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
            List<String> exclusions = List.copyOf(plugin.config().getProtectionExcludedBiomeTags());
            Set<Long> worldClaims = claims.get(name);
            CompletableFuture<String> worldFingerprint = CompletableFuture.supplyAsync(
                    () -> NationProtectionCache.fingerprint(worldId, seed, worldClaims, radius, size, sampleY, exclusions),
                    plugin.getScheduler().getAsyncExecutor());
            CompletableFuture<List<ColoredGeometry>> render = worldFingerprint.thenApply(unused -> new ArrayList<>());
            // Serialize groups so every nation does not consume its own 2ms tick budget.
            var orderedGroups = new ArrayList<>(worldGroups.entrySet());
            orderedGroups.sort(Map.Entry.comparingByKey(java.util.Comparator.comparing(ProtectionGroup::id)));
            for (var entry : orderedGroups) {
                ProtectionGroup group = entry.getKey();
                Set<Long> groupClaims = entry.getValue();
                Path directory = plugin.getDataFolder().toPath().resolve("nation-protection-cache");
                NationProtectionCache cache = nationColors
                        ? new NationProtectionCache(directory.resolve(worldId.toString()), group.id)
                        : new NationProtectionCache(directory, worldId);
                render = render.thenCompose(results -> worldFingerprint.thenCompose(globalFingerprint ->
                        renderGroup(world, worldId, seed, name, group, cache, globalFingerprint, worldClaims, groupClaims,
                                radius, size, sampleY, exclusions, nationColors)).thenApply(polygons -> {
                    results.add(new ColoredGeometry(group, polygons));
                    return results;
                }));
            }
            renders.add(render.thenAcceptAsync(results -> {
                if (closed) return;
                layer.removeMarkers(key -> key.startsWith(MARKER_PREFIX));
                for (ColoredGeometry geometry : results) {
                    if (geometry.polygons.isEmpty()) continue;
                    var options = plugin.config().buildNationProtectionMarkerOptions();
                    if (geometry.group.color != null)
                        options.fillColor(geometry.group.color).strokeColor(geometry.group.color);
                    if (geometry.group.name != null) options.name(geometry.group.name);
                    layer.addMultiPolyMarker(MARKER_PREFIX + name + "_" + geometry.group.id,
                            geometry.polygons, options.build());
                }
            }, plugin.getScheduler().getExecutor()));
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
            NationProtectionCache cache, String globalFingerprint, Set<Long> worldClaims, Set<Long> groupClaims,
            int radius, int size, int sampleY, List<String> exclusions, boolean nationColors) {
        String label = name + (group.name == null ? "" : "/" + group.name);
        return CompletableFuture.supplyAsync(() -> {
            String fingerprint = nationColors ? globalFingerprint + NationProtectionCache.fingerprint(
                    worldId, seed, groupClaims, radius, size, sampleY, exclusions) : globalFingerprint;
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
            return CompletableFuture.supplyAsync(() -> {
                Set<Long> cells = bufferCells(groupClaims, radius);
                cells.removeAll(worldClaims);
                return cells;
            }, plugin.getScheduler().getAsyncExecutor()).thenCompose(cells -> {
                CompletableFuture<Collection<StaticTB>> filtered = new CompletableFuture<>();
                plugin.getScheduler().scheduleTask(() -> sampleBatch(world, cells.iterator(), new ArrayList<>(), filtered));
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
            }, plugin.getScheduler().getAsyncExecutor());
        });
    }

    private record ProtectionGroup(UUID id, String name, Color color) {}
    private record ColoredGeometry(ProtectionGroup group, List<Polygon> polygons) {}
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
                             CompletableFuture<Collection<StaticTB>> completion) {
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
                if (!isDeepOcean(world, StaticTB.rawX(cell), StaticTB.rawZ(cell)))
                    result.add(StaticTB.fromHashed(cell));
                if (System.nanoTime() >= deadline) break;
            }
            if (cells.hasNext()) plugin.getScheduler().scheduleTask(() -> sampleBatch(world, cells, result, completion));
            else completion.complete(result);
        } catch (RuntimeException error) {
            completion.completeExceptionally(error);
        }
    }

    private boolean isDeepOcean(World world, int townBlockX, int townBlockZ) {
        int size = TownySettings.getTownBlockSize();
        int x = townBlockX * size + size / 2;
        int z = townBlockZ * size + size / 2;
        String biome = world.getBiome(x, plugin.config().getProtectionBiomeSampleY(), z).getKey().toString();
        return plugin.config().getProtectionExcludedBiomeTags().stream().anyMatch(tag -> biome.equals(tag) ||
                (tag.endsWith("is_deep_ocean") && biome.endsWith("deep_ocean")));
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
