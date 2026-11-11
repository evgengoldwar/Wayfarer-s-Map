package WayFarMap.client.map;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.server.integrated.IntegratedServer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import WayFarMap.WayFarMap;
import WayFarMap.client.waypoint.Waypoint;
import WayFarMap.client.waypoint.WaypointManager;

/**
 * Copies a single player world's JourneyMap map and waypoints into this mod's, once, the first time the world is
 * played with it: {@code journeymap/data/sp/<world>/DIM0/day/<rx>,<rz>.png} are regions of 512 x 512 blocks, one
 * pixel a block, like this mod's, and {@code waypoints/*.json} one waypoint each. Only the Overworld's map: the other
 * dimensions JourneyMap keeps as slices by height (the Nether), or are not the surface this map shows; the waypoints
 * of every dimension are copied. Pixels this map has already are
 * kept; JourneyMap's only fill what it hasn't explored. Waypoints go into a "JourneyMap" group, those already there
 * (same name, place and dimension) are not added again.
 * <p>
 * What was copied is kept in a file of the world folder until the world map has said so ({@link #takeNotice}).
 */
public final class JourneyMapImport {

    /** Written in the account's world folder once JourneyMap's data was looked at: not copied again. */
    private static final String DONE_FILE = "journeymap-imported";
    /** What was copied, until the world map has told the player: "regions;waypoints;folder". */
    private static final String NOTICE_FILE = "journeymap-import-notice";
    public static final String GROUP = "JourneyMap";

    private static final Pattern REGION = Pattern.compile("(-?\\d+),(-?\\d+)\\.png");

    private JourneyMapImport() {}

    /**
     * Copies JourneyMap's data of this single player world if it has some and it was not copied before. Before the
     * maps of the world are opened (their files are written here), after its waypoints are loaded.
     */
    public static void run(Minecraft mc, File worldDirectory) {
        IntegratedServer server = mc.getIntegratedServer();
        if (!mc.isSingleplayer() || server == null || new File(worldDirectory, DONE_FILE).exists()) {
            return;
        }
        File source = findWorld(mc, server);
        if (source == null) {
            // No JourneyMap data (yet): looked for again next time, in case it is copied in later.
            return;
        }
        long start = System.nanoTime();
        int regions = 0, waypoints = 0;
        try {
            regions = copyMaps(source, worldDirectory);
        } catch (RuntimeException e) {
            WayFarMap.LOG.warn("Could not copy the JourneyMap map from {}", source, e);
        }
        try {
            waypoints = copyWaypoints(new File(source, "waypoints"));
        } catch (RuntimeException e) {
            WayFarMap.LOG.warn("Could not copy the JourneyMap waypoints from {}", source, e);
        }
        WayFarMap.LOG.info(
            "Copied from JourneyMap ({}): {} regions of the map, {} waypoints, in {} ms",
            source,
            regions,
            waypoints,
            (System.nanoTime() - start) / 1_000_000);
        try {
            worldDirectory.mkdirs();
            Files.write(
                new File(worldDirectory, DONE_FILE).toPath(),
                source.getName()
                    .getBytes(StandardCharsets.UTF_8));
            if (regions > 0 || waypoints > 0) {
                String notice = regions + ";" + waypoints + ";" + source.getName();
                Files.write(new File(worldDirectory, NOTICE_FILE).toPath(), notice.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            WayFarMap.LOG.warn("Could not save that JourneyMap's data was copied to {}", worldDirectory, e);
        }
    }

    /**
     * What was copied, for the world map to tell once, then forgotten: {regions, waypoints} and JourneyMap's folder
     * name; null if nothing is left to tell.
     */
    public static Object[] takeNotice(File worldDirectory) {
        if (worldDirectory == null) {
            return null;
        }
        File file = new File(worldDirectory, NOTICE_FILE);
        if (!file.isFile()) {
            return null;
        }
        Object[] notice = null;
        try {
            String[] parts = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).split(";", 3);
            notice = new Object[] { Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()),
                parts.length > 2 ? parts[2] : "" };
        } catch (IOException | RuntimeException e) {
            WayFarMap.LOG.warn("Could not read {}", file, e);
        }
        if (!file.delete()) {
            WayFarMap.LOG.warn("Could not delete {}", file);
        }
        return notice;
    }

    /**
     * JourneyMap's folder of the world: named as the world's folder in {@code saves}, or as the world (they differ
     * when the name was taken); null if neither is there.
     */
    private static File findWorld(Minecraft mc, IntegratedServer server) {
        File sp = new File(new File(new File(mc.mcDataDir, "journeymap"), "data"), "sp");
        Set<String> names = new LinkedHashSet<>();
        names.add(server.getFolderName());
        if (server.worldServers != null && server.worldServers.length > 0 && server.worldServers[0] != null) {
            names.add(
                server.worldServers[0].getWorldInfo()
                    .getWorldName());
        }
        for (String name : names) {
            if (name == null || name.isEmpty()) {
                continue;
            }
            File world = new File(sp, name);
            if (world.isDirectory()) {
                return world;
            }
        }
        return null;
    }

    /** The Overworld's surface map ({@code DIM0/day}); returns how many regions got something. */
    private static int copyMaps(File source, File worldDirectory) {
        File[] images = new File(new File(source, "DIM0"), "day").listFiles();
        if (images == null) {
            return 0;
        }
        int copied = 0;
        File target = new File(worldDirectory, "dim0");
        for (File image : images) {
            Matcher rm = REGION.matcher(image.getName());
            if (!rm.matches()) {
                continue;
            }
            try {
                if (copyRegion(image, target, Integer.parseInt(rm.group(1)), Integer.parseInt(rm.group(2)))) {
                    copied++;
                }
            } catch (IOException | RuntimeException e) {
                WayFarMap.LOG.warn("Could not copy the JourneyMap region {}", image, e);
            }
        }
        return copied;
    }

    /** Fills what this map hasn't explored of the region from JourneyMap's picture; whether anything was. */
    private static boolean copyRegion(File image, File target, int rx, int rz) throws IOException {
        BufferedImage picture = ImageIO.read(image);
        if (picture == null || picture.getWidth() != MapRegion.SIZE || picture.getHeight() != MapRegion.SIZE) {
            return false;
        }
        int[] colors = picture.getRGB(0, 0, MapRegion.SIZE, MapRegion.SIZE, null, 0, MapRegion.SIZE);
        File file = MapRegion.getFile(target, rx, rz);
        MapRegion region = file.isFile() ? MapRegion.read(file, rx, rz) : new MapRegion(rx, rz);
        long time = Math.max(1, image.lastModified());
        boolean any = false;
        boolean[] chunks = new boolean[MapRegion.CHUNKS * MapRegion.CHUNKS];
        for (int z = 0; z < MapRegion.SIZE; z++) {
            for (int x = 0; x < MapRegion.SIZE; x++) {
                int color = colors[z * MapRegion.SIZE + x];
                // JourneyMap leaves what it hasn't seen see-through.
                if (color >>> 24 < 128 || region.getPixel(x, z) >>> 24 != 0) {
                    continue;
                }
                region.setPixel(x, z, 0xFF000000 | color);
                chunks[(z >> 4) * MapRegion.CHUNKS + (x >> 4)] = true;
                any = true;
            }
        }
        if (!any) {
            return false;
        }
        for (int cz = 0; cz < MapRegion.CHUNKS; cz++) {
            for (int cx = 0; cx < MapRegion.CHUNKS; cx++) {
                if (chunks[cz * MapRegion.CHUNKS + cx] && region.getChunkTime(cx, cz) == 0) {
                    // On the map from now: the area loading view shows it mapped, not to be loaded.
                    region.setChunkTime(cx, cz, time);
                }
            }
        }
        target.mkdirs();
        MapRegion.write(file, region.snapshotForSave(), new MapRegion.IoTrace());
        return true;
    }

    /** JourneyMap's waypoints, one file each; returns how many were added. */
    private static int copyWaypoints(File folder) {
        File[] files = folder.listFiles(
            file -> file.isFile() && file.getName()
                .endsWith(".json"));
        if (files == null || files.length == 0) {
            return 0;
        }
        WaypointManager manager = WaypointManager.INSTANCE;
        List<Waypoint> added = new ArrayList<>();
        for (File file : files) {
            JsonObject json;
            try (Reader reader = new InputStreamReader(Files.newInputStream(file.toPath()), StandardCharsets.UTF_8)) {
                JsonElement element = new JsonParser().parse(reader);
                if (!element.isJsonObject()) {
                    continue;
                }
                json = element.getAsJsonObject();
            } catch (IOException | RuntimeException e) {
                WayFarMap.LOG.warn("Could not read the JourneyMap waypoint {}", file, e);
                continue;
            }
            String name = string(json, "name", "");
            int x = number(json, "x"), y = number(json, "y"), z = number(json, "z");
            int color = (number(json, "r") & 0xFF) << 16 | (number(json, "g") & 0xFF) << 8 | number(json, "b") & 0xFF;
            boolean enabled = !json.has("enable") || json.get("enable")
                .getAsBoolean();
            List<Integer> dimensions = new ArrayList<>();
            JsonElement dims = json.get("dimensions");
            if (dims != null && dims.isJsonArray()) {
                for (JsonElement dim : (JsonArray) dims) {
                    dimensions.add(dim.getAsInt());
                }
            }
            if (dimensions.isEmpty()) {
                dimensions.add(0);
            }
            for (int dimension : dimensions) {
                if (exists(manager.getWaypoints(), name, x, y, z, dimension)
                    || exists(added, name, x, y, z, dimension)) {
                    continue;
                }
                Waypoint waypoint = new Waypoint(name, x, y, z, dimension);
                waypoint.outlineColor = color;
                waypoint.enabled = enabled;
                waypoint.group = GROUP;
                added.add(waypoint);
            }
        }
        if (added.isEmpty()) {
            return 0;
        }
        manager.createGroup(GROUP);
        manager.addWaypoints(added);
        return added.size();
    }

    private static boolean exists(List<Waypoint> waypoints, String name, int x, int y, int z, int dimension) {
        for (Waypoint w : waypoints) {
            if (w.x == x && w.y == y && w.z == z && w.dimension == dimension && name.equals(w.name)) {
                return true;
            }
        }
        return false;
    }

    private static String string(JsonObject json, String key, String fallback) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }

    private static int number(JsonObject json, String key) {
        JsonElement value = json.get(key);
        try {
            return value != null && value.isJsonPrimitive() ? value.getAsInt() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
