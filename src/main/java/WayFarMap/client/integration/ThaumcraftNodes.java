package WayFarMap.client.integration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.StatCollector;

import org.lwjgl.opengl.GL11;

import com.dyonovan.tcnodetracker.TCNodeTracker;
import com.dyonovan.tcnodetracker.integration.navigator.ThaumcraftNodeLayerManager;
import com.dyonovan.tcnodetracker.lib.JsonUtils;
import com.dyonovan.tcnodetracker.lib.NodeList;

import WayFarMap.client.Lang;
import WayFarMap.client.gui.ui.ScaledScreen;
import WayFarMap.client.waypoint.WaypointRenderer;
import thaumcraft.api.aspects.Aspect;

/**
 * Thaumcraft aura nodes found with TCNodeTracker, drawn like its own Navigator (JourneyMap / Xaero) layer: the node
 * icon tinted with the node's strongest aspect, with that aspect on top. TCNodeTracker keeps them in
 * {@link TCNodeTracker#nodelist}; removing a node takes it out of there and saves its file. A tracked node gets a
 * marker in the world like a tracked ore vein.
 * <p>
 * Only touch this class after {@link Mods#isThaumcraftNodesAvailable()}.
 */
public final class ThaumcraftNodes {

    private static final ResourceLocation NODE = new ResourceLocation(
        "tcnodetracker",
        "textures/gui/node_unmarked.png");
    private static final ResourceLocation NODE_TRACKED = new ResourceLocation(
        "tcnodetracker",
        "textures/gui/node_marked.png");
    /** TCNodeTracker's JourneyMap marker dimensions, in screen pixels. */
    private static final int ICON_SIZE = 44, ASPECT_SIZE = 32;
    private static final int SEARCH_COLOR = 0xFFD34D;
    private static final int LABEL_BACKGROUND = 0xB4000000;

    private ThaumcraftNodes() {}

    /** The tracked node as {dimension, x, y, z}, shown in the world like a waypoint; null if none. */
    private static int[] tracked;

    /** One node with its aspects, strongest first. */
    public static final class Node {

        final NodeList source;
        public final int dimension, x, y, z;
        final List<Aspect> aspects = new ArrayList<>();
        final List<Integer> amounts = new ArrayList<>();

        Node(NodeList source) {
            this.source = source;
            dimension = source.dim;
            x = source.x;
            y = source.y;
            z = source.z;
            List<Map.Entry<String, Integer>> entries = new ArrayList<>();
            if (source.aspect != null) {
                entries.addAll(source.aspect.entrySet());
            }
            entries.sort((a, b) -> Integer.compare(amount(b.getValue()), amount(a.getValue())));
            for (Map.Entry<String, Integer> entry : entries) {
                Aspect aspect = Aspect.getAspect(entry.getKey());
                if (aspect != null) {
                    aspects.add(aspect);
                    amounts.add(amount(entry.getValue()));
                }
            }
        }

        private static int amount(Integer value) {
            return value == null ? 0 : value;
        }

        int color() {
            return aspects.isEmpty() ? 0xFFFFFF
                : aspects.get(0)
                    .getColor();
        }

        ResourceLocation image() {
            return aspects.isEmpty() ? null
                : aspects.get(0)
                    .getImage();
        }

        boolean tracked() {
            int[] target = tracked;
            return target != null && target[0] == dimension && target[1] == x && target[2] == y && target[3] == z;
        }
    }

    /** How long the nodes of a dimension are reused before being read again from TCNodeTracker's list. */
    private static final long CACHE_MS = 1000;

    private static List<Node> cachedNodes;
    private static List<NodeList> cachedSources;
    private static int cachedDimension, cachedSize;
    private static long cachedAt;

    /**
     * Nodes of the dimension, read again once a second or when the list changes size: building them (sorting the
     * aspects) every frame for hundreds of nodes is wasted work.
     */
    private static List<Node> nodes(int dimension) {
        List<NodeList> sources = TCNodeTracker.nodelist;
        if (sources == null) {
            // TCNodeTracker leaves it null when its file is empty.
            return new ArrayList<>();
        }
        long now = System.currentTimeMillis();
        // A new list (another world) or a node added or removed: read again right away.
        if (cachedNodes != null && cachedSources == sources
            && cachedDimension == dimension
            && cachedSize == sources.size()
            && now - cachedAt < CACHE_MS) {
            return cachedNodes;
        }
        List<Node> result = new ArrayList<>();
        for (NodeList source : sources) {
            if (source.dim == dimension) {
                result.add(new Node(source));
            }
        }
        cachedNodes = result;
        cachedSources = sources;
        cachedDimension = dimension;
        cachedSize = sources.size();
        cachedAt = now;
        return result;
    }

    // ---------------------------------------------------------------- search

    private static String[] searchTokens = {};

    /** Nodes found, per dimension, for the statistics. */
    public static Map<Integer, Integer> countFound() {
        Map<Integer, Integer> byDimension = new HashMap<>();
        List<NodeList> sources = TCNodeTracker.nodelist;
        if (sources != null) {
            for (NodeList source : sources) {
                byDimension.merge(source.dim, 1, Integer::sum);
            }
        }
        return byDimension;
    }

    /** Aspect search ("ignis", "Aer ordo"): nodes lacking one of the aspects are dimmed. */
    public static void setSearch(String text) {
        String q = text == null ? ""
            : text.trim()
                .toLowerCase(Locale.ROOT);
        searchTokens = q.isEmpty() ? new String[0] : q.split("\\s+");
    }

    private static boolean matches(Node node) {
        for (String token : searchTokens) {
            boolean found = false;
            for (Aspect aspect : node.aspects) {
                found |= aspect.getName()
                    .toLowerCase(Locale.ROOT)
                    .contains(token)
                    || aspect.getTag()
                        .toLowerCase(Locale.ROOT)
                        .contains(token);
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- drawing

    private static Node hovered;

    public static void draw(int dimension, double centerX, double centerZ, double scale, int x, int y, int width,
        int height, boolean minimap, int mouseX, int mouseY) {
        if (!minimap) {
            hovered = null;
        }
        List<Node> all = nodes(dimension);
        if (all.isEmpty()) {
            return;
        }
        double left = centerX - width / 2.0 / scale;
        double top = centerZ - height / 2.0 / scale;
        // Keep JourneyMap's 44-pixel marker readable even when zoomed out, at any UI scale. When zoomed in,
        // it can still grow to cover the node's block (up to 64 GUI pixels).
        double size = minimap ? 9 : Math.max(ICON_SIZE / (double) ScaledScreen.currentFactor(), Math.min(64, scale));
        double half = size / 2;
        boolean searching = searchTokens.length > 0;

        List<Node> visible = new ArrayList<>();
        List<double[]> positions = new ArrayList<>();
        for (Node node : all) {
            double sx = x + (node.x + 0.5 - left) * scale;
            double sy = y + (node.z + 0.5 - top) * scale;
            if (sx + half < x || sy + half < y || sx - half > x + width || sy - half > y + height) {
                continue;
            }
            visible.add(node);
            positions.add(new double[] { sx, sy });
            if (!minimap && (mouseX - sx) * (mouseX - sx) + (mouseY - sy) * (mouseY - sy) <= half * half) {
                hovered = node;
            }
        }
        if (visible.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        Tessellator tessellator = Tessellator.instance;
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        // Node rings tinted with the strongest aspect (tracked ones in their own texture), in two batches.
        for (int pass = 0; pass < 2; pass++) {
            mc.getTextureManager()
                .bindTexture(pass == 0 ? NODE : NODE_TRACKED);
            tessellator.startDrawingQuads();
            for (int i = 0; i < visible.size(); i++) {
                Node node = visible.get(i);
                boolean tracked = node.tracked();
                if (tracked != (pass == 1)) {
                    continue;
                }
                boolean dim = searching && !matches(node);
                tessellator.setColorRGBA_I(tracked ? 0xFFFFFF : node.color(), dim ? 70 : 255);
                quad(tessellator, positions.get(i), half);
            }
            tessellator.draw();
        }
        // The strongest aspect in the middle.
        for (int i = 0; i < visible.size(); i++) {
            Node node = visible.get(i);
            ResourceLocation image = node.image();
            if (image == null) {
                continue;
            }
            boolean dim = searching && !matches(node);
            mc.getTextureManager()
                .bindTexture(image);
            tessellator.startDrawingQuads();
            tessellator.setColorRGBA_I(node.color(), dim ? 90 : 255);
            quad(tessellator, positions.get(i), half * ASPECT_SIZE / ICON_SIZE);
            tessellator.draw();
        }
        if (searching) {
            // Search hits get a frame.
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            tessellator.startDrawingQuads();
            tessellator.setColorRGBA_I(SEARCH_COLOR, 255);
            for (int i = 0; i < visible.size(); i++) {
                if (matches(visible.get(i))) {
                    double[] p = positions.get(i);
                    frame(tessellator, p[0] - half - 1, p[1] - half - 1, p[0] + half + 1, p[1] + half + 1);
                }
            }
            tessellator.draw();
            GL11.glEnable(GL11.GL_TEXTURE_2D);
        }
        GL11.glColor4f(1f, 1f, 1f, 1f);

        // Aspects under the icons when zoomed in, like a label.
        if (minimap || scale < 2) {
            return;
        }
        FontRenderer font = mc.fontRenderer;
        for (int i = 0; i < visible.size(); i++) {
            Node node = visible.get(i);
            if (searching && !matches(node) || node.aspects.isEmpty()) {
                continue;
            }
            double[] p = positions.get(i);
            String text = summary(node, 3);
            int w = font.getStringWidth(text);
            int lx = (int) Math.round(p[0] - w / 2.0), ly = (int) Math.round(p[1] + half + 3);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            tessellator.startDrawingQuads();
            tessellator.setColorRGBA_I(LABEL_BACKGROUND & 0xFFFFFF, LABEL_BACKGROUND >>> 24);
            tessellator.addVertex(lx - 2, ly + 9, 0);
            tessellator.addVertex(lx + w + 2, ly + 9, 0);
            tessellator.addVertex(lx + w + 2, ly - 1, 0);
            tessellator.addVertex(lx - 2, ly - 1, 0);
            tessellator.draw();
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            font.drawString(text, lx, ly, 0xFFFFFF);
        }
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    private static void quad(Tessellator tessellator, double[] p, double half) {
        tessellator.addVertexWithUV(p[0] - half, p[1] + half, 0, 0, 1);
        tessellator.addVertexWithUV(p[0] + half, p[1] + half, 0, 1, 1);
        tessellator.addVertexWithUV(p[0] + half, p[1] - half, 0, 1, 0);
        tessellator.addVertexWithUV(p[0] - half, p[1] - half, 0, 0, 0);
    }

    private static void frame(Tessellator t, double x0, double y0, double x1, double y1) {
        rect(t, x0, y0, x1, y0 + 1);
        rect(t, x0, y1 - 1, x1, y1);
        rect(t, x0, y0 + 1, x0 + 1, y1 - 1);
        rect(t, x1 - 1, y0 + 1, x1, y1 - 1);
    }

    private static void rect(Tessellator t, double x0, double y0, double x1, double y1) {
        t.addVertex(x0, y1, 0);
        t.addVertex(x1, y1, 0);
        t.addVertex(x1, y0, 0);
        t.addVertex(x0, y0, 0);
    }

    /** "Aer 25 · Ordo 18 · …" in the aspects' colors. */
    private static String summary(Node node, int max) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < node.aspects.size() && i < max; i++) {
            if (i > 0) {
                text.append("\u00a77 \u00b7 ");
            }
            text.append("\u00a7f")
                .append(
                    node.aspects.get(i)
                        .getName())
                .append(" \u00a77")
                .append(node.amounts.get(i));
        }
        if (node.aspects.size() > max) {
            text.append("§7 …");
        }
        return text.toString();
    }

    // ---------------------------------------------------------------- world map interaction

    public static Object getHovered() {
        return hovered;
    }

    public static boolean isTracked(Object handle) {
        return ((Node) handle).tracked();
    }

    /** Starts tracking the node, or stops if it is the tracked one. */
    public static void toggleTracked(Object handle) {
        Node node = (Node) handle;
        tracked = node.tracked() ? null : new int[] { node.dimension, node.x, node.y, node.z };
    }

    /**
     * Draws the tracked node in the world like a waypoint (as a tracked ore vein): the node icon with its strongest
     * aspect, the name and the distance, visible through blocks.
     */
    public static void renderTrackedInWorld(Minecraft mc, int dimension, float partialTicks) {
        int[] target = tracked;
        if (target == null || target[0] != dimension || TCNodeTracker.nodelist == null) {
            return;
        }
        for (NodeList source : TCNodeTracker.nodelist) {
            if (source.dim != target[0] || source.x != target[1] || source.y != target[2] || source.z != target[3]) {
                continue;
            }
            final Node node = new Node(source);
            if (WaypointRenderer.trackedBeams()) {
                WaypointRenderer.renderBeam(mc, node.x, node.z, node.color(), partialTicks, 1f);
            }
            String aspect = node.aspects.isEmpty() ? ""
                : node.aspects.get(0)
                    .getName();
            WaypointRenderer.renderBillboard(
                mc,
                node.x + 0.5,
                node.y,
                node.z + 0.5,
                Lang.format("wayfarmap.node.tracked_name", aspect),
                node.color(),
                (cx, cy, size) -> {
                    drawIcon(node, cx, cy, size);
                    return true;
                });
            return;
        }
    }

    /** The node icon and its strongest aspect, centered on (cx, cy). */
    private static void drawIcon(Node node, double cx, double cy, double size) {
        Minecraft mc = Minecraft.getMinecraft();
        Tessellator tessellator = Tessellator.instance;
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        double[] center = { cx, cy };
        mc.getTextureManager()
            .bindTexture(NODE_TRACKED);
        tessellator.startDrawingQuads();
        tessellator.setColorRGBA_I(0xFFFFFF, 230);
        quad(tessellator, center, size / 2);
        tessellator.draw();
        ResourceLocation image = node.image();
        if (image != null) {
            mc.getTextureManager()
                .bindTexture(image);
            tessellator.startDrawingQuads();
            tessellator.setColorRGBA_I(node.color(), 255);
            quad(tessellator, center, size * 0.35);
            tessellator.draw();
        }
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /** Removes the node the way TCNodeTracker does (its "delete"): from its list, and saves. */
    public static void markDepleted(Object handle) {
        Node node = (Node) handle;
        if (node.tracked()) {
            tracked = null;
        }
        if (TCNodeTracker.isNavigatorLoaded && Mods.hasNodeLayerDelete()) {
            // Also updates its own map layers.
            ThaumcraftNodeLayerManager.instance.deleteNode(node.source);
        } else if (TCNodeTracker.nodelist != null) {
            TCNodeTracker.nodelist.remove(node.source);
            JsonUtils.writeJson();
        }
        cachedNodes = null;
    }

    public static List<String> getHoveredTooltip() {
        if (hovered == null) {
            return null;
        }
        Node node = hovered;
        List<String> lines = new ArrayList<>();
        if (node.tracked()) {
            lines.add("§6" + Lang.format("wayfarmap.node.tracked"));
        }
        lines.add("§l" + StatCollector.translateToLocal("tile.blockAiry.0.name"));
        String kind = StatCollector.translateToLocal("nodetype." + node.source.type + ".name");
        if (node.source.mod != null && !"BLANK".equals(node.source.mod)) {
            kind += ", " + StatCollector.translateToLocal("nodemod." + node.source.mod + ".name");
        }
        lines.add("\u00a77" + kind);
        for (int i = 0; i < node.aspects.size(); i++) {
            lines.add(
                "\u00a7f" + node.aspects.get(i)
                    .getName() + " \u00a77" + node.amounts.get(i));
        }
        lines.add("§7" + node.x + ", " + node.y + ", " + node.z);
        lines.add("§8" + Lang.format("wayfarmap.node.hint"));
        return lines;
    }
}
