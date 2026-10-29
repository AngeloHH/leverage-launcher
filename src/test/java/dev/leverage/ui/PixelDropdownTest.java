package dev.leverage.ui;

import org.junit.jupiter.api.Test;
import javax.accessibility.AccessibleRole;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.plaf.basic.BasicComboPopup;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PixelDropdownTest {
    @Test void arrowOnlyMenuKeepsSquarePopupAndHidesOnlyTheClosedLabel(
            ) throws Exception {
        SwingUtilities.invokeAndWait(()-> {
            var dropdown=countries();
            dropdown.setArrowOnly(true);
            var popup=popup(dropdown);
            assertEquals(0, popup.getClientProperty("Popup.borderCornerRadius"));
            assertEquals(false, popup.getClientProperty("Popup.dropShadowPainted"));
            var closed= (JLabel)dropdown.getRenderer().getListCellRendererComponent(
                    new JList<>(), "United States", -1, false, false);
            assertEquals("", closed.getText());
            var option= (JLabel)dropdown.getRenderer().getListCellRendererComponent(
                    new JList<>(), "United States", 0, false, false);
            assertEquals("UNITED STATES", option.getText());
            layout(dropdown, 42, 40);
            var image=render(dropdown);
            assertEquals(0x898481, rgb(image, 0, 0));
            assertEquals(0x898481, rgb(image, 41, 39));
        });
    }
    @Test void closedDropdownMatchesTheBorderSurfaceShadowAndFont() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PixelDropdown<String> dropdown = countries();
            layout(dropdown, 434, 48);
            BufferedImage image = render(dropdown);
            assertEquals(48, dropdown.getPreferredSize().height);
            assertEquals(135, dropdown.getMinimumSize().width);
            assertEquals(3, dropdown.getMaximumRowCount());
            assertEquals("Minecraft Seven", dropdown.getFont().getFamily());
            assertEquals(16, dropdown.getFont().getSize());
            assertEquals(0x898481, rgb(image, 0, 12));
            assertEquals(0x898481, rgb(image, 12, 0));
            assertEquals(0x898481, rgb(image, 433, 12));
            assertEquals(0x262423, rgb(image, 12, 12));
            assertEquals(0x131211, rgb(image, 12, 2));
            assertEquals(0x131211, rgb(image, 12, 7));
            assertEquals(0x262423, rgb(image, 12, 8));
            assertEquals(0x898481, rgb(image, 12, 47));
            assertEquals(AccessibleRole.COMBO_BOX,
                    dropdown.getAccessibleContext().getAccessibleRole());
            assertFalse(dropdown.isEditable());
            assertThrows(IllegalArgumentException.class,
                    () -> dropdown.setEditable(true));
        });
    }

    @Test void optionRowsMatchTheBevelAndKeepTheCheckOnTheCommittedSelection(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PixelDropdown<String> dropdown = countries();
            Component first = cell(dropdown, 0, true);
            BufferedImage selected = render(first);
            assertEquals("UNITED STATES", ((JLabel) first).getText());
            assertEquals(48, first.getPreferredSize().height);
            assertEquals(0x56514e, rgb(selected, 12, 12));
            assertEquals(0x171615, rgb(selected, 0, 12));
            assertEquals(0x171615, rgb(selected, 12, 47));
            assertEquals(0x817c7a, rgb(selected, 12, 0));
            assertEquals(0x817c7a, rgb(selected, 433, 12));
            assertTrue(count(selected, 0x6cc349) > 10);
            BufferedImage hovered = render(cell(dropdown, 1, true));
            assertEquals(0x56514e, rgb(hovered, 12, 12));
            assertEquals(0, count(hovered, 0x6cc349));
            BufferedImage ordinary = render(cell(dropdown, 2, false));
            assertEquals(0x6b6562, rgb(ordinary, 12, 12));
            Component value = dropdown.getRenderer().getListCellRendererComponent(
                    new JList<>(), "United States", -1, false, false);
            assertEquals("United States", ((JLabel) value).getText());
            dropdown.setSelectedIndex(1);
            assertTrue(count(render(cell(dropdown, 1, true)), 0x6cc349) > 10);
            assertEquals(0, count(render(cell(dropdown, 0, false)), 0x6cc349));
        });
    }

    @Test void nativeModelSelectionAndActionListenersContinueWorking() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PixelDropdown<String> dropdown = countries();
            AtomicInteger events = new AtomicInteger();
            dropdown.addActionListener(event -> events.incrementAndGet());
            dropdown.setSelectedIndex(1);
            assertEquals("Uruguay", dropdown.getSelectedItem());
            assertEquals(1, events.get());
            dropdown.addItem("Argentina");
            assertEquals(4, dropdown.getItemCount());
            dropdown.removeItem("Uruguay");
            assertNotEquals("Uruguay", dropdown.getSelectedItem());
            dropdown.setModel(new DefaultComboBoxModel<>(new String[] { "Uno", "Dos" }));
            dropdown.setSelectedIndex(1);
            assertEquals("Dos", dropdown.getSelectedItem());
            BasicComboPopup popup = popup(dropdown);
            assertSame(dropdown.getModel(), popup.getList().getModel());
            assertSame(dropdown.getRenderer(), popup.getList().getCellRenderer());
            assertEquals(48, popup.getList().getFixedCellHeight());
            assertEquals(javax.swing.plaf.basic.BasicListUI.class,
                    popup.getList().getUI().getClass());
            Component actual=popup.getList().getCellRenderer(
                    ).getListCellRendererComponent(popup.getList(),
                    "Uruguay", 1, true, false);
            actual.setSize(434, 48);
            assertEquals(0x56514e, rgb(render(actual), 12, 12));
        });
    }

    @Test void labelMappingPreservesTypedValuesAndLocaleIndependentUppercase(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            record Option(int id, String name) { }
            Option first = new Option(1, "United States"),
                    second = new Option(2, "Uruguay");
            PixelDropdown<Option> dropdown = new PixelDropdown<>(List.of(first,
                    second), Option::name);
            dropdown.setSelectedIndex(1);
            assertSame(second, dropdown.getSelectedItem());
            Locale old = Locale.getDefault();
            try {
                Locale.setDefault(Locale.forLanguageTag("tr-TR"));
                JLabel rendered = (JLabel) dropdown.getRenderer(
                        ).getListCellRendererComponent(new JList<>(),
                        first, 0, false, false);
                assertEquals("UNITED STATES", rendered.getText());
            }
            finally { Locale.setDefault(old); }
            dropdown.setSelectedIndex(0);
            assertTrue(dropdown.selectWithKeyChar('u'));
            assertSame(second, dropdown.getSelectedItem());
        });
    }

    @Test void typeAheadCyclesMatchesAndNativeArrowActionsChangeSelection(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PixelDropdown<String> dropdown = countries();
            assertTrue(dropdown.selectWithKeyChar('u'));
            assertEquals(1, dropdown.getSelectedIndex());
            assertTrue(dropdown.selectWithKeyChar('u'));
            assertEquals(0, dropdown.getSelectedIndex());
            JPanel host = new JPanel();
            host.add(dropdown);
            host.addNotify();
            try {
                Action next = dropdown.getActionMap().get("selectNext2");
                assertNotNull(next);
                next.actionPerformed(new ActionEvent(dropdown, 0, "selectNext2"));
                assertEquals(1, dropdown.getSelectedIndex());
            }
            finally { host.removeNotify(); }
            assertNotNull(dropdown.getActionMap().get("hidePopup"));
            assertNotNull(dropdown.getActionMap().get("enterPressed"));
            assertNotNull(dropdown.getInputMap(
                    JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(
                    KeyStroke.getKeyStroke("DOWN")));
            dropdown.setEnabled(false);
            assertFalse(dropdown.isPopupVisible());
            dropdown.setPopupVisible(true);
            assertFalse(dropdown.isPopupVisible());
            Component arrow = named(dropdown, "pixel-dropdown-arrow");
            assertNotNull(arrow);
            assertFalse(arrow.isEnabled());
        });
    }

    @Test void emptyModelsPlaceholdersAndExplicitSizesRemainValid() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PixelDropdown<String> empty = new PixelDropdown<>(List.of());
            empty.setPlaceholder("Elegir versión");
            layout(empty, 200, 48);
            render(empty);
            assertEquals(-1, empty.getSelectedIndex());
            empty.setPopupVisible(true);
            assertFalse(empty.isPopupVisible());
            assertEquals("Elegir versión", empty.getPlaceholder());
            empty.addItem("Nueva");
            assertEquals("Nueva", empty.getSelectedItem());
            empty.removeAllItems();
            assertEquals(-1, empty.getSelectedIndex());
            empty.setPreferredSize(new Dimension(434, 48));
            assertEquals(434, empty.getPreferredSize().width);
            assertThrows(IllegalArgumentException.class,
                    () -> empty.setMaximumRowCount(0));
            assertThrows(NullPointerException.class, () -> empty.setPlaceholder(null));
            assertThrows(NullPointerException.class,
                    () -> new PixelDropdown<String>(null));
        });
    }

    @Test void nativePopupAndGalleryUseTheSixPixelGreenScrollbarWithInsetShadows(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PixelDropdown<String> dropdown = many();
            dropdown.setSelectedIndex(4);
            JScrollPane scroll = (JScrollPane) popup(dropdown).getComponent(0);
            layout(scroll, 430, 144);
            assertTrue(scroll.getVerticalScrollBar().isVisible());
            assertEquals(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER,
                    scroll.getHorizontalScrollBarPolicy());
            assertEquals(6, scroll.getVerticalScrollBar().getWidth());
            JScrollBar bar = scroll.getVerticalScrollBar();
            BufferedImage image = render(bar);
            assertTrue(count(image, 0x6cc349) >= 40);
            int thumbY = -1;
            for (int y = 0; y < image.getHeight(); y++) if (rgb(image,
                    2, y) == 0x6cc349) {
                thumbY = y;
                break;
            }
            assertTrue(thumbY >= 0);
            assertTrue(new Color(image.getRGB(1, thumbY)).getGreen(
                    ) < new Color(image.getRGB(2, thumbY)).getGreen());
            assertTrue(new Color(image.getRGB(4, thumbY)).getGreen(
                    ) > new Color(image.getRGB(2, thumbY)).getGreen());
            JComponent preview = dropdown.createOptionsPreview(3);
            layout(preview, 434, 144);
            JScrollPane inspection = (JScrollPane) preview;
            assertEquals(4 * 48, inspection.getViewport().getViewPosition().y);
            assertEquals(6, inspection.getVerticalScrollBar().getWidth());
            SwingUtilities.updateComponentTreeUI(preview);
            layout(preview, 434, 144);
            assertEquals(6, inspection.getVerticalScrollBar().getWidth());
            assertTrue(count(render(inspection.getVerticalScrollBar()), 0x6cc349) >= 40);
            dropdown.setMaximumRowCount(5);
            assertEquals(5, dropdown.getMaximumRowCount());
        });
    }

    @Test void optionsHaveNoOuterFrameAndScrollbarBordersStayOffBlackOnEverySide(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PixelDropdown<String> dropdown = many();
            dropdown.setSelectedIndex(4);
            BasicComboPopup popup = popup(dropdown);
            assertEquals(new Insets(0, 0, 0, 0),
                    popup.getBorder().getBorderInsets(popup));
            JScrollPane nativeScroll = (JScrollPane) popup.getComponent(0);
            assertEquals(new Insets(0, 0, 0, 0), nativeScroll.getInsets());
            JScrollPane preview = (JScrollPane) dropdown.createOptionsPreview(3);
            assertEquals(144, preview.getPreferredSize().height);
            for (boolean refresh : new boolean[] { false, true }) {
                if (refresh) SwingUtilities.updateComponentTreeUI(preview);
                layout(preview, 434, 144);
                assertEquals(new Insets(0, 0, 0, 0), preview.getInsets());
                BufferedImage options = render(preview);
                assertEquals(0, count(options, 0x898481));
                assertEquals(0x171615, rgb(options, 0, 12));
                assertEquals(0x817c7a, rgb(options, 12, 0));
                JScrollBar bar = preview.getVerticalScrollBar();
                assertEquals(6, bar.getWidth());
                BufferedImage image = render(bar);
                for (int x = 0; x < image.getWidth(); x++) {
                    assertEquals(0x171615, rgb(image, x, 0));
                    assertEquals(0x171615, rgb(image, x, image.getHeight() - 1));
                }
                for (int y = 0; y < image.getHeight(); y++) {
                    assertEquals(0x171615, rgb(image, 0, y));
                    assertEquals(0x171615, rgb(image, image.getWidth() - 1, y));
                }
                assertTrue(count(image, 0x6cc349) >= 40);
            }
        });
    }

    @Test void
            shortMenusHaveNoUnnecessaryScrollbarAndInspectionDoesNotDoubleSelectionEvents(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PixelDropdown<String> dropdown = countries();
            JScrollPane preview = (JScrollPane) dropdown.createOptionsPreview(3);
            layout(preview, 434, 144);
            assertFalse(preview.getVerticalScrollBar().isVisible());
            assertFalse(preview.getHorizontalScrollBar().isVisible());
            AtomicInteger events = new AtomicInteger();
            dropdown.addActionListener(event -> events.incrementAndGet());
            dropdown.setSelectedIndex(1);
            assertEquals(1, events.get());
            JList<?> list = (JList<?>) preview.getViewport().getView();
            assertEquals(1, list.getSelectedIndex());
            list.setSelectedIndex(2);
            assertEquals(2, dropdown.getSelectedIndex());
            assertEquals(2, events.get());
            dropdown.setEnabled(false);
            assertFalse(list.isEnabled());
            dropdown.setEnabled(true);
            assertTrue(list.isEnabled());
        });
    }

    @Test void fontStyleAndRenderingSurviveLookAndFeelUpdatesAndTreatLabelsAsPlainText(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PixelDropdown<String> dropdown = new PixelDropdown<>(
                    List.of("<html>No es HTML", "Otra"));
            SwingUtilities.updateComponentTreeUI(dropdown);
            layout(dropdown, 434, 48);
            assertEquals("Minecraft Seven", dropdown.getFont().getFamily());
            assertEquals(16, dropdown.getFont().getSize());
            assertEquals(0x131211, rgb(render(dropdown), 12, 3));
            Component label = cell(dropdown, 0, true);
            assertEquals("<HTML>NO ES HTML", ((JLabel) label).getText());
            assertNull(((JLabel) label).getClientProperty("html"));
            assertEquals(-1, dropdown.getFont().canDisplayUpTo("México España Uruguay"));
        });
    }

    @Test void galleryIncludesTheNewDropdownWithoutRemovingOtherComponents(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                var create = ComponentGallery.class.getDeclaredMethod("createContent");
                create.setAccessible(true);
                JPanel root = (JPanel) create.invoke(null);
                layout(root, 820, 520);
                JTabbedPane tabs = (JTabbedPane) root.getComponent(0);
                assertEquals(8, tabs.getTabCount());
                assertEquals(7, tabs.getSelectedIndex());
                assertInstanceOf(PixelTabsGallery.class, tabs.getComponentAt(4));
                assertInstanceOf(PixelCarouselGallery.class, tabs.getComponentAt(5));
                assertInstanceOf(PixelDropdownGallery.class, tabs.getComponentAt(6));
                Component gallery = tabs.getComponentAt(6);
                PixelDropdown<?> dropdown = (PixelDropdown<?>) named(gallery,
                        "dropdown-countries");
                dropdown.setSelectedIndex(5);
                assertEquals("Seleccionado: Uruguay", ((JLabel) named(gallery,
                        "dropdown-countries-selection")).getText());
                ((JCheckBox) named(gallery, "dropdown-gallery-disabled")).doClick(0);
                assertFalse(dropdown.isEnabled());
                ((JComboBox<?>) named(gallery,
                        "dropdown-gallery-view")).setSelectedIndex(1);
                assertFalse(named(gallery, "dropdown-versions").isEnabled());
                layout(root, 820, 520);
                assertEquals(434, dropdown.getWidth());
            } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
        });
    }

    @Test void exportsActualComponentPreviewsWhenRequested() throws Exception {
        String directory = System.getenv("LEVERAGE_DROPDOWN_PREVIEW");
        if (directory == null || directory.isBlank()) return;
        Path output = Path.of(directory);
        Files.createDirectories(output);
        SwingUtilities.invokeAndWait(() -> {
            try {
                com.formdev.flatlaf.FlatDarkLaf.setup();
                PixelDropdown<String> dropdown = many();
                dropdown.setSelectedIndex(4);
                layout(dropdown, 434, 48);
                JComponent options = dropdown.createOptionsPreview(3);
                layout(options, 434, 144);
                BufferedImage image = new BufferedImage(482, 306,
                        BufferedImage.TYPE_INT_RGB);
                Graphics2D g = image.createGraphics();
                g.setColor(new Color(0x171615));
                g.fillRect(0, 0, 482, 306);
                g.setFont(LauncherFonts.minecraftSeven(14));
                g.setColor(Color.WHITE);
                g.drawString("Selector", 24, 25);
                g.drawImage(render(dropdown), 24, 38, null);
                g.drawString("Opciones del menú", 24, 115);
                g.drawImage(render(options), 24, 130, null);
                g.dispose();
                ImageIO.write(image, "png", output.resolve(
                        "leverage-dropdown-reference.png").toFile());
                PixelDropdownGallery gallery = new PixelDropdownGallery();
                layout(gallery, 1040, 617);
                ImageIO.write(render(gallery), "png", output.resolve(
                        "leverage-dropdown-gallery.png").toFile());
                layout(gallery, 820, 487);
                ImageIO.write(render(gallery), "png", output.resolve(
                        "leverage-dropdown-compact.png").toFile());
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    private static PixelDropdown<String> countries() {
        return new PixelDropdown<>(List.of("United States", "Uruguay", "Venezuela"));
    }
    private static PixelDropdown<String> many() {
        return new PixelDropdown<>(List.of("Argentina", "Chile", "España",
                "México", "United States", "Uruguay", "Venezuela"));
    }
    private static BasicComboPopup popup(PixelDropdown<?> dropdown) {
        return (BasicComboPopup) dropdown.getUI().getAccessibleChild(dropdown, 0);
    }
    private static Component cell(PixelDropdown<String> dropdown,
            int index, boolean highlighted) {
        Component c = dropdown.getRenderer().getListCellRendererComponent(new JList<>(),
                dropdown.getItemAt(index), index, highlighted, false);
        layout(c, 434, 48);
        return c;
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
    private static int count(BufferedImage image, int color) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0;
                x < image.getWidth(); x++) if (rgb(image, x, y) == color) count++;
        return count;
    }
    private static Component named(Component c, String name) {
        if (name.equals(c.getName())) return c;
        if (c instanceof Container parent) for (Component child
                : parent.getComponents()) {
            Component found = named(child, name);
            if (found != null) return found;
        }
        return null;
    }
}
