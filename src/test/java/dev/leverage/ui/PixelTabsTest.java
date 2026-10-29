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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class PixelTabsTest {
    @Test void reproducesTheReferencePaletteAndSixPixelIndicator() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PixelTabs tabs = years();
            layout(tabs, 608, 64);
            BufferedImage image = render(tabs);
            assertEquals(64, tabs.getTabBar().getHeight());
            assertEquals(0x171615, rgb(image, 0, 0));
            assertEquals(0, rgb(image, 20, 49));
            assertEquals(0xa0e081, rgb(image, 20, 50));
            assertEquals(0xa0e081, rgb(image, 20, 51));
            assertEquals(0x86d562, rgb(image, 20, 52));
            assertEquals(0x86d562, rgb(image, 20, 53));
            assertEquals(0x649f49, rgb(image, 20, 54));
            assertEquals(0x649f49, rgb(image, 20, 55));
            assertEquals(0, rgb(image, 20, 56));
            assertEquals(0x131211, rgb(image, 607, 50));
            assertEquals(0x262423, rgb(image, 607, 53));
            assertEquals(new Color(0x86d562), tabs.getActiveColor());
            assertEquals(new Color(0xaba09c), tabs.getInactiveColor());
            assertEquals(27, tabs.getTabFont().getSize());
            assertEquals(20, tabs.indicatorBounds().left());
            assertEquals(button(tabs, 0).getWidth(), tabs.indicatorBounds().width());
        });
    }

    @Test void realButtonsSwitchCardsAndNotifyOnlyOnChanges() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PixelTabs tabs = new PixelTabs();
            JPanel first = new JPanel(), second = new JPanel();
            tabs.addTab("UNO", first);
            tabs.addTab("DOS", second);
            layout(tabs, 608, 300);
            AtomicInteger changes = new AtomicInteger();
            javax.swing.event.ChangeListener listener = e -> changes.incrementAndGet();
            tabs.addChangeListener(listener);
            assertTrue(first.isVisible());
            assertFalse(second.isVisible());
            button(tabs, 1).doClick(0);
            assertEquals(1, tabs.getSelectedIndex());
            assertFalse(first.isVisible());
            assertTrue(second.isVisible());
            assertEquals(1, changes.get());
            button(tabs, 1).doClick(0);
            tabs.setSelectedIndex(1);
            assertEquals(1, changes.get());
            tabs.removeChangeListener(listener);
            tabs.setSelectedIndex(0);
            assertEquals(1, changes.get());
            assertSame(first, tabs.getComponentAt(0));
            assertTrue(button(tabs, 0).isSelected());
            assertFalse(button(tabs, 1).isSelected());
        });
    }

    @Test void keyboardArrowsWrapSkipDisabledAndStayOutOfPageTextFields(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PixelTabs tabs = years();
            JTextField field = new JTextField();
            tabs.getComponentAt(0).add(field);
            tabs.setTabEnabledAt(1, false);
            key(tabs, "RIGHT");
            assertEquals(2, tabs.getSelectedIndex());
            key(tabs, "END");
            assertEquals(4, tabs.getSelectedIndex());
            key(tabs, "RIGHT");
            assertEquals(0, tabs.getSelectedIndex());
            key(tabs, "LEFT");
            assertEquals(4, tabs.getSelectedIndex());
            key(tabs, "HOME");
            assertEquals(0, tabs.getSelectedIndex());
            assertNull(tabs.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                    .get(KeyStroke.getKeyStroke("RIGHT")));
            assertNotNull(field.getInputMap().get(KeyStroke.getKeyStroke("RIGHT")));
            assertTrue(button(tabs, 0).isFocusable());
            assertFalse(button(tabs, 2).isFocusable());
            MouseWheelEvent wheel = new MouseWheelEvent(tabs.getTabBar(),
                    MouseWheelEvent.MOUSE_WHEEL, 1, 0, 20, 20, 0, false,
                    MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, 1);
            tabs.getTabBar().dispatchEvent(wheel);
            assertEquals(2, tabs.getSelectedIndex());
            assertTrue(wheel.isConsumed());
        });
    }

    @Test void nativeSpaceActivationAndTabAccessibilityArePreserved() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PixelTabs tabs = years();
            JToggleButton next = button(tabs, 1);
            assertEquals(AccessibleRole.PAGE_TAB_LIST,
                    tabs.getTabBar().getAccessibleContext().getAccessibleRole());
            assertEquals(AccessibleRole.PAGE_TAB,
                    next.getAccessibleContext().getAccessibleRole());
            next.getActionMap().get("pressed").actionPerformed(
                    new ActionEvent(next, 0, "pressed"));
            next.getActionMap().get("released").actionPerformed(
                    new ActionEvent(next, 0, "released"));
            assertEquals(1, tabs.getSelectedIndex());
            assertTrue(next.getAccessibleContext().getAccessibleStateSet(
                    ).contains(AccessibleState.CHECKED));
            assertTrue(next.getAccessibleContext().getAccessibleStateSet(
                    ).contains(AccessibleState.SELECTED));
            tabs.setTitleAt(1, "NUEVA");
            assertEquals("NUEVA", next.getAccessibleContext().getAccessibleName());
        });
    }

    @Test void disabledTabsAndGlobalDisableMaintainIndependentState() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PixelTabs tabs = years();
            tabs.setTabEnabledAt(0, false);
            assertEquals(1, tabs.getSelectedIndex());
            assertThrows(IllegalArgumentException.class, () -> tabs.setSelectedIndex(0));
            tabs.setEnabled(false);
            button(tabs, 2).doClick(0);
            key(tabs, "RIGHT");
            assertEquals(1, tabs.getSelectedIndex());
            assertTrue(tabs.isTabEnabledAt(2));
            assertFalse(button(tabs, 2).isEnabled());
            tabs.setEnabled(true);
            assertTrue(button(tabs, 2).isEnabled());
            assertFalse(button(tabs, 0).isEnabled());
            for (int i = 0; i < tabs.getTabCount(); i++) tabs.setTabEnabledAt(i, false);
            assertEquals(-1, tabs.getSelectedIndex());
            assertNull(tabs.indicatorBounds());
            for (int i = 0; i < tabs.getTabCount(); i++) {
                assertFalse(button(tabs, i).isSelected());
                assertFalse(tabs.getComponentAt(i).isVisible());
            }
            tabs.setTabEnabledAt(3, true);
            assertEquals(3, tabs.getSelectedIndex());
            assertTrue(tabs.getComponentAt(3).isVisible());
        });
    }

    @Test void removalPreservesCardsOrChoosesNearestEnabledAndCanBecomeEmpty(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PixelTabs tabs = years();
            tabs.setSelectedIndex(2);
            JComponent selected = tabs.getComponentAt(2);
            AtomicInteger changes = new AtomicInteger();
            tabs.addChangeListener(e -> changes.incrementAndGet());
            tabs.removeTabAt(0);
            assertEquals(1, tabs.getSelectedIndex());
            assertSame(selected, tabs.getComponentAt(1));
            assertTrue(selected.isVisible());
            tabs.setTabEnabledAt(2, false);
            tabs.removeTabAt(1);
            assertEquals(2, tabs.getSelectedIndex());
            assertEquals("MORE", tabs.getTitleAt(2));
            while (tabs.getTabCount() > 1) tabs.removeTabAt(0);
            int before = changes.get();
            tabs.removeTabAt(0);
            assertEquals(before + 1, changes.get());
            assertEquals(-1, tabs.getSelectedIndex());
            assertNull(tabs.indicatorBounds());
            tabs.addTab("NUEVA");
            assertEquals(0, tabs.getSelectedIndex());
        });
    }

    @Test void configurationValidationAndTitleChangesRelayoutTheSelectedUnderline(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PixelTabs tabs = years();
            layout(tabs, 608, 64);
            double width = tabs.indicatorBounds().width();
            tabs.setTitleAt(0, "MUCHO MAS LARGO");
            layout(tabs, 608, 64);
            assertTrue(tabs.indicatorBounds().width() > width);
            tabs.setTabFont(LauncherFonts.minecraftTen(20));
            tabs.setTabHeight(72);
            tabs.setTabGap(12);
            tabs.setTabHorizontalPadding(10);
            layout(tabs, 608, 72);
            assertEquals(72, tabs.getTabBar().getHeight());
            assertEquals(12, tabs.getTabGap());
            assertEquals(10, tabs.getTabHorizontalPadding());
            assertThrows(IllegalArgumentException.class, () -> tabs.addTab(" "));
            assertThrows(IllegalArgumentException.class,
                    () -> tabs.addTab("DUPLICADO", tabs.getComponentAt(0)));
            assertThrows(NullPointerException.class, () -> tabs.addTab("NULL", null));
            assertThrows(IllegalArgumentException.class, () -> tabs.setTabHeight(39));
            assertThrows(IllegalArgumentException.class, () -> tabs.setTabGap(-1));
            assertThrows(IllegalArgumentException.class,
                    () -> tabs.setTabHorizontalPadding(-1));
            assertThrows(IllegalArgumentException.class,
                    () -> tabs.setMaximumStripWidth(99));
        });
    }

    @Test void stripIsCenteredCappedAndKeepsSelectedTabsVisibleOnNarrowViews(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PixelTabs tabs = years();
            layout(tabs, 2400, 64);
            assertEquals(260, tabs.indicatorBounds().left());
            tabs.setMaximumStripWidth(608);
            layout(tabs, 1000, 64);
            assertEquals(216, tabs.indicatorBounds().left());
            BufferedImage wide = render(tabs);
            assertEquals(0x171615, rgb(wide, 0, 49));
            assertEquals(0, rgb(wide, 196, 49));
            layout(tabs, 220, 64);
            tabs.setSelectedIndex(4);
            layout(tabs, 220, 64);
            Rectangle last = button(tabs, 4).getBounds();
            assertTrue(last.x >= 20);
            assertTrue(last.x + last.width <= 200);
            tabs.setSelectedIndex(0);
            layout(tabs, 220, 64);
            assertEquals(20, button(tabs, 0).getX());
            tabs.setTitleAt(0, "TITULO QUE NO CABE EN ESTE ESPACIO");
            layout(tabs, 220, 64);
            assertEquals(20, button(tabs, 0).getX());
            assertFalse(button(tabs, 0).contains(221, 10));
        });
    }

    @Test void transitionUsesCssEaseAndIndependentPositionAndWidthDurations() {
        var start = new TabIndicatorMotion.Bounds(20, 100);
        var end = new TabIndicatorMotion.Bounds(420, 200);
        assertEquals(start, TabIndicatorMotion.sample(start, end, 0));
        assertEquals(end, TabIndicatorMotion.sample(start, end, 500));
        var half = TabIndicatorMotion.sample(start, end, 250);
        assertEquals(200, half.width());
        assertTrue(half.left() > 220 && half.left() < 420);
        assertEquals(.8024, TabIndicatorMotion.ease(.5), .0001);
        assertEquals(0, TabIndicatorMotion.ease(-1));
        assertEquals(1, TabIndicatorMotion.ease(2));
        var reverse = TabIndicatorMotion.sample(end, start, 250);
        assertEquals(100, reverse.width());
        assertTrue(reverse.left() > 20 && reverse.left() < 220);
    }

    @Test void actualIndicatorAnimatesRetargetsWithoutJumpAndDoesNotRestartOnLayout(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicLong clock = new AtomicLong(1_000_000_000L);
            PixelTabs tabs = new PixelTabs(clock::get);
            tabs.addTab("A");
            tabs.addTab("MUCHO MAS LARGO");
            tabs.addTab("FIN");
            JPanel host = new JPanel(new BorderLayout());
            host.add(tabs);
            host.addNotify();
            try {
                layout(host, 800, 200);
                var initial = tabs.indicatorBounds();
                tabs.setSelectedIndex(1);
                assertTrue(tabs.isIndicatorAnimating());
                clock.addAndGet(125_000_000L);
                tabs.pulseIndicator();
                var intermediate = tabs.indicatorBounds();
                assertTrue(intermediate.left() > initial.left());
                assertTrue(intermediate.width() > initial.width());
                layout(host, 800, 200);
                assertTrue(tabs.isIndicatorAnimating());
                assertEquals(intermediate, tabs.indicatorBounds());
                clock.addAndGet(125_000_000L);
                tabs.pulseIndicator();
                assertEquals(button(tabs, 1).getWidth(), tabs.indicatorBounds().width());
                assertTrue(tabs.indicatorBounds().left() < button(tabs, 1).getX());
                var beforeRetarget = tabs.indicatorBounds();
                tabs.setSelectedIndex(2);
                assertEquals(beforeRetarget, tabs.indicatorBounds());
                clock.addAndGet(500_000_000L);
                tabs.pulseIndicator();
                assertFalse(tabs.isIndicatorAnimating());
                assertEquals(button(tabs, 2).getX(), tabs.indicatorBounds().left());
            } finally { host.removeNotify(); }
        });
    }

    @Test void hiddenDisabledDetachedOrReducedMotionTabsDoNotKeepAnimationTimers(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicLong clock = new AtomicLong();
            PixelTabs tabs = new PixelTabs(clock::get);
            tabs.addTab("UNO");
            tabs.addTab("DOS");
            layout(tabs, 608, 100);
            tabs.setSelectedIndex(1);
            assertFalse(tabs.isIndicatorAnimating());
            JPanel host = new JPanel(new BorderLayout());
            host.add(tabs);
            host.addNotify();
            try {
                layout(host, 608, 100);
                tabs.setSelectedIndex(0);
                assertTrue(tabs.isIndicatorAnimating());
                tabs.setAnimationEnabled(false);
                assertFalse(tabs.isIndicatorAnimating());
                assertEquals(button(tabs, 0).getX(), tabs.indicatorBounds().left());
                tabs.setAnimationEnabled(true);
                tabs.setSelectedIndex(1);
                assertTrue(tabs.isIndicatorAnimating());
                tabs.setVisible(false);
                assertFalse(tabs.isIndicatorAnimating());
                tabs.setVisible(true);
                tabs.setSelectedIndex(0);
                assertTrue(tabs.isIndicatorAnimating());
                tabs.setEnabled(false);
                assertFalse(tabs.isIndicatorAnimating());
                tabs.setEnabled(true);
                tabs.setSelectedIndex(1);
                assertTrue(tabs.isIndicatorAnimating());
            } finally { host.removeNotify(); }
            assertFalse(tabs.isIndicatorAnimating());
        });
    }

    @Test void galleryProvidesBothExamplesAndRealControls() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PixelTabsGallery gallery = new PixelTabsGallery();
            layout(gallery, 1040, 650);
            PixelTabs years = (PixelTabs) named(gallery, "pixel-tabs-years"),
                    sections = (PixelTabs) named(gallery, "pixel-tabs-sections");
            ((JButton) named(gallery, "pixel-tabs-next")).doClick(0);
            assertEquals(1, years.getSelectedIndex());
            ((JComboBox<?>) named(gallery, "pixel-tabs-view")).setSelectedIndex(1);
            ((JButton) named(gallery, "pixel-tabs-next")).doClick(0);
            assertEquals(1, sections.getSelectedIndex());
            ((JCheckBox) named(gallery, "pixel-tabs-animate")).doClick(0);
            assertFalse(years.isAnimationEnabled());
            assertFalse(sections.isAnimationEnabled());
            assertTrue(button(sections, 2).getWidth() > button(sections, 1).getWidth());
        });
    }

    @Test void exportsActualSwingPreviewsWhenRequested() throws Exception {
        String directory = System.getenv("LEVERAGE_PIXEL_TABS_PREVIEW");
        if (directory == null || directory.isBlank()) return;
        Path output = Path.of(directory);
        Files.createDirectories(output);
        SwingUtilities.invokeAndWait(() -> {
            try {
                com.formdev.flatlaf.FlatDarkLaf.setup();
                PixelTabs tabs = years();
                layout(tabs, 608, 64);
                ImageIO.write(render(tabs), "png", output.resolve(
                        "leverage-pixel-tabs-reference.png").toFile());
                PixelTabsGallery gallery = new PixelTabsGallery();
                layout(gallery, 1040, 650);
                ImageIO.write(render(gallery), "png", output.resolve(
                        "leverage-pixel-tabs-gallery.png").toFile());
                layout(gallery, 820, 520);
                ImageIO.write(render(gallery), "png", output.resolve(
                        "leverage-pixel-tabs-compact.png").toFile());
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    private static PixelTabs years() {
        PixelTabs tabs = new PixelTabs();
        for (String title : new String[] { "2026", "2025", "2024",
                "2023", "MORE" }) tabs.addTab(title);
        return tabs;
    }
    private static JToggleButton button(PixelTabs tabs, int index) {
        return (JToggleButton) tabs.getTabBar().getComponent(index);
    }
    private static void key(PixelTabs tabs, String key) {
        tabs.getTabBar().getActionMap().get(key).actionPerformed(
                new ActionEvent(tabs, 0, key));
    }
    private static void layout(Component component, int width, int height) {
        component.setSize(width, height);
        layout(component);
    }
    private static void layout(Component component) {
        if (component instanceof Container container) {
            container.doLayout();
            for (Component child : container.getComponents()) layout(child);
        }
    }
    private static BufferedImage render(Component component) {
        BufferedImage image = new BufferedImage(component.getWidth(),
                component.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        component.printAll(g);
        g.dispose();
        return image;
    }
    private static int rgb(BufferedImage image, int x, int y) {
        return image.getRGB(x, y) & 0xffffff;
    }
    private static Component named(Component component, String name) {
        if (name.equals(component.getName())) return component;
        if (component instanceof Container c) for (var child : c.getComponents()) {
            Component found = named(child, name);
            if (found != null) return found;
        }
        return null;
    }
}
