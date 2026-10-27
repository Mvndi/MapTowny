package me.silverwolfg11.maptowny.mapmode;

import com.palmergames.bukkit.towny.object.Town;
import com.palmergames.bukkit.towny.object.TownBlock;
import java.util.Optional;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.logging.Level;
import org.bukkit.plugin.Plugin;
import me.silverwolfg11.maptowny.MapTowny;

final class ReligionMapBridge {
    private static final String LAND_METHOD = "land";
    private static final String OWNER_METHOD = "owner";
    private static final String RELIGIOUS_PLOT_TYPE = "institution";
    private static final String PLUGIN = "MvndiReligion";
    private static final String SERVICE_METHOD = "religions";
    private static final String TOWN_METHOD = "townReligion";
    private static final String COLOR_METHOD = "mapColor";
    private static final String COLOR = "color";
    private static final String EMPTY = "";
    private static final String ACTIVE_METHOD = "active";
    private static final String NAME_METHOD = "name";
    private static final String NONE = "none";
    private static final String ID = "id";
    private static final String NAME = "name";
    private static final String ERROR = "Could not read public religion map information";
    private final MapTowny plugin;
    private boolean warned;

    ReligionMapBridge(MapTowny plugin) {
        this.plugin = plugin;
    }

    boolean isAvailable() {
        Plugin religion = plugin.getServer().getPluginManager().getPlugin(PLUGIN);
        if (religion == null || !religion.isEnabled()) {
            return false;
        }
        try {
            return (boolean) religion.getClass().getMethod(ACTIVE_METHOD).invoke(religion);
        } catch (ReflectiveOperationException exception) {
            return false;
        }
    }

    Map<String, String> describe(Town town) {
        if (!isAvailable()) {
            return Map.of(ID, NONE, NAME, NONE);
        }
        try {
            return readReligion(town);
        } catch (ReflectiveOperationException exception) {
            warn(exception);
            return Map.of(ID, NONE, NAME, NONE);
        }
    }

    Optional<Map<String, String>> describePlot(TownBlock block) {
        if (!RELIGIOUS_PLOT_TYPE.equals(block.getTypeName()) || !isAvailable())
            return Optional.empty();
        try {
            Plugin religion = plugin.getServer().getPluginManager().getPlugin(PLUGIN);
            Object land = religion.getClass().getMethod(LAND_METHOD).invoke(religion);
            String owner = (String) land.getClass().getMethod(OWNER_METHOD, TownBlock.class).invoke(land, block);
            if (owner.isBlank())
                return Optional.empty();
            Object service = religion.getClass().getMethod(SERVICE_METHOD).invoke(religion);
            return Optional.of(describeReligion(service, owner));
        } catch (ReflectiveOperationException exception) {
            warn(exception);
            return Optional.empty();
        }
    }

    private void warn(ReflectiveOperationException exception) {
        if (!warned) {
            plugin.getLogger().log(Level.WARNING, ERROR, exception);
            warned = true;
        }
    }

    private Map<String, String> readReligion(Town town) throws ReflectiveOperationException {
        Plugin religion = plugin.getServer().getPluginManager().getPlugin(PLUGIN);
        Method serviceMethod = religion.getClass().getMethod(SERVICE_METHOD);
        Object service = serviceMethod.invoke(religion);
        Method townMethod = service.getClass().getMethod(TOWN_METHOD, Town.class);
        String id = (String) townMethod.invoke(service, town);
        return describeReligion(service, id);
    }

    private Map<String, String> describeReligion(Object service, String id) throws ReflectiveOperationException {
        Method nameMethod = service.getClass().getMethod(NAME_METHOD, String.class);
        String name = (String) nameMethod.invoke(service, id);
        return Map.of(ID, id, NAME, name, COLOR, readColor(service, id));
    }
    private String readColor(Object service, String id) throws ReflectiveOperationException {
        try {
            Method method = service.getClass().getMethod(COLOR_METHOD, String.class);
            return (String) method.invoke(service, id);
        } catch (NoSuchMethodException exception) {
            return EMPTY;
        }
    }

}
