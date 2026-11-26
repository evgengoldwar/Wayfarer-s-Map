package WayFarMap.client.map;

import net.minecraft.block.Block;
import net.minecraft.block.BlockLog;
import net.minecraft.block.material.Material;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.chunk.Chunk;

import WayFarMap.Config;

/** Turns a loaded chunk into map pixels. */
public final class ChunkScanner {

    private static final int NO_BLOCK = -1;

    private ChunkScanner() {}

    /** A chunk the client actually has block data for. */
    public static boolean isChunkReady(World world, int cx, int cz) {
        if (!world.getChunkProvider()
            .chunkExists(cx, cz)) {
            return false;
        }
        Chunk chunk = world.getChunkFromChunkCoords(cx, cz);
        return chunk != null && !chunk.isEmpty();
    }

    /**
     * Draws the chunk into the map.
     *
     * @param caveLayer -1 for the surface, otherwise the cave layer (blocks {@code layer * 16} to
     *                  {@code layer * 16 + 15}) whose floor is drawn
     */
    public static void scan(World world, Chunk chunk, MapDimension dimension, int caveLayer) {
        scan(world, chunk, dimension, caveLayer, null);
    }

    /**
     * Same as {@link #scan(World, Chunk, MapDimension, int)}, also drawing the biome of each column into
     * {@code biomeMap} when it is not null (surface only).
     */
    public static void scan(World world, Chunk chunk, MapDimension dimension, int caveLayer, MapDimension biomeMap) {
        int cx = chunk.xPosition;
        int cz = chunk.zPosition;
        boolean noSky = world.provider.hasNoSky;

        // Heights of the chunk and of the two rows north and west of it (from the neighbouring chunks if loaded),
        // for shading across chunk borders: heights[lx + 2][lz + 2] for lx, lz from -2 to 15.
        int[][] heights = new int[18][18];
        Chunk north = neighbour(world, cx, cz - 1);
        Chunk west = neighbour(world, cx - 1, cz);
        Chunk northWest = neighbour(world, cx - 1, cz - 1);
        for (int i = -2; i < 16; i++) {
            for (int j = -2; j < 16; j++) {
                Chunk source = i >= 0 ? (j >= 0 ? chunk : north) : (j >= 0 ? west : northWest);
                heights[i + 2][j + 2] = source == null ? NO_BLOCK : findTop(source, i & 15, j & 15, noSky, caveLayer);
            }
        }

        MapRegion region = dimension.getRegion((cx * 16) >> MapRegion.SHIFT, (cz * 16) >> MapRegion.SHIFT, true);
        int baseX = (cx * 16) & (MapRegion.SIZE - 1);
        int baseZ = (cz * 16) & (MapRegion.SIZE - 1);
        MapRegion biomeRegion = biomeMap == null ? null
            : biomeMap.getRegion((cx * 16) >> MapRegion.SHIFT, (cz * 16) >> MapRegion.SHIFT, true);
        // The surface and the cave layers are also drawn without grass and flowers, into their own map.
        MapDimension plantlessMap = dimension.plantless();
        MapRegion plantlessRegion = plantlessMap == null ? null
            : plantlessMap.getRegion((cx * 16) >> MapRegion.SHIFT, (cz * 16) >> MapRegion.SHIFT, true);

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int y = heights[lx + 2][lz + 2];
                int northHeight = heights[lx + 2][lz + 1];
                int argb = 0, plantlessArgb = 0;
                // Light relief for the biome map: a step up from the north is lighter, a step down darker.
                float biomeRelief = 1f;
                if (y != NO_BLOCK && northHeight != NO_BLOCK) {
                    biomeRelief = 1.0f + Math.max(-4, Math.min(4, y - northHeight)) * 0.03f;
                }
                if (y != NO_BLOCK) {
                    int blockRgb = columnColor(world, chunk, lx, y, lz);
                    int rgb = detailedColor(world, chunk, heights, lx, y, lz, blockRgb, true);
                    int plantlessRgb = plantlessRegion == null ? 0
                        : detailedColor(world, chunk, heights, lx, y, lz, blockRgb, false);
                    if (caveLayer >= 0) {
                        // Deeper floors (below the layer) get darker, so drops read as depth; a cut through blocks
                        // filling the whole layer (a machine standing on the floor, a hill) is darker still.
                        int below = Math.max(0, caveLayer * 16 - y);
                        float depth = isCut(chunk, lx, y, lz, caveLayer) ? CUT_SHADE
                            : Math.max(0.4f, 1.0f - below * 0.02f);
                        rgb = BlockColors.shade(rgb, depth);
                        plantlessRgb = BlockColors.shade(plantlessRgb, depth);
                    }
                    argb = 0xFF000000 | rgb;
                    if (plantlessRegion != null) {
                        plantlessArgb = 0xFF000000 | plantlessRgb;
                    }
                }
                int height = y == NO_BLOCK ? 0 : Math.min(255, y + 1);
                if (caveLayer < 0) {
                    // The surface also remembers the height to stand on, for teleporting.
                    region.setPixel(baseX + lx, baseZ + lz, argb, height);
                } else {
                    region.setPixel(baseX + lx, baseZ + lz, argb);
                }
                // Torches and other lights: the map glows there at night (under glass, those lighting the floor).
                int light = y == NO_BLOCK ? 0 : blockLight(chunk, lx, seenThroughGlass(chunk, lx, y, lz), lz);
                region.setLight(baseX + lx, baseZ + lz, light);
                if (plantlessRegion != null && caveLayer >= 0) {
                    plantlessRegion.setPixel(baseX + lx, baseZ + lz, plantlessArgb);
                    plantlessRegion.setLight(baseX + lx, baseZ + lz, light);
                } else if (plantlessRegion != null) {
                    // The map without plants keeps the ground for the topography instead: under trees, the floor
                    // of water, and whether it is water or lava.
                    int topo = y == NO_BLOCK ? 0 : groundTopo(chunk, lx, y, lz);
                    plantlessRegion.setPixel(baseX + lx, baseZ + lz, plantlessArgb, topo & 0xFF);
                    plantlessRegion.setLight(baseX + lx, baseZ + lz, light | topo >> 8);
                }
                if (biomeRegion != null) {
                    BiomeGenBase biome = chunk.getBiomeGenForWorldCoords(lx, lz, world.getWorldChunkManager());
                    int biomeArgb = biome == null ? 0 : 0xFF000000 | BlockColors.shade(biomeColor(biome), biomeRelief);
                    int biomeId = biome == null || biome.biomeID >= 255 ? 0 : biome.biomeID + 1;
                    biomeRegion.setPixel(baseX + lx, baseZ + lz, biomeArgb, biomeId);
                }
            }
        }
    }

    /**
     * The ground under the column's top block {@code y}, for the topography: leaves, logs, snow layers and ice on
     * water are looked through, water down to its floor. Returns the height of the ground (or of the water's floor)
     * plus one in the low byte and the topography flags of MapRegion above it.
     */
    private static int groundTopo(Chunk chunk, int lx, int y, int lz) {
        while (y > 0) {
            Block block = chunk.getBlock(lx, y, lz);
            boolean iceOnWater = block.getMaterial() == Material.ice && chunk.getBlock(lx, y - 1, lz)
                .getMaterial() == Material.water;
            if (isVisible(block) && !isTreeOrSnow(block) && !iceOnWater) {
                break;
            }
            y--;
        }
        Material material = chunk.getBlock(lx, y, lz)
            .getMaterial();
        int flags = MapRegion.TOPO_KNOWN;
        if (material == Material.lava) {
            flags |= MapRegion.TOPO_LAVA;
        } else if (material == Material.water) {
            flags |= MapRegion.TOPO_WATER;
            while (y > 0 && chunk.getBlock(lx, y, lz)
                .getMaterial() == Material.water) {
                y--;
            }
        }
        return flags << 8 | Math.max(1, Math.min(255, y + 1));
    }

    /** Not the ground: leaves, logs and thin snow on top. */
    private static boolean isTreeOrSnow(Block block) {
        Material material = block.getMaterial();
        return material == Material.leaves || material == Material.snow || block instanceof BlockLog;
    }

    /** Light from blocks at the surface: in the space above it, or given off by the block itself (lava, glowstone). */
    private static int blockLight(Chunk chunk, int lx, int y, int lz) {
        int above = y < 255 ? chunk.getSavedLightValue(EnumSkyBlock.Block, lx, y + 1, lz) : 0;
        int own = chunk.getBlock(lx, y, lz)
            .getLightValue();
        return Math.max(0, Math.min(15, Math.max(above, own)));
    }

    private static Chunk neighbour(World world, int cx, int cz) {
        return isChunkReady(world, cx, cz) ? world.getChunkFromChunkCoords(cx, cz) : null;
    }

    // JourneyMap's shading ("black magic that serves as the stand-in for true bump-mapping"), same numbers.
    private static final int[][] PRIMARY_SLOPE = { { 0, -1 }, { -1, -1 }, { -1, 0 } };
    private static final int[][] SECONDARY_SLOPE = { { -1, -2 }, { -2, -1 }, { -2, -2 }, { -2, 0 }, { 0, -2 } };
    private static final float DOWNSLOPE = 0.65f, UPSLOPE = 1.2f;
    private static final float SECONDARY_DOWNSLOPE = 0.95f, SECONDARY_UPSLOPE = 1.05f;
    private static final float SLOPE_MIN = 0.2f, SLOPE_MAX = 1.7f;
    /** Daylight brightening of the surface, like JourneyMap's day map. */
    private static final float DAYLIGHT = 1.06f;

    /**
     * The JourneyMap look of a column: a plant, crop, rail or redstone on the block is drawn instead of
     * it (without a bevel, like JourneyMap without plant shadows); otherwise the block is beveled by its slope to the
     * north-west, with shadows turning a little blue.
     *
     * @param plants false to leave out grass and flowers (the block under them is drawn)
     */
    private static int detailedColor(World world, Chunk chunk, int[][] heights, int lx, int y, int lz, int rgb,
        boolean plants) {
        int plant = plantColor(world, chunk, lx, y, lz, plants);
        if (plant < 0) {
            int seen = seenThroughGlass(chunk, lx, y, lz);
            if (seen != y) {
                plant = plantColor(world, chunk, lx, seen, lz, plants);
                if (plant >= 0) {
                    // Plants under glass replace the ground too, keeping the roof's tint.
                    Block glass = chunk.getBlock(lx, y, lz);
                    int glassColor = BlockColors.getColor(
                        world,
                        glass,
                        chunk.getBlockMetadata(lx, y, lz),
                        chunk.xPosition * 16 + lx,
                        y,
                        chunk.zPosition * 16 + lz);
                    plant = BlockColors.blend(plant, glassColor, GLASS_TINT);
                }
            }
        }
        if (plant >= 0) {
            return BlockColors.shade(plant, DAYLIGHT);
        }
        if (chunk.getBlock(lx, y, lz)
            .getMaterial()
            .isLiquid()) {
            // Water and lava are flat: no bevel, as in JourneyMap.
            return BlockColors.shade(rgb, DAYLIGHT);
        }
        return bevel(BlockColors.shade(rgb, DAYLIGHT), slope(heights, lx + 2, lz + 2));
    }

    /** Slope factor of the column: above 1 facing the light (brighter), below 1 facing away (darker). */
    private static float slope(int[][] heights, int i, int j) {
        int y = heights[i][j];
        if (y <= 0) {
            return 1f;
        }
        float primary = averageRatio(heights, i, j, y, PRIMARY_SLOPE);
        float slope = primary < 1f ? primary * DOWNSLOPE : primary > 1f ? primary * UPSLOPE : 1f;
        if (primary == 1f) {
            // Flat next to the block: look one block further to soften the edges (JourneyMap's antialiasing).
            float secondary = averageRatio(heights, i, j, y, SECONDARY_SLOPE);
            if (secondary > 1f) {
                slope *= SECONDARY_UPSLOPE;
            } else if (secondary < 1f) {
                slope *= SECONDARY_DOWNSLOPE;
            }
        }
        return Math.max(SLOPE_MIN, Math.min(SLOPE_MAX, slope));
    }

    /** Average of the column's height divided by each neighbour's (unknown neighbours count as level). */
    private static float averageRatio(int[][] heights, int i, int j, int y, int[][] offsets) {
        float sum = 0;
        for (int[] offset : offsets) {
            int other = heights[i + offset[0]][j + offset[1]];
            sum += other == NO_BLOCK || other <= 0 ? 1f : (float) y / other;
        }
        return sum / offsets.length;
    }

    /** Darkens or lightens by the slope; shadows get a blue tint (JourneyMap's bevelSlope). */
    private static int bevel(int rgb, float factor) {
        float bluer = factor < 1f ? 0.85f : 1f;
        int r = Math.min(255, (int) (((rgb >> 16) & 0xFF) * bluer * factor));
        int g = Math.min(255, (int) (((rgb >> 8) & 0xFF) * bluer * factor));
        int b = Math.min(255, (int) ((rgb & 0xFF) * factor));
        return r << 16 | g << 8 | b;
    }

    /**
     * Color of a plant, crop, sapling, rail, redstone or torch standing on the block, or -1 if there is none (or it
     * is grass or a flower and those are left out).
     * JourneyMap draws these instead of the block below them.
     */
    private static int plantColor(World world, Chunk chunk, int lx, int y, int lz, boolean grassAndFlowers) {
        if (y >= 255) {
            return -1;
        }
        Block above = chunk.getBlock(lx, y + 1, lz);
        Material material = above.getMaterial();
        if (above.getRenderType() == -1
            || material != Material.plants && material != Material.vine && material != Material.circuits) {
            return -1;
        }
        if (!grassAndFlowers && isPlant(material)) {
            return -1;
        }
        int x = chunk.xPosition * 16 + lx;
        int z = chunk.zPosition * 16 + lz;
        return BlockColors.getColor(world, above, chunk.getBlockMetadata(lx, y + 1, lz), x, y + 1, z);
    }

    /**
     * Left out of the map without plants: anything of the plants' materials. Flowers, double plants, sugar cane,
     * crops, saplings and mushrooms are {@link Material#plants}; tall grass, ferns and dead bushes are
     * {@link Material#vine} in this version (it has no separate reed material).
     */
    private static boolean isPlant(Material material) {
        return material == Material.plants || material == Material.vine;
    }

    /** Color of the biome for the biome map; biomes without a color get a stable made-up one. */
    public static int biomeColor(BiomeGenBase biome) {
        int color = biome.color & 0xFFFFFF;
        if (color != 0) {
            return color;
        }
        int hash = biome.biomeID * 0x9E3779B1;
        return 0x404040 | (hash >>> 8) & 0xBFBFBF;
    }

    /** y of the topmost block drawn on the surface map, -1 if none (for the log). */
    static int surfaceY(Chunk chunk, int lx, int lz, boolean noSky) {
        return findSurface(chunk, lx, lz, noSky);
    }

    private static int findTop(Chunk chunk, int lx, int lz, boolean noSky, int caveLayer) {
        return caveLayer >= 0 ? findCaveFloor(chunk, lx, lz, caveLayer) : findSurface(chunk, lx, lz, noSky);
    }

    /** How bright a cut through the top of a cave layer is drawn: solid there, so nothing to stand on. */
    private static final float CUT_SHADE = 0.45f;

    /** Whether {@code y} is a cut through the top of the cave layer (see {@link #findCaveFloor}). */
    private static boolean isCut(Chunk chunk, int lx, int y, int lz, int layer) {
        return y == Math.min(255, layer * 16 + 15) && isSolid(chunk.getBlock(lx, y, lz));
    }

    /**
     * Floor of the open space in the cave layer: rock at the top of the layer is skipped, then the first block below
     * the open space is the floor, however far down it is (big caverns, the Nether's lava sea), like JourneyMap. A
     * column solid all through the layer gives its block at the top of the layer, drawn darker as a cut: machines
     * standing on the floor of a base reach up through the layer's top, and were holes in the map.
     */
    private static int findCaveFloor(Chunk chunk, int lx, int lz, int layer) {
        int layerBottom = layer * 16;
        int y = Math.min(255, layerBottom + 15);
        while (y >= layerBottom && isSolid(chunk.getBlock(lx, y, lz))) {
            y--;
        }
        if (y < layerBottom) {
            return Math.min(255, layerBottom + 15);
        }
        for (; y >= 0; y--) {
            if (isVisible(chunk.getBlock(lx, y, lz))) {
                return y;
            }
        }
        return NO_BLOCK;
    }

    /** A block that fills the space (not air, plants, torches or liquids). */
    private static boolean isSolid(Block block) {
        return isVisible(block) && !block.getMaterial()
            .isLiquid();
    }

    /** @return y of the topmost block that should be drawn, or {@link #NO_BLOCK}. */
    private static int findSurface(Chunk chunk, int lx, int lz, boolean noSky) {
        int top = chunk.getTopFilledSegment() + 15;
        if (top < 0) {
            return NO_BLOCK;
        }
        int y = top;
        if (noSky) {
            // Under a ceiling (Nether): skip the ceiling, then look for the floor below the first open space.
            top = Math.min(top, 127);
            y = top;
            while (y > 0 && !isAir(chunk.getBlock(lx, y, lz))) {
                y--;
            }
        }
        for (; y >= 0; y--) {
            Block block = chunk.getBlock(lx, y, lz);
            if (isVisible(block)) {
                return y;
            }
        }
        return NO_BLOCK;
    }

    private static boolean isAir(Block block) {
        return block.getMaterial() == Material.air;
    }

    private static boolean isVisible(Block block) {
        Material material = block.getMaterial();
        if (material == Material.air || material == Material.plants
            || material == Material.vine
            || material == Material.circuits
            || material == Material.web
            || material == Material.fire) {
            return false;
        }
        return block.getRenderType() != -1 || material.isLiquid();
    }

    /** How much of the glass color shows over what is under it. */
    private static final float GLASS_TINT = 0.2f;

    /** Glass you can see through (not glowstone, which is glass too but solid). */
    private static boolean isClearGlass(Block block) {
        return block.getMaterial() == Material.glass && !block.isOpaqueCube();
    }

    /**
     * The block seen from above at {@code y}: under clear glass (when {@link Config#seeThroughGlass} is on), the first
     * other visible block below it; otherwise {@code y} itself.
     */
    private static int seenThroughGlass(Chunk chunk, int lx, int y, int lz) {
        if (!Config.seeThroughGlass || !isClearGlass(chunk.getBlock(lx, y, lz))) {
            return y;
        }
        for (int below = y - 1; below >= 0; below--) {
            Block block = chunk.getBlock(lx, below, lz);
            if (isVisible(block) && !isClearGlass(block)) {
                return below;
            }
        }
        return y;
    }

    private static int columnColor(World world, Chunk chunk, int lx, int y, int lz) {
        int x = chunk.xPosition * 16 + lx;
        int z = chunk.zPosition * 16 + lz;
        Block block = chunk.getBlock(lx, y, lz);
        int meta = chunk.getBlockMetadata(lx, y, lz);
        int color = BlockColors.getColor(world, block, meta, x, y, z);

        int seen = seenThroughGlass(chunk, lx, y, lz);
        if (seen != y) {
            // Glass: what is under it (water with its floor too), lightly tinted with the glass.
            return BlockColors.blend(columnColor(world, chunk, lx, seen, lz), color, GLASS_TINT);
        }

        if (block.getMaterial() == Material.water) {
            // Let the floor shine through shallow water.
            int floorY = y - 1;
            while (floorY > 0 && chunk.getBlock(lx, floorY, lz)
                .getMaterial() == Material.water) {
                floorY--;
            }
            int depth = y - floorY;
            Block floor = chunk.getBlock(lx, floorY, lz);
            if (isVisible(floor)) {
                int floorColor = BlockColors
                    .getColor(world, floor, chunk.getBlockMetadata(lx, floorY, lz), x, floorY, z);
                float waterAmount = Math.min(0.95f, 0.55f + depth * 0.05f);
                color = BlockColors.blend(floorColor, color, waterAmount);
            }
            color = BlockColors.shade(color, Math.max(0.6f, 1.0f - depth * 0.015f));
        }
        return color;
    }
}
