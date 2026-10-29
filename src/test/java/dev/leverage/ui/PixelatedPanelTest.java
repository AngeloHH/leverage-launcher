package dev.leverage.ui;

import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PixelatedPanelTest {
    @Test
    void approvedResourceRetainsAllSixteenCalibratedLayoutsAndExactReference() {
        var patterns = PixelatedPanelGeometry.PATTERNS;
        assertEquals(16, patterns.size());
        assertEquals(16, new HashSet<>(patterns).size());
        assertEquals(List.of("101101", "010110", "100000"),
                PixelatedPanelGeometry.pattern(8));
        for (var pattern : patterns) {
            assertEquals(3, pattern.size());
            assertEquals(4, pattern.get(0).chars().filter(c -> c == '1').count());
            assertEquals(3, pattern.get(1).chars().filter(c -> c == '1').count());
            assertEquals(1, pattern.get(2).chars().filter(c -> c == '1').count());
            pattern.forEach(row -> assertTrue(row.matches("[01]{6}")));
        }
        assertEquals(PixelatedPanelGeometry.pattern(8),
                PixelatedPanelGeometry.pattern(-8));
        assertDoesNotThrow(() -> PixelatedPanelGeometry.pattern(Long.MIN_VALUE));
        assertThrows(UnsupportedOperationException.class, () -> patterns.add(List.of()));
        assertThrows(UnsupportedOperationException.class,
                () -> patterns.getFirst().add("000000"));
    }

    @Test
    void reproducesReferenceIncludingCellsOnlyTouchingAtCorners() {
        int unit = 28;
        var geometry = geometry(500, 400, unit, 8, PixelatedPanel.Placement.BOTTOM_LEFT);
        var body = geometry.body();
        var reference = PixelatedPanelGeometry.pattern(8);
        for (int v = -1; v <= 1; v++) for (int u = 0; u < 6; u++) {
            assertEquals(reference.get(v + 1).charAt(u) == '1', geometry.fill().contains(
                    body.x + (u + .5) * unit, body.y + (geometry.rows()
                    + v + .5) * unit));
        }
        assertEquals(4, geometry.additions().size());
        assertEquals(2, geometry.cuts().size());
        assertTrue(geometry.additions().contains(new PixelatedPanelGeometry.Cell(0,
                geometry.rows() + 1)));
        assertTrue(geometry.additions().contains(new PixelatedPanelGeometry.Cell(1,
                geometry.rows())));
    }

    @Test
    void allPlacementsStayOnOneGridAtDifferentSizesAndSeeds() {
        for (var placement : PixelatedPanel.Placement.values()) {
            for (int[] size : new int[][] { { 0, 0 }, { 1, 1 }, { 150,
                    90 }, { 320, 384 }, { 760, 520 } }) {
                for (int unit : new int[] { 12, 28, 32, 64, 128 }) {
                    for (long seed : new long[] { 0, 8, Long.MIN_VALUE }) {
                        var geometry = geometry(size[0], size[1], unit, seed, placement);
                        Rectangle body = geometry.body();
                        for (var cell : geometry.additions()) assertTrue(
                                geometry.fill().contains(
                                body.x + (cell.x() + .5) * unit,
                                body.y + (cell.y() + .5) * unit));
                        for (var cell : geometry.cuts()) assertFalse(
                                geometry.fill().contains(
                                body.x + (cell.x() + .5) * unit,
                                body.y + (cell.y() + .5) * unit));
                        for (Rectangle face : geometry.lowerFaces()) {
                            assertEquals(0, Math.floorMod(face.x - body.x, unit));
                            assertEquals(0, Math.floorMod(face.y + 1 - body.y, unit));
                            assertEquals(0, face.width % unit);
                            assertEquals(3, face.height);
                            assertTrue(face.x >= 0 && face.y >= 0);
                            assertTrue(face.getMaxX() <= size[0]
                                    && face.getMaxY() <= size[1]);
                            double x = face.getCenterX(), boundary = face.y + 1;
                            assertTrue(geometry.fill().contains(x, boundary - .5));
                            assertFalse(geometry.fill().contains(x, boundary + .5));
                        }
                    }
                }
            }
        }
    }

    @Test
    void completeSidesCoverWholeEdgeAndTwoCornersNeverOverlap() {
        for (int width : new int[] { 200, 320, 500, 1040 }) {
            var all = geometry(width, 520, 28, 8, PixelatedPanel.Placement.ALL);
            for (var side : PixelatedPanel.Side.values()) {
                var coverage = new HashSet<Integer>();
                for (var patch : all.patches()) if (patch.side() == side) {
                    for (int i = 0; i < patch.length();
                            i++) coverage.add(patch.start() + i);
                }
                assertEquals(PixelatedPanelGeometry.horizontal(side)
                        ? all.columns() : all.rows(), coverage.size());
            }
            var corners = geometry(width, 520, 28, 8,
                    PixelatedPanel.Placement.BOTTOM_CORNERS);
            var seen = new HashSet<Integer>();
            for (var patch : corners.patches()) for (int i = 0; i < patch.length(); i++) {
                assertTrue(seen.add(patch.reversed() ? patch.start()
                        - i : patch.start() + i));
            }
        }
    }

    @Test
    void rangeConfigurationUsesWholeCellsAndDefensiveCopies() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = new PixelatedPanel();
            panel.setSize(760, 520);
            var ranges = new ArrayList<>(List.of(
                    new PixelatedPanel.EdgeRange(PixelatedPanel.Side.BOTTOM, .7, 1, true),
                    new PixelatedPanel.EdgeRange(PixelatedPanel.Side.LEFT,
                    .1, .35, false)));
            panel.setEdgeRanges(ranges);
            ranges.clear();
            assertEquals(PixelatedPanel.Placement.CUSTOM, panel.getPlacement());
            assertEquals(2, panel.getEdgeRanges().size());
            assertThrows(UnsupportedOperationException.class,
                    () -> panel.getEdgeRanges().clear());
            var geometry = PixelatedPanelGeometry.build(760, 520, 28, 8,
                    panel.getPlacement(), panel.getEdgeRanges());
            for (var patch : geometry.patches()) {
                int count = PixelatedPanelGeometry.horizontal(patch.side())
                        ? geometry.columns() : geometry.rows();
                var range = panel.getEdgeRanges().stream().filter(r -> r.side(
                        ) == patch.side()).findFirst().orElseThrow();
                for (int i = 0; i < patch.length(); i++) {
                    int at = patch.reversed() ? patch.start() - i : patch.start() + i;
                    assertTrue(at >= Math.floor(count * range.start())
                            && at < Math.ceil(count * range.end()));
                }
            }
            panel.setEdgeRanges(List.of(new PixelatedPanel.EdgeRange(
                    PixelatedPanel.Side.RIGHT, .5, .5, false)));
            assertTrue(PixelatedPanelGeometry.build(760, 520, 28, 8, panel.getPlacement(),
                    panel.getEdgeRanges()).patches().isEmpty());
            panel.setPlacement(PixelatedPanel.Placement.ALL);
            assertEquals(PixelatedPanel.Placement.ALL, panel.getPlacement());
        });
    }

    @Test
    void validatesInputsAndNeverRetainsMutableInsetsOrRectangles() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = new PixelatedPanel();
            assertThrows(IllegalArgumentException.class, () -> panel.setBlockSize(0));
            assertThrows(IllegalArgumentException.class, () -> panel.setBlockSize(129));
            assertThrows(IllegalArgumentException.class, () ->
                    new PixelatedPanel.EdgeRange(PixelatedPanel.Side.TOP, .8, .2, false));
            assertThrows(IllegalArgumentException.class,
                    () -> new PixelatedPanel.EdgeRange(PixelatedPanel.Side.TOP,
                    Double.NaN, 1, false));
            assertThrows(NullPointerException.class, () -> panel.setEdgeRanges(null));
            assertThrows(NullPointerException.class, () -> panel.setPlacement(null));
            assertThrows(IllegalArgumentException.class,
                    () -> panel.setContentPadding(new Insets(-1, 0, 0, 0)));
            var padding = new Insets(10, 12, 14, 16);
            panel.setContentPadding(padding);
            padding.top = 999;
            assertEquals(10, panel.getContentPadding().top);
            panel.getContentPadding().left = 999;
            assertEquals(12, panel.getContentPadding().left);
            panel.setSize(500, 400);
            Rectangle original = panel.getBodyBounds();
            panel.getBodyBounds().x = 999;
            assertEquals(original, panel.getBodyBounds());
            assertNotNull(panel.getPreferredSize());
            assertFalse(panel.isOpaque());
        });
    }

    @Test
    void transparentHolesPassClicksAndKeepParentBackgroundVisible() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = new PixelatedPanel();
            BufferedImage image = render(panel, 500, 400);
            Rectangle body = panel.getBodyBounds();
            int unit = panel.getBlockSize();
            int holeX = body.x + unit + unit / 2, holeY = body.y + body.height - unit / 2;
            assertEquals(0, image.getRGB(holeX, holeY) >>> 24);
            assertFalse(panel.contains(holeX, holeY));
            int islandX = body.x + unit / 2, islandY = body.y
                    + body.height + unit + unit / 2;
            assertEquals(new Color(0x171615).getRGB(), image.getRGB(islandX, islandY));
            assertTrue(panel.contains(islandX, islandY));
            assertFalse(panel.contains(0, 0));
            assertFalse(panel.contains(500, 400));
            JPanel parent = new JPanel(null);
            parent.setBackground(Color.CYAN);
            parent.add(panel);
            panel.setBounds(0, 0, 500, 400);
            BufferedImage composed = render(parent, 500, 400);
            assertEquals(Color.CYAN.getRGB(), composed.getRGB(holeX, holeY));
        });
    }

    @Test
    void childrenUseSafeContentBoundsWithoutCoveringHolesOrIslands() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (var placement : new PixelatedPanel.Placement[] {
                    PixelatedPanel.Placement.BOTTOM_LEFT,
                    PixelatedPanel.Placement.ALL }) {
                var panel = new PixelatedPanel(placement);
                JPanel child = new JPanel();
                child.setBackground(Color.MAGENTA);
                JButton button = new JButton("Botón");
                child.add(button);
                panel.add(child, BorderLayout.CENTER);
                BufferedImage image = render(panel, 640, 480);
                assertEquals(panel.getContentBounds(), child.getBounds());
                Rectangle content = panel.getContentBounds();
                assertEquals(Color.MAGENTA.getRGB(),
                        image.getRGB(content.x + 2, content.y + 2));
                var geometry = geometry(640, 480, 28, 8, placement);
                for (var cell : geometry.cuts()) {
                    int x = geometry.body().x + cell.x() * 28 + 14;
                    int y = geometry.body().y + cell.y() * 28 + 14;
                    assertEquals(0, image.getRGB(x, y) >>> 24);
                }
                assertTrue(button.isEnabled());
                panel.setLayout(null);
                child.setBounds(0, 0, 640, 480);
                image = render(panel, 640, 480);
                assertNotEquals(Color.MAGENTA.getRGB(),
                        image.getRGB(geometry.body().x + 2, geometry.body().y + 2));
            }
        });
    }

    @Test
    void settingsResizeAndColorChangesInvalidateTheRightState() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = new PixelatedPanel();
            int[] first = pixels(render(panel, 500, 400));
            panel.setSeed(9);
            assertFalse(java.util.Arrays.equals(first, pixels(render(panel, 500, 400))));
            panel.setSeed(8);
            assertArrayEquals(first, pixels(render(panel, 500, 400)));
            render(panel, 740, 520);
            assertArrayEquals(first, pixels(render(panel, 500, 400)));
            panel.setBlockSize(32);
            assertNotEquals(28, panel.getBlockSize());
            panel.setPlacement(PixelatedPanel.Placement.NONE);
            panel.setBackground(Color.BLUE);
            panel.setShadeColor(Color.ORANGE);
            var image = render(panel, 500, 400);
            var body = panel.getBodyBounds();
            assertEquals(Color.BLUE.getRGB(), image.getRGB((int) body.getCenterX(),
                    (int) body.getCenterY()));
            assertEquals(Color.ORANGE.getRGB(), image.getRGB(body.x + 2,
                    body.y + body.height));
            assertDoesNotThrow(() -> render(panel, 1, 1));
            assertFalse(panel.contains(0, 0));
        });
    }

    @Test
    void galleryControlsTheRealComponentAndExportsNativeJavaPreviews() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var gallery = new PixelatedPanelGallery();
            render(gallery, 1040, 650);
            var panel = (PixelatedPanel) find(gallery, "pixelated-preview");
            var placement = (JComboBox<?>) find(gallery, "pixelated-placement");
            assertEquals(6, placement.getItemCount());
            for (int i = 0; i < 6; i++) {
                placement.setSelectedIndex(i);
                assertEquals(i + 1, panel.getPlacement().ordinal());
            }
            ((JSpinner) find(gallery, "pixelated-block-size")).setValue(32);
            ((JSpinner) find(gallery, "pixelated-seed")).setValue(9);
            assertEquals(32, panel.getBlockSize());
            assertEquals(9, panel.getSeed());
            String output = System.getenv("LEVERAGE_PIXELATED_PREVIEW");
            if (output == null) return;
            try {
                Path destination = Path.of(output);
                Files.createDirectories(destination);
                BufferedImage sheet = new BufferedImage(960, 1320,
                        BufferedImage.TYPE_INT_RGB);
                var g = sheet.createGraphics();
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, 960, 1320);
                g.setFont(LauncherFonts.minecraftTen(14f));
                String[] labels = { "Una esquina", "Dos esquinas inferiores",
                        "Esquinas opuestas", "Un lateral",
                        "Dos laterales", "Todo el contorno" };
                var values = PixelatedPanel.Placement.values();
                for (int i = 0; i < 6; i++) {
                    var sample = new PixelatedPanel(values[i + 1]);
                    BufferedImage image = render(sample, 468, 408);
                    int ox = (i % 2) * 480, oy = (i / 2) * 440;
                    for (int y = 0; y < 408; y += 14) for (int x = 0; x < 468; x += 14) {
                        g.setColor(((x / 14 + y / 14) & 1) == 0
                                ? new Color(0xd8d7d4) : new Color(0xc8c7c4));
                        g.fillRect(ox + x, oy + 24 + y, 14, 14);
                    }
                    g.drawImage(image, ox, oy + 24, null);
                    g.setColor(new Color(0x262423));
                    g.drawString(labels[i], ox + 12, oy + 17);
                }
                g.dispose();
                ImageIO.write(sheet, "PNG", destination.resolve(
                        "leverage-pixelated-containers-java.png").toFile());
                var content = new PixelatedPanel(
                        PixelatedPanel.Placement.OPPOSITE_CORNERS);
                JLabel title = new JLabel("Contenido del panel");
                title.setForeground(Color.WHITE);
                title.setFont(LauncherFonts.minecraftTen(24f));
                content.add(title, BorderLayout.NORTH);
                content.add(new PixelButton("Botón de ejemplo",
                        PixelButton.Variant.SECONDARY), BorderLayout.SOUTH);
                ImageIO.write(render(content, 720, 480), "PNG", destination.resolve(
                        "leverage-pixelated-content-java.png").toFile());
                ImageIO.write(render(gallery, 1040, 650), "PNG", destination.resolve(
                        "leverage-pixelated-gallery-java.png").toFile());
            } catch (java.io.IOException exception) {
                throw new AssertionError(exception);
            }
        });
    }

    private static PixelatedPanelGeometry.Geometry geometry(int width, int height,
            int unit, long seed, PixelatedPanel.Placement placement) {
        return PixelatedPanelGeometry.build(width, height,
                unit, seed, placement, List.of());
    }
    private static BufferedImage render(javax.swing.JComponent component,
            int width, int height) {
        component.setSize(width, height);
        layout(component);
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            component.paint(graphics);
        } finally {
            graphics.dispose();
        }
        return image;
    }
    private static void layout(Container container) {
        container.doLayout();
        for (Component component : container.getComponents()) if (
                component instanceof Container child) layout(child);
    }
    private static int[] pixels(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(),
                null, 0, image.getWidth());
    }
    private static Component find(Container container, String name) {
        for (Component component : container.getComponents()) {
            if (name.equals(component.getName())) return component;
            if (component instanceof Container child) {
                Component found = find(child, name);
                if (found != null) return found;
            }
        }
        return null;
    }
}
