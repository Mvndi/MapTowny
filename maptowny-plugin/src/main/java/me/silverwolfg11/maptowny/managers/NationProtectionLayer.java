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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Renders Towny's configured town-plot spacing as a map-only buffer. */
final class NationProtectionLayer {
    private static final String KEY = "nation_protection";
    private static final String MARKER_PREFIX = "nation_protection_";

    private final MapTowny plugin;
    private final MapPlatform platform;
    private final TownyLayerManager towns;
    private final Map<String, MapLayer> layers = new HashMap<>();

    NationProtectionLayer(MapTowny plugin, MapPlatform platform, TownyLayerManager towns) {
        this.plugin = plugin;
        this.platform = platform;
        this.towns = towns;
        for (String worldName : plugin.config().getEnabledWorlds()) {
            World world = plugin.getServer().getWorld(worldName);
            if (world == null || !platform.isWorldEnabled(world)) continue;
            MapWorld mapWorld = platform.getWorld(world);
            if (mapWorld != null) layers.put(worldName, mapWorld.registerLayer(KEY, plugin.config().getNationProtectionLayerOptions()));
        }
    }

    void refresh() {
        layers.forEach((worldName, layer) -> {
            layer.removeMarkers(key -> key.startsWith(MARKER_PREFIX));
            Collection<StaticTB> protectedCells = protectedCells(worldName);
            if (protectedCells.isEmpty()) return;
            List<Polygon> polygons = new ArrayList<>();
            for (TBCluster cluster : TBCluster.findClusters(protectedCells)) {
                PolygonUtil.PolyFormResult result = PolygonUtil.getPolyInfoFromCluster(cluster, TownySettings.getTownBlockSize());
                if (!result.getPolygonPoints().isEmpty()) {
                    List<List<me.silverwolfg11.maptowny.objects.Point2D>> holes = result.getNegativeSpaceClusters().stream()
                            .map(hole -> PolygonUtil.getPolyInfoFromCluster(hole, TownySettings.getTownBlockSize(), false).getPolygonPoints())
                            .toList();
                    polygons.add(new Polygon(result.getPolygonPoints(), holes));
                }
            }
            if (!polygons.isEmpty()) layer.addMultiPolyMarker(MARKER_PREFIX + worldName, polygons,
                    plugin.config().buildNationProtectionMarkerOptions().build());
        });
    }

    private Collection<StaticTB> protectedCells(String worldName) {
        int radius = TownySettings.getMinDistanceFromTownPlotblocks();
        if (radius <= 0) return List.of();
        Set<Long> claimed = new HashSet<>();
        Set<Long> cells = new HashSet<>();
        World world = plugin.getServer().getWorld(worldName);
        if (world == null) return List.of();
        for (Town town : TownyUniverse.getInstance().getTowns()) {
            for (TownBlock block : town.getTownBlocks()) {
                if (worldName.equals(block.getWorld().getName()))
                    claimed.add(StaticTB.hashPos(block.getX(), block.getZ()));
            }
        }
        for (Town town : TownyUniverse.getInstance().getTowns()) {
            for (TownBlock block : town.getTownBlocks()) {
                if (!worldName.equals(block.getWorld().getName())) continue;
                int x = block.getX(), z = block.getZ();
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        int cellX = x + dx, cellZ = z + dz;
                        if (claimed.contains(StaticTB.hashPos(cellX, cellZ)) || isDeepOcean(world, cellX, cellZ)) continue;
                        cells.add(StaticTB.hashPos(cellX, cellZ));
                    }
                }
            }
        }
        return cells.stream().map(StaticTB::fromHashed).toList();
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
        for (Map.Entry<String, MapLayer> entry : layers.entrySet()) {
            World world = plugin.getServer().getWorld(entry.getKey());
            if (world == null) continue;
            MapWorld mapWorld = platform.getWorld(world);
            if (mapWorld != null) mapWorld.unregisterLayer(KEY);
        }
        layers.clear();
    }
}
