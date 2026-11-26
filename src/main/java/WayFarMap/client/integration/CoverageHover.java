package WayFarMap.client.integration;

/**
 * Which coverage circle (or square) the mouse is in on the world map, across all the coverage layers: the smallest
 * one holding the cursor, the most telling of where it is. Layers are drawn one after another, so the winner is picked
 * from the circles offered during a frame and used in the next one: each layer offers its
 * circles under the mouse ({@link #offer}) and draws the highlight of the one that won the frame before
 * ({@link #isWinner}). A frame late is not to be seen.
 */
public final class CoverageHover {

    private static String winner, best;
    private static double bestRadius;

    private CoverageHover() {}

    /** A circle of the radius (screen pixels) holds the mouse; the key names the machine across frames. */
    static void offer(String key, double radius) {
        if (best == null || radius < bestRadius) {
            best = key;
            bestRadius = radius;
        }
    }

    /**
     * Offers the circle if it holds the mouse, and tells whether it is the one under the mouse (it won the frame
     * before).
     */
    static boolean track(String key, double x, double y, double radius, int mouseX, int mouseY) {
        double dx = mouseX - x, dy = mouseY - y;
        if (radius > 0.5 && dx * dx + dy * dy <= radius * radius) {
            offer(key, radius);
        }
        return isWinner(key);
    }

    /** {@link #track} for a square of that half side. */
    static boolean trackSquare(String key, double x, double y, double half, int mouseX, int mouseY) {
        if (half > 0.5 && Math.abs(mouseX - x) <= half && Math.abs(mouseY - y) <= half) {
            offer(key, half);
        }
        return isWinner(key);
    }

    static boolean isWinner(String key) {
        return key.equals(winner);
    }

    /** After the world map's layers: what was offered this frame wins the next one. */
    public static void endFrame() {
        winner = best;
        best = null;
    }

    /** The map is off or shows no layers: nothing is under the mouse. */
    public static void clear() {
        winner = null;
        best = null;
    }
}
