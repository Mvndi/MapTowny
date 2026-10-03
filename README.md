# MapTowny
A Towny add-on PaperMC plugin that enables towns to show up on a web-map.
* Requires Java 17 (if using squaremap or dynmap) or Java 21 (if using Pl3xMap or BlueMap)

**Supported web-map plugins are Pl3xMap, squaremap, dynmap, and BlueMap**.

## Dependencies
This plugin requires Towny to be installed on your server.
- [Towny](https://github.com/TownyAdvanced/Towny) 0.101.2.0 (or later)

The plugin also requires one of the following web-map plugins to be installed on your server:
- [Pl3xMap](https://github.com/granny/Pl3xMap) 1.21.5 (or later)
- [squaremap](https://github.com/jpenilla/squaremap) 1.3.9 (or later)
- [dynmap](https://www.spigotmc.org/resources/dynmap%C2%AE.274/) 3.7-beta-6 (or later)
- [BlueMap](https://www.spigotmc.org/resources/bluemap.83557/) v5.12 (or later) (limited support)

## Features
Honestly, nothing uber special about this plugin other than it works, but if you really want to know:
- Configurable Marker Options
- Async Processing
- Support multiple web-map plugins
- Unit-tested Custom Polygon Outline (contour) Algorithm and Negative Space (contour "hole-finding") Algorithm

## Installing
Install the plugin from the [releases page](https://github.com/TownyAdvanced/MapTowny/releases). Simply put the jar file into your `plugins` directory and start your server.

## Usage and Configuration
The plugin should be ready for use out of the box. The one thing that may need to be adjusted is the `enabled-worlds` property in the `config.yml` to add the world names that you want town claims to show up on. For more information about the plugin's commands and configuring the plugin, see the [wiki](https://github.com/TownyAdvanced/MapTowny/wiki).

Deep-ocean biomes are excluded using the actual `minecraft:is_deep_ocean` and
`mvndi:is_deep_ocean` tags, including custom biomes in those tags.
`nation-protection.excluded-biome-tags` accepts biome tags or individual biome
keys; set it to `[]` to include all biomes. Filtering samples the centre of each
townblock at `biome-sample-y`, so edges follow townblocks rather than coastlines.
Changes to the resolved tag contents invalidate cached geometry automatically.

## Nation protection cache

`nation-protection.use-nation-map-color` defaults to `true`: each nation's buffer
uses its nation map colour for both fill and outline. Towns without a nation (or
nations without a map colour) use the configured default colour. Set it to
`false` for a single-colour overlay. Reload with `/maptowny reload` after editing
this setting. Overlapping nation buffers may blend their translucent fills.

The protection overlay saves compressed polygon geometry in
`plugins/MapTowny/nation-protection-cache/`. Matching claims, world UUID/seed,
claim-spacing radius, townblock size, biome sampling height, and biome exclusions
reuse the cache after restarting or reloading, without another biome scan.
Cached groups appear as soon as they load, while missing or invalid groups rebuild
in the background. Cache IO runs off-thread;
files are replaced atomically, and corrupt files are ignored and rebuilt.

The first render still needs the gradual biome scan. Nation colours and opacity apply to cached polygons without another biome scan.
Nation-coloured geometry is cached separately for each nation; membership changes
rebuild the affected geometry. Claims outside a nation's buffer do not invalidate
its cache. This cache-key change requires one rebuild of older nation caches. If terrain
or its biome generator changes without changing the world UUID/seed, delete the
cache directory and run `/maptowny reload` to rebuild it.

## Plugin API:
See [this wiki page](https://github.com/TownyAdvanced/MapTowny/wiki/MapTowny-API) for more info.

## Building
This plugin is a standard Maven project that uses the [Maven Toolchains Plugin](https://maven.apache.org/plugins/maven-toolchains-plugin/) to build modules against multiple JDK versions.
JDK versions 17 and 21 are required to build the project.
* Note: The devcontainer already installs both.

The project will attempt to auto-detect paths to the JDKs, however for non-standard paths, a `toolchains.xml` file in your `.m2` folder is recommended.
See [this guide](https://maven.apache.org/guides/mini/guide-using-toolchains.html#using-toolchains-in-your-project) for more info on setting up a `toolchains.xml` file.

Once Maven and the JDK versions are installed, the project can be compiled with the command `mvn clean package` or `mvn clean install` ran in the project directory.

## Licensing
This plugin is licensed under the MIT license. While highly permissible, I do kindly hope that you create pull-requests for any bug fixes or useful features so they can help the entire community.

\*All pull-requests made to the repository agree to license the code contributed under the MIT license.
