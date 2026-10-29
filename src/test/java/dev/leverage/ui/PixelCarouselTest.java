package dev.leverage.ui;

import org.junit.jupiter.api.Test;
import javax.accessibility.AccessibleRole;
import javax.accessibility.AccessibleState;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PixelCarouselTest {
    @Test void itemsAreValidatedCopiedAndAllowDataWithoutAnImage() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PixelCarousel carousel = new PixelCarousel();
            ArrayList<PixelCarousel.Item> items = new ArrayList<>(items());
            carousel.setItems(items);
            items.clear();
            assertEquals(5, carousel.getItems().size());
            assertEquals(0, carousel.getSelectedIndex());
            assertThrows(UnsupportedOperationException.class,
                    () -> carousel.getItems().clear());
            assertThrows(IllegalArgumentException.class,
                    () -> new PixelCarousel.Item(" ", "", "", null));
            assertThrows(NullPointerException.class,
                    () -> new PixelCarousel.Item("Título", null, "", null));
            assertThrows(NullPointerException.class, () -> carousel.setItems(null));
            ArrayList<PixelCarousel.Item> invalid = new ArrayList<>();
            invalid.add(null);
            assertThrows(NullPointerException.class, () -> carousel.setItems(invalid));
            assertEquals(5, carousel.getItems().size());
            assertEquals(0, carousel.getSelectedIndex());
            carousel.setItems(List.of(new PixelCarousel.Item("Solo datos",
                    "", "Una descripción", null)));
            assertNull(carousel.getSelectedItem().image());
        });
    }

    @Test void nativeThumbnailsChangeSelectionAndNotifyOncePerChange() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PixelCarousel carousel = carousel();
            AtomicInteger changes = new AtomicInteger();
            javax.swing.event.ChangeListener listener = e -> changes.incrementAndGet();
            carousel.addChangeListener(listener);
            thumb(carousel, 2).doClick(0);
            assertEquals(2, carousel.getSelectedIndex());
            assertEquals("Elemento 3", carousel.getSelectedItem().title());
            assertEquals(1, changes.get());
            thumb(carousel, 2).doClick(0);
            carousel.setSelectedIndex(2);
            assertEquals(1, changes.get());
            assertTrue(thumb(carousel, 2).isSelected());
            assertFalse(thumb(carousel, 0).isSelected());
            carousel.removeChangeListener(listener);
            carousel.setSelectedIndex(0);
            assertEquals(1, changes.get());
            assertThrows(IndexOutOfBoundsException.class,
                    () -> carousel.setSelectedIndex(5));
        });
    }

    @Test void modesRetainSelectionAndShowOnlyTheRelevantControls() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PixelCarousel carousel = carousel();
            carousel.setSelectedIndex(3);
            carousel.setMode(PixelCarousel.Mode.DATA);
            layout(carousel, 876, 212);
            assertEquals(3, carousel.getSelectedIndex());
            assertFalse(named(carousel, "carousel-image").isVisible());
            assertFalse(named(carousel, "carousel-thumbnails").isVisible());
            assertTrue(named(carousel, "carousel-title").isVisible());
            assertTrue(named(carousel, "carousel-description").isVisible());
            assertEquals("Elemento 4", ((JLabel) named(carousel,
                    "carousel-title")).getText());
            carousel.setMode(PixelCarousel.Mode.IMAGES);
            assertEquals(3, carousel.getSelectedIndex());
            assertTrue(named(carousel, "carousel-image").isVisible());
            assertFalse(named(carousel, "carousel-more").isVisible());
            assertThrows(NullPointerException.class, () -> carousel.setMode(null));
        });
    }

    @Test void frameAndActiveThumbnailReproduceTheDarkInsetAndGreenOutline(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PixelCarousel carousel = carousel();
            layout(carousel, 625, 337);
            BufferedImage image = render(carousel);
            assertEquals(0x0e0d0d, rgb(image, 12, 12));
            assertEquals(0, rgb(image, 10, 0));
            assertEquals(0, rgb(image, 624, 10));
            assertWhiteTenPercent(image, 0, 10, new Color(0x0e0d0d));
            assertWhiteTenPercent(image, 10, 336, new Color(0x0e0d0d));
            assertEquals(rgb(image, 0, 10), rgb(image, 10, 336));
            assertEquals(0x1a1a1a, rgb(image, 0, 0));
            assertEquals(0x1a1a1a, rgb(image, 624, 336));
            assertEquals(rgb(image, 0, 10), rgb(image, 0, 336));
            // no double overlay at bottom-left
            assertEquals(0x0e0d0d, rgb(image, 4, 10));
            assertEquals(0x0e0d0d, rgb(image, 10, 332));
            Component rail = named(carousel, "carousel-thumbnails"),
                    selected = thumb(carousel, 0);
            int x = rail.getX() + selected.getX(), y = rail.getY() + selected.getY();
            assertEquals(0x6cc349, rgb(image, x, y));
            assertEquals(0x6cc349, rgb(image, x + 1, y + 8));
            assertEquals(0, rgb(image, x + 3, y + 8));
            assertEquals(0xff3333, rgb(image, x + 8, y + 8));
            assertEquals(0xff3333, rgb(image, 100, 100));
        });
    }

    @Test void insetShadowsRemainLighterAgainstTheGalleryAndFollowTheActualSurfaceColor(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PixelCarouselGallery gallery = new PixelCarouselGallery();
            layout(gallery, 1040, 617);
            PixelCarousel carousel = (PixelCarousel) named(gallery,
                    "pixel-carousel-images");
            Color outside = carousel.getParent().getBackground();
            BufferedImage frame = render(carousel);
            assertNotEquals(outside.getRGB() & 0xffffff, rgb(frame, 0, 10));
            assertTrue(new Color(frame.getRGB(0, 10)).getRed() > outside.getRed());
            assertTrue(new Color(frame.getRGB(10, frame.getHeight()
                    - 1)).getRed() > outside.getRed());
            for (PixelCarousel.Mode mode : PixelCarousel.Mode.values()) {
                carousel.setMode(mode);
                carousel.setBackground(new Color(100, 50, 0));
                layout(carousel, 625, 337);
                frame = render(carousel);
                for (int i = 0; i < 4; i++) {
                    assertWhiteTenPercent(frame, i, 12, carousel.getBackground());
                    assertWhiteTenPercent(frame, 12, 336 - i, carousel.getBackground());
                }
                assertEquals(0, rgb(frame, 12, 0));
                assertEquals(0, rgb(frame, 624, 12));
                assertEquals(carousel.getBackground().getRGB() & 0xffffff,
                        rgb(frame, 4, 12));
            }
        });
    }

    @Test void descriptionsCollapseWithEllipsisAndExpandWithoutLosingTheirContent(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PixelCarousel carousel = carousel();
            carousel.setMode(PixelCarousel.Mode.DATA);
            layout(carousel, 500, 212);
            JTextArea body = (JTextArea) named(carousel, "carousel-description");
            JButton more = (JButton) named(carousel, "carousel-more");
            assertTrue(more.isVisible());
            assertEquals(4, body.getLineCount());
            assertTrue(body.getText().endsWith("..."));
            assertFalse(body.isEditable());
            int collapsedHeight = carousel.getPreferredSize().height;
            AtomicInteger events = new AtomicInteger();
            carousel.addPropertyChangeListener("expanded",
                    event -> events.incrementAndGet());
            more.doClick(0);
            layout(carousel, 500, carousel.getPreferredSize().height);
            assertTrue(carousel.isExpanded());
            assertEquals("Mostrar menos", more.getText());
            assertTrue(body.getLineCount() > 4);
            assertTrue(carousel.getPreferredSize().height > collapsedHeight);
            assertEquals(compact(carousel.getSelectedItem().description()),
                    compact(body.getText()));
            assertEquals(1, events.get());
            assertTrue(more.getAccessibleContext().getAccessibleStateSet(
                    ).contains(AccessibleState.EXPANDED));
            more.doClick(0);
            assertFalse(carousel.isExpanded());
            assertEquals("Mostrar más", more.getText());
            assertEquals(2, events.get());
            assertTrue(more.getAccessibleContext().getAccessibleStateSet(
                    ).contains(AccessibleState.COLLAPSED));
            carousel.setExpanded(true);
            carousel.setSelectedIndex(1);
            assertFalse(carousel.isExpanded());
        });
    }

    @Test void moreAndLessUseTheActualBundledMinecraftSevenAtSixteenPixels(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PixelCarousel carousel = carousel();
            carousel.setMode(PixelCarousel.Mode.DATA);
            layout(carousel, 876, 220);
            JButton more = (JButton) named(carousel, "carousel-more");
            assertNotNull(LauncherFonts.class.getResource(
                    "/dev/leverage/ui/fonts/minecraft-seven.ttf"));
            for (boolean expanded : new boolean[] { false, true, false }) {
                carousel.setExpanded(expanded);
                SwingUtilities.updateComponentTreeUI(carousel);
                layout(carousel, 876, carousel.getPreferredSize().height);
                assertEquals(expanded ? "Mostrar menos" : "Mostrar más", more.getText());
                assertEquals("Minecraft Seven", more.getFont().getFamily());
                assertEquals("Minecraft Seven", more.getFont().getFontName());
                assertEquals(LauncherFonts.minecraftSeven(16), more.getFont());
                assertEquals(16, more.getFont().getSize());
                assertEquals(-1, more.getFont().canDisplayUpTo(more.getText()));
                assertTrue(more.getWidth() >= more.getFontMetrics(more.getFont(
                        )).stringWidth(more.getText()) + 24);
            }
        });
    }

    @Test void shortDescriptionsDoNotExposeAnUnnecessaryMoreButtonAndMetadataIsOptional(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PixelCarousel carousel = new PixelCarousel(PixelCarousel.Mode.DATA);
            carousel.setItems(List.of(new PixelCarousel.Item("Título",
                    "", "Descripción corta.", null)));
            layout(carousel, 876, 212);
            assertFalse(named(carousel, "carousel-more").isVisible());
            assertFalse(named(carousel, "carousel-metadata").isVisible());
            assertEquals("Descripción corta.", ((JTextArea) named(carousel,
                    "carousel-description")).getText());
            int withoutDate = carousel.getPreferredSize().height;
            carousel.setItems(List.of(new PixelCarousel.Item("Título",
                    "Junio 2026", "Descripción corta.", null)));
            layout(carousel, 876, 212);
            assertTrue(named(carousel, "carousel-metadata").isVisible());
            assertTrue(carousel.getPreferredSize().height > withoutDate);
        });
    }

    @Test void descriptionWrappingHandlesParagraphsLongWordsUnicodeAndLiteralMarkup(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FontMetrics metrics = new JLabel().getFontMetrics(
                    LauncherFonts.minecraftSeven(17));
            var wrapped = CarouselTextLayout.wrap(
                    "primera línea\n\nsegunda palabraexcesivamentelargaparapartir 🚀"
                    + "🚀", metrics, 80);
            assertTrue(wrapped.contains(""));
            for (String line : wrapped) assertTrue(metrics.stringWidth(line) <= 80);
            assertEquals("primeralíneasegundapalabraexcesivamentelargaparapartir🚀🚀",
                    compact(String.join("", wrapped)));
            assertEquals(List.of("🚀", "🚀"), CarouselTextLayout.wrap("🚀🚀",
                    metrics, 1));
            assertEquals(List.of(), CarouselTextLayout.wrap("", metrics, 100));
            assertEquals("", CarouselTextLayout.ellipsize("largo", metrics, 0));
            PixelCarousel carousel = new PixelCarousel(PixelCarousel.Mode.DATA);
            carousel.setItems(List.of(new PixelCarousel.Item("<html>Literal",
                    "<html>Dato", "<b>No es HTML</b> & texto", null)));
            layout(carousel, 876, 212);
            assertNull(((JLabel) named(carousel,
                    "carousel-title")).getClientProperty("html"));
            assertTrue(((JTextArea) named(carousel,
                    "carousel-description")).getText().contains("<b>No es HTML</b>"));
        });
    }

    @Test void selectionScrollsAnOverflowingRailWithoutAddingScrollbarsToTheComponent(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PixelCarousel carousel = carousel();
            List<PixelCarousel.Item> many = new ArrayList<>();
            for (int i = 0; i < 20; i++) many.add(items().get(i % 5));
            carousel.setItems(many);
            layout(carousel, 480, 220);
            carousel.setSelectedIndex(19);
            layout(carousel, 480, 220);
            Component rail = named(carousel, "carousel-thumbnails"),
                    selected = thumb(carousel, 19);
            assertTrue(selected.getY() >= 0);
            assertTrue(selected.getY() + selected.getHeight() <= rail.getHeight());
            assertTrue(thumb(carousel, 0).getY() < 0);
            carousel.setSelectedIndex(0);
            layout(carousel, 480, 220);
            assertEquals(0, thumb(carousel, 0).getY());
            assertFalse(hasType(carousel, JScrollPane.class));
        });
    }

    @Test void keyboardWheelAndNativeSpaceActivationRemainUsableAndRespectDisabledState(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PixelCarousel carousel = carousel();
            JComponent rail = (JComponent) named(carousel, "carousel-thumbnails");
            key(rail, "UP");
            assertEquals(4, carousel.getSelectedIndex());
            key(rail, "DOWN");
            assertEquals(0, carousel.getSelectedIndex());
            key(rail, "END");
            assertEquals(4, carousel.getSelectedIndex());
            key(rail, "HOME");
            assertEquals(0, carousel.getSelectedIndex());
            MouseWheelEvent wheel = new MouseWheelEvent(rail, MouseWheelEvent.MOUSE_WHEEL,
                    1, 0, 10, 10, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, 1);
            rail.dispatchEvent(wheel);
            assertEquals(1, carousel.getSelectedIndex());
            assertTrue(wheel.isConsumed());
            JToggleButton button = thumb(carousel, 2);
            button.getActionMap().get("pressed").actionPerformed(
                    new ActionEvent(button, 0, "pressed"));
            button.getActionMap().get("released").actionPerformed(
                    new ActionEvent(button, 0, "released"));
            assertEquals(2, carousel.getSelectedIndex());
            assertEquals(AccessibleRole.PAGE_TAB,
                    button.getAccessibleContext().getAccessibleRole());
            assertTrue(button.getAccessibleContext().getAccessibleStateSet(
                    ).contains(AccessibleState.SELECTED));
            assertTrue(button.isFocusable());
            assertFalse(thumb(carousel, 0).isFocusable());
            carousel.setEnabled(false);
            key(rail, "RIGHT");
            thumb(carousel, 0).doClick(0);
            assertEquals(2, carousel.getSelectedIndex());
            assertFalse(((JButton) named(carousel, "carousel-more")).isEnabled());
            carousel.setEnabled(true);
            key(rail, "RIGHT");
            assertEquals(3, carousel.getSelectedIndex());
            carousel.setMode(PixelCarousel.Mode.DATA);
            key(rail, "RIGHT");
            assertEquals(3, carousel.getSelectedIndex());
            assertNotEquals("carousel-RIGHT", ((JComponent) named(carousel,
                    "carousel-description")).getInputMap().get(
                    KeyStroke.getKeyStroke("RIGHT")));
            assertNull(carousel.getInputMap(
                    JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(
                    KeyStroke.getKeyStroke("RIGHT")));
        });
    }

    @Test void imagesPreserveTheirAspectRatioAndMissingImagesHaveAPlaceholder(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PixelCarousel carousel = new PixelCarousel();
            carousel.setItems(List.of(new PixelCarousel.Item("Vertical",
                    "", "", solid(20, 80, Color.CYAN)),
                    new PixelCarousel.Item("Sin imagen", "", "", null)));
            layout(carousel, 625, 337);
            BufferedImage image = render(carousel);
            Component main = named(carousel, "carousel-image");
            assertEquals(0x0e0d0d, rgb(image, main.getX(), main.getY() + 10));
            assertEquals(0xffff, rgb(image, main.getX() + main.getWidth() / 2,
                    main.getY() + main.getHeight() / 2));
            carousel.setSelectedIndex(1);
            image = render(carousel);
            assertEquals(0x262423, rgb(image, main.getX() + 10, main.getY() + 10));
        });
    }

    @Test void emptyAndReplacedCollectionsNeverLeaveStaleImagesOrText() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PixelCarousel carousel = new PixelCarousel();
            layout(carousel, 625, 337);
            assertEquals(-1, carousel.getSelectedIndex());
            assertNull(carousel.getSelectedItem());
            assertFalse(named(carousel, "carousel-image").isVisible());
            assertThrows(IndexOutOfBoundsException.class,
                    () -> carousel.setSelectedIndex(0));
            carousel.setItems(items());
            carousel.setSelectedIndex(4);
            carousel.setExpanded(true);
            carousel.setItems(List.of());
            layout(carousel, 625, 337);
            assertEquals(-1, carousel.getSelectedIndex());
            assertFalse(carousel.isExpanded());
            assertEquals(0, ((Container) named(carousel,
                    "carousel-thumbnails")).getComponentCount());
            carousel.setMode(PixelCarousel.Mode.DATA);
            layout(carousel, 876, 212);
            assertEquals("", ((JTextArea) named(carousel,
                    "carousel-description")).getText());
            carousel.setItems(items());
            assertEquals(0, carousel.getSelectedIndex());
        });
    }

    @Test void configurableSizesAndTinyLayoutsRemainValid() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PixelCarousel carousel = carousel();
            carousel.setContentPadding(12);
            carousel.setThumbnailWidth(60);
            carousel.setThumbnailGap(8);
            carousel.setCollapsedLineCount(2);
            assertEquals(12, carousel.getContentPadding());
            assertEquals(60, carousel.getThumbnailWidth());
            assertEquals(8, carousel.getThumbnailGap());
            assertEquals(2, carousel.getCollapsedLineCount());
            layout(carousel, 220, 160);
            assertNonnegative(carousel);
            carousel.setMode(PixelCarousel.Mode.DATA);
            layout(carousel, 220, 120);
            assertNonnegative(carousel);
            assertEquals(2, ((JTextArea) named(carousel,
                    "carousel-description")).getLineCount());
            assertThrows(IllegalArgumentException.class,
                    () -> carousel.setContentPadding(7));
            assertThrows(IllegalArgumentException.class,
                    () -> carousel.setThumbnailWidth(0));
            assertThrows(IllegalArgumentException.class,
                    () -> carousel.setThumbnailGap(-1));
            assertThrows(IllegalArgumentException.class,
                    () -> carousel.setCollapsedLineCount(0));
        });
    }

    @Test void galleryLinksBothModesWhileKeepingAllEarlierComponentsAvailable(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                var method = ComponentGallery.class.getDeclaredMethod("createContent");
                method.setAccessible(true);
                JPanel root = (JPanel) method.invoke(null);
                layout(root, 1040, 650);
                JTabbedPane tabs = (JTabbedPane) root.getComponent(0);
                assertEquals(8, tabs.getTabCount());
                assertEquals(7, tabs.getSelectedIndex());
                assertInstanceOf(PixelTabsGallery.class, tabs.getComponentAt(4));
                Component gallery = tabs.getComponentAt(5);
                PixelCarousel images = (PixelCarousel) named(gallery,
                        "pixel-carousel-images"), data = (PixelCarousel) named(gallery,
                        "pixel-carousel-data");
                ((JButton) named(gallery, "carousel-gallery-next")).doClick(0);
                assertEquals(1, images.getSelectedIndex());
                assertEquals(1, data.getSelectedIndex());
                ((JComboBox<?>) named(gallery,
                        "carousel-gallery-mode")).setSelectedIndex(1);
                layout(root, 1040, 650);
                assertFalse(images.isVisible());
                assertTrue(data.isVisible());
                ((JButton) named(gallery, "carousel-gallery-previous")).doClick(0);
                assertEquals(0, data.getSelectedIndex());
                ((JComboBox<?>) named(gallery,
                        "carousel-gallery-mode")).setSelectedIndex(2);
                layout(root, 820, 520);
                assertTrue(images.isVisible());
                assertTrue(data.isVisible());
                JScrollPane scroll = (JScrollPane) named(gallery,
                        "carousel-gallery-scroll");
                assertFalse(scroll.getHorizontalScrollBar().isVisible());
                data.setExpanded(true);
                layout(root, 820, 520);
                assertTrue(data.getHeight() > 200);
            } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
        });
    }

    @Test void exportsNativeComponentPreviewsWhenRequested() throws Exception {
        String directory = System.getenv("LEVERAGE_CAROUSEL_PREVIEW");
        if (directory == null || directory.isBlank()) return;
        Path output = Path.of(directory);
        Files.createDirectories(output);
        SwingUtilities.invokeAndWait(() -> {
            try {
                com.formdev.flatlaf.FlatDarkLaf.setup();
                PixelCarouselGallery gallery = new PixelCarouselGallery();
                layout(gallery, 1040, 617);
                PixelCarousel images = (PixelCarousel) named(gallery,
                        "pixel-carousel-images");
                layout(images, 625, 337);
                ImageIO.write(render(images), "png", output.resolve(
                        "leverage-carousel-images.png").toFile());
                PixelCarousel text = new PixelCarousel(PixelCarousel.Mode.DATA);
                text.setItems(images.getItems());
                layout(text, 876, text.getPreferredSize().height);
                ImageIO.write(render(text), "png", output.resolve(
                        "leverage-carousel-data.png").toFile());
                text.setExpanded(true);
                layout(text, 876, text.getPreferredSize().height);
                ImageIO.write(render(text), "png", output.resolve(
                        "leverage-carousel-expanded.png").toFile());
                layout(gallery, 820, 487);
                ImageIO.write(render(gallery), "png", output.resolve(
                        "leverage-carousel-compact.png").toFile());
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    private static PixelCarousel carousel() {
        PixelCarousel carousel = new PixelCarousel();
        carousel.setItems(items());
        return carousel;
    }
    private static List<PixelCarousel.Item> items() {
        List<PixelCarousel.Item> items = new ArrayList<>();
        Color[] colors = { new Color(0xff3333), Color.BLUE,
                Color.CYAN, Color.YELLOW, Color.MAGENTA };
        for (int i = 0; i < 5; i++) items.add(new PixelCarousel.Item(
                "Elemento " + (i + 1), "Junio 2026",
                ("Una descripción con muchas palabras para comprobar el ajuste del con"
                + "tenido. ").repeat(15), solid(160, 90, colors[i])));
        return items;
    }
    private static BufferedImage solid(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.dispose();
        return image;
    }
    private static JToggleButton thumb(PixelCarousel carousel, int index) {
        return (JToggleButton) named(carousel, "carousel-thumbnail-" + index);
    }
    private static void key(JComponent owner, String key) {
        owner.getActionMap().get("carousel-" + key).actionPerformed(
                new ActionEvent(owner, 0, key));
    }
    private static void layout(Component c, int width, int height) {
        c.setSize(width, height);
        layout(c);
    }
    private static void layout(Component c) {
        if (c instanceof Container parent) {
            parent.doLayout();
            for (Component child : parent.getComponents()) layout(child);
        }
    }
    private static BufferedImage render(Component c) {
        BufferedImage image = new BufferedImage(c.getWidth(),
                c.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        c.printAll(g);
        g.dispose();
        return image;
    }
    private static int rgb(BufferedImage image, int x, int y) {
        return image.getRGB(x, y) & 0xffffff;
    }
    private static void assertWhiteTenPercent(BufferedImage image,
            int x, int y, Color background) {
        Color actual = new Color(image.getRGB(x, y));
        assertEquals(background.getRed() * .9 + 255 * .1, actual.getRed(), 1);
        assertEquals(background.getGreen() * .9 + 255 * .1, actual.getGreen(), 1);
        assertEquals(background.getBlue() * .9 + 255 * .1, actual.getBlue(), 1);
    }
    private static String compact(String value) { return value.replaceAll("\\s+", ""); }
    private static Component named(Component c, String name) {
        if (name.equals(c.getName())) return c;
        if (c instanceof Container parent) for (Component child
                : parent.getComponents()) {
            Component found = named(child, name);
            if (found != null) return found;
        }
        return null;
    }
    private static boolean hasType(Component c, Class<?> type) {
        if (type.isInstance(c)) return true;
        if (c instanceof Container parent) for (Component child
                : parent.getComponents()) if (hasType(child, type)) return true;
        return false;
    }
    private static void assertNonnegative(Component c) {
        assertTrue(c.getWidth() >= 0 && c.getHeight() >= 0, c.getName());
        if (c instanceof Container parent) for (Component child
                : parent.getComponents()) assertNonnegative(child);
    }
}
