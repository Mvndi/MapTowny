package me.silverwolfg11.maptowny.managers;

import com.gmail.goosius.siegewar.SiegeController;
import com.gmail.goosius.siegewar.enums.SiegeSide;
import com.gmail.goosius.siegewar.objects.BattleSession;
import com.gmail.goosius.siegewar.objects.Siege;
import me.silverwolfg11.maptowny.MapTowny;
import me.silverwolfg11.maptowny.objects.MarkerOptions;
import me.silverwolfg11.maptowny.objects.Point2D;
import me.silverwolfg11.maptowny.platform.MapLayer;
import me.silverwolfg11.maptowny.platform.MapPlatform;
import me.silverwolfg11.maptowny.platform.MapWorld;
import org.bukkit.Location;
import org.bukkit.World;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class SiegeLayer {
    private static final String LAYER_KEY = "siegewar";
    private static final String FIRE_ICON = "siegewar_fire";
    private static final String BATTLE_ICON = "siegewar_battle";
    private static final String MARKER_PREFIX = "siege_";

    private final MapTowny plugin;
    private final MapPlatform platform;
    private final Map<String, MapLayer> layers = new HashMap<>();
    private volatile boolean closed;

    SiegeLayer(MapTowny plugin, MapPlatform platform) {
        this.plugin = plugin;
        this.platform = platform;
        int size = plugin.config().getSiegeIconSize();
        platform.registerIcon(FIRE_ICON, SiegeIcons.createFire(), size, size);
        platform.registerIcon(BATTLE_ICON, SiegeIcons.createSwords(), size, size);
        registerWorlds();
        loadCustomIcons();
    }

    private void loadCustomIcons() {
        SiegeIconLoader loader = new SiegeIconLoader(plugin.getDataFolder(), plugin.getLogger());
        String dormantSource = plugin.config().getSiegeDormantIcon();
        String battleSource = plugin.config().getSiegeBattleIcon();
        plugin.getScheduler().scheduleAsyncTask(() -> {
            BufferedImage dormant = loader.loadImage(dormantSource, SiegeIcons.createFire());
            BufferedImage battle = loader.loadImage(battleSource, SiegeIcons.createSwords());
            if (!closed && plugin.isEnabled())
                plugin.getScheduler().scheduleTask(() -> replaceIcons(dormant, battle));
        });
    }

    private void replaceIcons(BufferedImage dormant, BufferedImage battle) {
        if (closed)
            return;
        int size = plugin.config().getSiegeIconSize();
        platform.unregisterIcon(FIRE_ICON);
        platform.unregisterIcon(BATTLE_ICON);
        platform.registerIcon(FIRE_ICON, dormant, size, size);
        platform.registerIcon(BATTLE_ICON, battle, size, size);
    }

    private void registerWorlds() {
        for (String name : plugin.config().getEnabledWorlds()) {
            World world = plugin.getServer().getWorld(name);
            if (world == null || !platform.isWorldEnabled(world))
                continue;
            MapWorld mapWorld = platform.getWorld(world);
            if (mapWorld != null)
                layers.put(name, mapWorld.registerLayer(LAYER_KEY, plugin.config().getSiegeLayerOptions()));
        }
    }

    void refresh() {
        Set<String> current = new HashSet<>();
        for (Siege siege : SiegeController.getSieges()) {
            if (!siege.getStatus().isActive())
                continue;
            Location location = siege.getFlagLocation();
            World world = location.getWorld();
            if (world != null && layers.containsKey(world.getName())) {
                String key = MARKER_PREFIX + siege.getTown().getUUID();
                current.add(world.getName() + key);
                renderSiege(siege, layers.get(world.getName()));
            }
        }
        layers.forEach((world, layer) -> layer.removeMarkers(key -> !current.contains(world + key)));
    }

    private void renderSiege(Siege siege, MapLayer layer) {
        String key = MARKER_PREFIX + siege.getTown().getUUID();
        String icon = isDormant(siege) ? FIRE_ICON : BATTLE_ICON;
        int size = plugin.config().getSiegeIconSize();
        MarkerOptions options = SiegeTooltip.buildOptions(siege);
        layer.addIconMarker(key, icon, Point2D.of(siege.getFlagLocation()), size, size, options);
    }

    private boolean isDormant(Siege siege) {
        return !BattleSession.getBattleSession().isActive()
                || (siege.getAttackerBattlePoints() == 0 && siege.getDefenderBattlePoints() == 0
                && siege.getBannerControllingSide() == SiegeSide.NOBODY
                && siege.getBannerControlSessions().isEmpty());
    }

    void close() {
        closed = true;
        for (String name : layers.keySet()) {
            World world = plugin.getServer().getWorld(name);
            if (world == null)
                continue;
            MapWorld mapWorld = platform.getWorld(world);
            if (mapWorld != null)
                mapWorld.unregisterLayer(LAYER_KEY);
        }
        layers.clear();
        platform.unregisterIcon(FIRE_ICON);
        platform.unregisterIcon(BATTLE_ICON);
    }
}
