package me.silverwolfg11.maptowny.mapmode;

import com.palmergames.bukkit.towny.object.Town;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.logging.Level;
import org.bukkit.plugin.Plugin;
import me.silverwolfg11.maptowny.MapTowny;

final class ReligionMapBridge {
    private static final String PLUGIN = "MvndiReligion";
    private static final String SERVICE_METHOD = "religions";
    private static final String TOWN_METHOD = "townReligion";
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
        return plugin.getServer().getPluginManager().isPluginEnabled(PLUGIN);
    }

    Map<String, String> describe(Town town) {
        if (!isAvailable()) {
            return Map.of(ID, NONE, NAME, NONE);
        }
        try {
            return readReligion(town);
        } catch (ReflectiveOperationException exception) {
            if (!warned) {
                plugin.getLogger().log(Level.WARNING, ERROR, exception);
                warned = true;
            }
            return Map.of(ID, NONE, NAME, NONE);
        }
    }

    private Map<String, String> readReligion(Town town) throws ReflectiveOperationException {
        Plugin religion = plugin.getServer().getPluginManager().getPlugin(PLUGIN);
        Method serviceMethod = religion.getClass().getMethod(SERVICE_METHOD);
        Object service = serviceMethod.invoke(religion);
        Method townMethod = service.getClass().getMethod(TOWN_METHOD, Town.class);
        String id = (String) townMethod.invoke(service, town);
        Method nameMethod = service.getClass().getMethod(NAME_METHOD, String.class);
        String name = (String) nameMethod.invoke(service, id);
        return Map.of(ID, id, NAME, name);
    }
}
