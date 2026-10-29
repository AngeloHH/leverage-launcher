package dev.leverage.ui;

import com.formdev.flatlaf.extras.FlatSVGIcon;

import javax.swing.Icon;
import java.awt.Color;
import java.awt.Graphics2D;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// Loads individual SVG icons bundled with the launcher.
public final class SvgIconLibrary {
    private static final Set<String> IDS = Set.of("pixel-play", "pixel-headset",
            "pixel-grid-add", "pixel-pencil", "pixel-hanger", "pixel-appearance",
            "pixel-players", "pixel-sign", "pixel-chest", "pixel-news", "pixel-download",
            "pixel-chevron-up", "pixel-chevron-down", "pixel-copy", "pixel-gift");
    private static final Map<IconKey, Icon> CACHE = new ConcurrentHashMap<>();

    private SvgIconLibrary() {
    }

    public static Set<String> ids() {
        return IDS;
    }

    public static Icon icon(String id, int width, int height) {
        return icon(id, width, height, null);
    }

    public static Icon icon(String id, int width, int height, Color tint) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "El tamaño del icono debe ser mayor que cero");
        }
        if (!IDS.contains(id)) {
            throw new IllegalArgumentException("No existe el icono SVG: " + id);
        }
        IconKey key = new IconKey(id, width, height, tint == null ? null : tint.getRGB());
        return CACHE.computeIfAbsent(key, SvgIconLibrary::createIcon);
    }

    public static void paint(Graphics2D graphics, String id, int x,
            int y, int width, int height, Color tint) {
        icon(id, width, height, tint).paintIcon(null, graphics, x, y);
    }

    private static Icon createIcon(IconKey key) {
        String resource = "dev/leverage/ui/icons/" + key.id() + ".svg";
        FlatSVGIcon icon = new FlatSVGIcon(resource, key.width(), key.height(),
                SvgIconLibrary.class.getClassLoader());
        if (key.rgb() != null) {
            Color tint = new Color(key.rgb(), true);
            icon.setColorFilter(new FlatSVGIcon.ColorFilter(ignored -> tint));
        }
        return icon;
    }

    private record IconKey(String id, int width, int height, Integer rgb) {
    }
}
