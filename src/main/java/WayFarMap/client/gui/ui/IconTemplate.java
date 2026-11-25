package WayFarMap.client.gui.ui;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

/**
 * Writes {@code CustomThemes/_icons}: every icon of the mod drawn white in one picture ({@code icons.png}, a dot per
 * pixel) and the sprites that cut them out of it ({@code icons.json}), to copy into a theme: an atlas made from the
 * picture, with the icons drawn over, replaces them all at once. Written each start, so it always has all the icons.
 */
final class IconTemplate {

    private static final int WIDTH = 256, GAP = 2;

    private IconTemplate() {}

    static void write(File dir) throws IOException {
        Map<String[], String> names = Skin.iconNames();
        List<Map.Entry<String[], String>> icons = new ArrayList<>(names.entrySet());
        icons.sort(Map.Entry.comparingByValue());
        // Laid out in rows, left to right.
        int x = GAP, y = GAP, rowHeight = 0;
        int[][] places = new int[icons.size()][];
        for (int i = 0; i < icons.size(); i++) {
            String[] icon = icons.get(i)
                .getKey();
            int w = Math.max(1, Icons.width(icon)), h = Math.max(1, icon.length);
            if (x + w + GAP > WIDTH) {
                x = GAP;
                y += rowHeight + GAP;
                rowHeight = 0;
            }
            places[i] = new int[] { x, y, w, h };
            x += w + GAP;
            rowHeight = Math.max(rowHeight, h);
        }
        int height = y + rowHeight + GAP;
        BufferedImage image = new BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_ARGB);
        StringBuilder json = new StringBuilder();
        json.append(
            "// Every icon of Wayfarer's Map, cut out of icons.png (white: \"recolor\" paints it in the icon's\n")
            .append("// color, as the mod does). Copy the lines you need into \"sprites\" of your theme.json and put\n")
            .append("// the icons at the same place in your atlas (or change \"uv\"). Written again each start.\n")
            .append("// Все иконки мода из icons.png. Скопируйте нужные строки в \"sprites\" своего theme.json.\n")
            .append("{\n  \"sprites\": {\n");
        for (int i = 0; i < icons.size(); i++) {
            String[] icon = icons.get(i)
                .getKey();
            int[] p = places[i];
            for (int row = 0; row < icon.length; row++) {
                for (int col = 0; col < icon[row].length(); col++) {
                    if (icon[row].charAt(col) == '#') {
                        image.setRGB(p[0] + col, p[1] + row, 0xFFFFFFFF);
                    }
                }
            }
            json.append("    \"icon.")
                .append(
                    icons.get(i)
                        .getValue())
                .append("\": { \"uv\": [")
                .append(p[0])
                .append(", ")
                .append(p[1])
                .append(", ")
                .append(p[2])
                .append(", ")
                .append(p[3])
                .append("] }")
                .append(i < icons.size() - 1 ? "," : "")
                .append('\n');
        }
        json.append("  }\n}\n");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("could not make " + dir);
        }
        ImageIO.write(image, "png", new File(dir, "icons.png"));
        Files.write(
            new File(dir, "icons.json").toPath(),
            json.toString()
                .getBytes(StandardCharsets.UTF_8));
    }
}
