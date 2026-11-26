package WayFarMap.client.map;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.world.biome.BiomeGenBase;

import WayFarMap.WayFarMap;

/**
 * Which biome each value of a biome map's extra byte stands for. The byte holds 1-255 (0 = unknown), so a biome with
 * an id up to 254 is stored as its id + 1, as always; a biome with a higher id (mods extending biome ids, like
 * EndlessIDs) gets a free value of its own, written to {@code biome-palette.txt} in the map's folder. Maps from before
 * read the same, and a world rarely sees more than a few hundred biomes.
 * <p>
 * A free value is one no biome of the game has as id + 1. A biome registered there later (a pack update) gets a free
 * value of its own the same way, since the palette keeps the value taken.
 */
public final class BiomePalette {

    private static final String FILE = "biome-palette.txt";
    /** One per folder: the map and the team map's upload use the same. */
    private static final Map<String, BiomePalette> PALETTES = new HashMap<>();

    private final File file;
    /** The biome of each value the palette took, null for one whose biome is gone from the game. */
    private final BiomeGenBase[] byValue = new BiomeGenBase[256];
    private final boolean[] taken = new boolean[256];
    private final Map<BiomeGenBase, Integer> values = new IdentityHashMap<>();
    private boolean loaded;

    private BiomePalette(File directory) {
        this.file = new File(directory, FILE);
    }

    /** The palette of the biome map in the folder. */
    public static BiomePalette of(File directory) {
        String key = directory.getAbsolutePath();
        synchronized (PALETTES) {
            return PALETTES.computeIfAbsent(key, k -> new BiomePalette(directory));
        }
    }

    /** The value to store for the biome, 1-255; 0 when all 255 are taken (it shows as unknown). */
    public synchronized int valueOf(BiomeGenBase biome) {
        if (biome == null) {
            return 0;
        }
        load();
        Integer value = values.get(biome);
        if (value != null) {
            return value;
        }
        int id = biome.biomeID;
        if (id >= 0 && id < 255 && !taken[id + 1]) {
            return id + 1;
        }
        BiomeGenBase[] biomes = BiomeGenBase.getBiomeGenArray();
        for (int v = 255; v >= 1; v--) {
            if (!taken[v] && (v - 1 >= biomes.length || biomes[v - 1] == null)) {
                take(v, biome);
                append(v, biome);
                return v;
            }
        }
        return 0;
    }

    /** The biome a stored value stands for, null for unknown. */
    public synchronized BiomeGenBase biome(int value) {
        if (value <= 0 || value > 255) {
            return null;
        }
        load();
        if (taken[value]) {
            return byValue[value];
        }
        BiomeGenBase[] biomes = BiomeGenBase.getBiomeGenArray();
        return value - 1 < biomes.length ? biomes[value - 1] : null;
    }

    /** The id a stored value's biome has in the game, for teammates (who have palettes of their own); -1 if none. */
    public int biomeId(int value) {
        BiomeGenBase biome = biome(value);
        return biome == null ? -1 : biome.biomeID;
    }

    private void take(int value, BiomeGenBase biome) {
        taken[value] = true;
        byValue[value] = biome;
        if (biome != null) {
            values.put(biome, value);
        }
    }

    /** Reads the file: lines "value id name". A biome found by its id, or by its name when the ids moved. */
    private void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!file.isFile()) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            BiomeGenBase[] biomes = BiomeGenBase.getBiomeGenArray();
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split(" ", 3);
                if (parts.length < 3) {
                    continue;
                }
                int value, id;
                try {
                    value = Integer.parseInt(parts[0]);
                    id = Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    continue;
                }
                if (value < 1 || value > 255) {
                    continue;
                }
                String name = parts[2];
                BiomeGenBase biome = id >= 0 && id < biomes.length ? biomes[id] : null;
                if (biome == null || !name.equals(biome.biomeName)) {
                    biome = null;
                    for (BiomeGenBase b : biomes) {
                        if (b != null && name.equals(b.biomeName)) {
                            biome = b;
                            break;
                        }
                    }
                }
                take(value, biome);
            }
        } catch (IOException e) {
            WayFarMap.LOG.warn("Could not read the biome palette {}: {}", file, e.toString());
        }
    }

    private void append(int value, BiomeGenBase biome) {
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory()) {
                dir.mkdirs();
            }
            StringBuilder text = new StringBuilder();
            if (!file.isFile()) {
                text.append("# Biomes with ids above 254 and the value the biome map stores for them: value id name\n");
            }
            text.append(value)
                .append(' ')
                .append(biome.biomeID)
                .append(' ')
                .append(biome.biomeName)
                .append('\n');
            Files.write(
                file.toPath(),
                text.toString()
                    .getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        } catch (IOException e) {
            WayFarMap.LOG.warn("Could not write the biome palette {}: {}", file, e.toString());
        }
    }
}
