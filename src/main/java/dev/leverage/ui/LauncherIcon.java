package dev.leverage.ui;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Taskbar;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

// Application branding for window decorations and supported taskbars, independent of user
// files.
final class LauncherIcon {
    private static final List<Image> IMAGES = loadImages();

    private LauncherIcon() { }

    static void apply(JFrame frame) {
        frame.setIconImages(IMAGES);
        try {
            if (Taskbar.isTaskbarSupported()) {
                Taskbar taskbar = Taskbar.getTaskbar();
                if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                    taskbar.setIconImage(IMAGES.getLast());
                }
            }
        } catch (UnsupportedOperationException | SecurityException ignored) {
            // A platform without a custom taskbar icon still gets the frame's native
            // window icons.
        }
    }

    static List<Image> images() { return IMAGES; }

    private static List<Image> loadImages() {
        String resource = "/dev/leverage/ui/images/favicon.png";
        try (var input = LauncherIcon.class.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("No se encontró " + resource);
            BufferedImage source = ImageIO.read(input);
            if (source == null) throw new IOException(
                    "El icono de Leverage no es una imagen válida");
            List<Image> images = new ArrayList<>();
            for (int size : new int[] { 16, 24, 32, 48, 64, 128 }) {
                if (source.getWidth() == size && source.getHeight() == size) {
                    images.add(source);
                    continue;
                }
                BufferedImage image = new BufferedImage(size, size,
                        BufferedImage.TYPE_INT_ARGB);
                var graphics = image.createGraphics();
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                    graphics.drawImage(source, 0, 0, size, size, null);
                } finally {
                    graphics.dispose();
                }
                images.add(image);
            }
            return List.copyOf(images);
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo cargar el icono de Leverage",
                    exception);
        }
    }
}
