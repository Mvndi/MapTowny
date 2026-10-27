package me.silverwolfg11.maptowny.mapmode;

import com.google.common.html.HtmlEscapers;
import com.google.gson.Gson;
import com.palmergames.bukkit.towny.TownyUniverse;
import com.palmergames.bukkit.towny.TownySettings;
import com.palmergames.bukkit.towny.object.TownBlock;
import org.bukkit.World;
import java.util.ArrayList;
import java.util.Optional;
import com.palmergames.bukkit.towny.object.Nation;
import com.palmergames.bukkit.towny.object.Town;
import java.awt.Color;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import me.silverwolfg11.maptowny.MapTowny;

public final class MapModePublisher implements Runnable {
    private static final String RELIGIOUS_PLOTS = "religious_plots";
    private static final String WORLD = "world";
    private static final String X = "x";
    private static final String Z = "z";
    private static final String SIZE = "size";
    private static final String TOWN = "town";
    private static final String NATIONS = "nations";
    private static final String TOWNS = "towns";
    private static final String INDEPENDENT_COLOR = "independent_color";
    private static final String RELIGION_AVAILABLE = "religion_available";
    private static final String ID = "id";
    private static final String NAME = "name";
    private static final String COLOR = "color";
    private static final String ALLIES = "allies";
    private static final String ENEMIES = "enemies";
    private static final String NATION = "nation";
    private static final String RELIGION = "religion";
    private static final String RELIGION_PLACEHOLDER = "%religion%";
    private static final String EMPTY = "";
    private static final String COLOR_FORMAT = "#%06x";
    private static final String FALLBACK_COLOR = "#8b929c";
    private static final String EXPORT_ERROR = "Could not publish map modes";
    private static final Gson GSON = new Gson();
    private final MapTowny plugin;
    private final ReligionMapBridge religions;
    private CompletableFuture<Void> write = CompletableFuture.completedFuture(null);

    public MapModePublisher(MapTowny plugin) {
        this.plugin = plugin;
        this.religions = new ReligionMapBridge(plugin);
        plugin.getLayerManager().registerReplacement(RELIGION_PLACEHOLDER, this::religionName);
    }

    @Override
    public void run() {
        String json = GSON.toJson(capture());
        write = write.handle((ignored, failure) -> null).thenRunAsync(
                () -> publish(json), plugin.getScheduler().getAsyncExecutor());
    }

    private Map<String, Object> capture() {
        TownyUniverse universe = TownyUniverse.getInstance();
        List<Map<String, Object>> nations = universe.getNations().stream().map(this::describeNation).toList();
        Map<String, Object> towns = new HashMap<>();
        List<Map<String, Object>> plots = new ArrayList<>();
        for (Town town : universe.getTowns()) {
            if (!town.isRuined() || plugin.config().showRuinedTowns()) {
                towns.put(town.getUUID().toString(), describeTown(town));
                capturePlots(town, plots);
            }
        }
        return Map.of(NATIONS, nations, TOWNS, towns, RELIGION_AVAILABLE, religions.isAvailable(),
                INDEPENDENT_COLOR, formatColor(plugin.config().getDefaultFillColor()), RELIGIOUS_PLOTS, plots);
    }

    private void capturePlots(Town town, List<Map<String, Object>> plots) {
        int size = TownySettings.getTownBlockSize();
        for (TownBlock block : town.getTownBlocks()) {
            Optional<Map<String, String>> religion = religions.describePlot(block);
            if (religion.isEmpty())
                continue;
            World world = block.getWorld().getBukkitWorld();
            if (world != null && plugin.config().getEnabledWorlds().contains(world.getName())) {
                plots.add(Map.of(WORLD, world.getKey().toString(), X, block.getX() * size,
                        Z, block.getZ() * size, SIZE, size, TOWN, town.getName(), RELIGION, religion.get()));
            }
        }
    }

    private Map<String, Object> describeNation(Nation nation) {
        return Map.of(ID, nation.getUUID().toString(), NAME, nation.getName(),
                COLOR, formatColor(nation.getMapColor()), ALLIES, identifiers(nation.getAllies()),
                ENEMIES, identifiers(nation.getEnemies()));
    }

    private Map<String, Object> describeTown(Town town) {
        Nation nation = town.getNationOrNull();
        return Map.of(NAME, town.getName(), NATION, nation == null ? EMPTY : nation.getUUID().toString(),
                COLOR, formatColor(town.getMapColor()), RELIGION, religions.describe(town));
    }

    private String religionName(Town town) {
        Map<String, String> religion = religions.describe(town);
        String name = religion.get(NAME);
        return HtmlEscapers.htmlEscaper().escape(name);
    }

    private List<String> identifiers(Collection<Nation> nations) {
        return nations.stream().map(nation -> nation.getUUID().toString()).toList();
    }

    private String formatColor(Color color) {
        return color == null ? FALLBACK_COLOR : String.format(COLOR_FORMAT, color.getRGB() & 0xFFFFFF);
    }

    private void publish(String json) {
        try {
            plugin.getPlatform().publishMapModes(json);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, EXPORT_ERROR, exception);
        }
    }
}
