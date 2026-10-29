package dev.leverage.ui;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class GlowParticlesTest {
    @Test
    void allTwentyParticlesMatchTheSuppliedHtmlAndCssWithoutNewRandomValues(
            ) throws Exception {
        Path references = Path.of("docs/references/particles");
        String html = Files.readString(references.resolve("regular-particles.html.txt"));
        String css = Files.readString(references.resolve("regular-particles.css.txt"));
        var json = JsonParser.parseString(new String(
                GlowParticles.class.getResourceAsStream(
                "/dev/leverage/ui/glow-particles.json").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(hash(references.resolve("regular-particles.html.txt")),
                json.get("sourceHtmlSHA256").getAsString());
        assertEquals(hash(references.resolve("regular-particles.css.txt")),
                json.get("sourceCssSHA256").getAsString());
        var data = GlowParticleModel.DATA;
        assertEquals(20, data.particles().size());
        assertEquals(4, data.variants().size());
        assertEquals(5, data.coreSize());
        assertEquals(110, data.topOverflow());
        assertEquals(5202, data.referenceTravel());
        assertEquals(40, data.swingAmplitude());
        assertEquals(.75, data.endScale());
        assertEquals(.8, data.pulseMinimum());
        var elements = Pattern.compile((
                "(?s)particle-raise-(\\d+)\">\\s*<div class=\"particle-swing particle"
                + "-swing-\\1\">\\s*")
                + ("<div class=\"particle-size-(\\d+) particle-pulse particle-pu"
                + "lse-\\1\">\\s*")
                + "<div class=\"glow-particle glow-particle--(\\d+)\">").matcher(html);
        int count = 0;
        while (elements.find()) {
            var particle = data.particles().get(count++);
            assertEquals(Integer.parseInt(elements.group(1)), particle.id());
            assertEquals(1 + Integer.parseInt(elements.group(2)) * .4,
                    particle.scale(), 1e-12);
            assertEquals(Integer.parseInt(elements.group(3)), particle.variant());
            String rise = block(css, "particle-raise-" + particle.id());
            var anchor = Pattern.compile("(left|right): ([0-9.]+)%").matcher(rise);
            assertTrue(anchor.find());
            assertEquals(anchor.group(1), particle.anchor());
            assertEquals(Double.parseDouble(anchor.group(2)) / 100,
                    particle.fraction(), 1e-12);
            assertEquals(value(rise, "animation-duration"), particle.riseSeconds());
            assertEquals(value(rise, "animation-delay"), particle.delaySeconds());
            assertEquals(value(block(css, "particle-swing-" + particle.id()),
                    "animation-duration"), particle.swingSeconds());
            assertEquals(value(block(css, "particle-pulse-" + particle.id()),
                    "animation-duration"), particle.pulseSeconds());
        }
        assertEquals(20, count);
        assertEquals(6, data.particles().stream().map(
                GlowParticleModel.Particle::scale).distinct().count());
        assertThrows(UnsupportedOperationException.class, () -> data.particles().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> data.variants().getFirst().shadows().clear());
    }

    @Test
    void profilesKeepTheSourceCoresShadowsAndDiffuseFourthType() {
        var variants = GlowParticleModel.DATA.variants();
        assertEquals("#f3cd73", variants.get(0).core());
        assertEquals("#e98d44", variants.get(1).core());
        assertEquals("#efb460", variants.get(2).core());
        assertEquals(4, variants.get(0).shadows().size());
        assertEquals(1, variants.get(1).shadows().size());
        assertEquals(2, variants.get(2).shadows().size());
        assertEquals(2, variants.get(3).shadows().size());
        assertEquals(20, variants.get(0).shadows().get(2).x());
        assertEquals(-40, variants.get(0).shadows().get(2).y());
        assertEquals(160, variants.get(3).shadows().getFirst().blur());
        assertEquals(80, variants.get(3).shadows().getFirst().spread());
        for (int i = 0; i < 3; i++) assertTrue(variants.get(i).square());
        assertFalse(variants.get(3).square());
        assertEquals(.7, variants.get(3).opacity());
        assertEquals(3, variants.get(3).filterBlur());
    }

    @Test
    void cssBezierCurvesAreNotLinearAndHaveExactEndpointsAndSymmetry() {
        for (var curve : java.util.List.<java.util.function.DoubleUnaryOperator>of(
                GlowParticleModel::easeOut, GlowParticleModel::easeInOut)) {
            assertEquals(0, curve.applyAsDouble(0));
            assertEquals(1, curve.applyAsDouble(1));
            double previous = 0;
            for (int i = 1; i <= 100; i++) {
                double next = curve.applyAsDouble(i / 100d);
                assertTrue(next >= previous && next <= 1);
                previous = next;
            }
        }
        assertEquals(.684643, GlowParticleModel.easeOut(.5), 1e-6);
        assertEquals(.5, GlowParticleModel.easeInOut(.5), 1e-8);
        assertEquals(1 - GlowParticleModel.easeInOut(.25),
                GlowParticleModel.easeInOut(.75), 1e-8);
    }

    @Test
    void delayedAndNegativeStartsRiseScaleAndFadeMatchKeyframes() {
        var particle = new GlowParticleModel.Particle(1,
                "left", .1, 2.2, 2, 100, 10, 4, 2);
        assertEquals(0, sample(particle, 9).opacity());
        var start = sample(particle, 10);
        assertEquals(0, start.opacity());
        assertEquals(2.2, start.scale());
        assertEquals(502.5, start.y());
        assertEquals(1, sample(particle, 26).opacity(), 1e-8);
        assertEquals(.8, sample(particle, 27).opacity(), 1e-8);
        var half = sample(particle, 60);
        double raised = GlowParticleModel.easeOut(.5);
        assertEquals(2.2 * (1 - .25 * raised), half.scale(), 1e-8);
        assertEquals(-107.5 + 610 * (1 - raised), half.y(), 1e-8);
        assertTrue(sample(particle, 108).opacity() < sample(particle, 106).opacity());
        assertEquals(0, sample(particle, 110).opacity());
        assertEquals(start.y(), sample(particle, 110).y());
        var negative = GlowParticleModel.DATA.particles().get(1);
        assertTrue(sample(negative, 0).opacity() > 0);
        assertTrue(sample(negative, 0).y() < 502.5);
    }

    @Test
    void swingAlternatesPulseRepeatsAndInnerAnimationsIgnoreRiseDelay() {
        var particle = new GlowParticleModel.Particle(1,
                "right", .1, 1, 2, 100, -20, 4, 2);
        double anchor = 1000 * .9 - 2.5;
        for (int second : new int[] { 0, 2, 4, 6, 8 }) {
            var state = sample(particle, second);
            double swing = (state.x() - anchor) / state.scale();
            assertEquals(switch (second) {
                case 0, 8 -> -40;
                case 4 -> 40;
                default -> 0;
            }, swing, 1e-6);
            assertEquals(1, state.opacity(), 1e-8);
        }
        assertEquals(.8, sample(particle, 1).opacity(), 1e-8);
        assertEquals(.9, sample(particle, .5).opacity(), 1e-8);
        assertEquals(.9, sample(particle, 1.5).opacity(), 1e-8);
    }

    @Test
    void resizingChangesPercentAnchorsAndTravelButNeverInventsParticles() {
        for (var particle : GlowParticleModel.DATA.particles()) for (double time
                : new double[] { 0, 20, 40, 600 }) {
            var a = GlowParticleModel.sample(particle, time, 1000, 500, 610);
            var b = GlowParticleModel.sample(particle, time, 1200, 500, 610);
            assertTrue(Double.isFinite(a.x()) && Double.isFinite(a.y()));
            assertTrue(a.opacity() >= 0 && a.opacity() <= 1);
            if (time >= particle.delaySeconds()) {
                assertEquals(particle.scale(), a.scale(), particle.scale() * .25 + 1e-8);
                assertEquals(200 * (particle.anchor().equals("left") ? particle.fraction()
                        : 1 - particle.fraction()), b.x() - a.x(), 1e-8);
                var literal = GlowParticleModel.sample(particle, time, 1000, 500, 5202);
                assertEquals(a.x(), literal.x());
                assertEquals(a.scale(), literal.scale());
                assertTrue(literal.y() >= a.y());
            }
        }
    }

    @Test
    void cachedHalosAreTransparentAndCoresRemainSharpSquares() {
        for (int variant = 1; variant <= 4; variant++) {
            assertSame(GlowParticleSprites.sprite(variant),
                    GlowParticleSprites.sprite(variant));
            BufferedImage image = particle(variant);
            assertEquals(0, GlowParticleSprites.sprite(variant).image().getRGB(0,
                    0) >>> 24);
            if (variant <= 3) {
                int core = Color.decode(GlowParticleModel.DATA.variants(
                        ).get(variant - 1).core()).getRGB();
                for (int y = 95; y < 105; y++) for (int x = 95; x < 105;
                        x++) assertEquals(core, image.getRGB(x, y));
                assertNotEquals(core, image.getRGB(94, 100));
                assertNotEquals(core, image.getRGB(100, 94));
                assertTrue((image.getRGB(100, 110) >>> 24) >(image.getRGB(100,
                        145) >>> 24));
            } else {
                assertTrue((image.getRGB(100, 100) >>> 24) < 255);
                assertNotEquals(image.getRGB(100, 100), image.getRGB(95, 95));
            }
        }
    }

    @Test
    void overlayIsClickThroughAndHiddenTimersStayStopped() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JLayeredPane parent = new JLayeredPane();
            parent.setSize(1000, 500);
            JButton button = new JButton("Debajo");
            button.setBounds(100, 100, 150, 50);
            parent.add(button, JLayeredPane.DEFAULT_LAYER);
            GlowParticles overlay = new GlowParticles();
            overlay.setBounds(0, 0, 1000, 500);
            parent.add(overlay, JLayeredPane.PALETTE_LAYER);
            assertFalse(overlay.isOpaque());
            assertFalse(overlay.isFocusable());
            assertFalse(overlay.contains(150, 120));
            assertSame(button, parent.findComponentAt(150, 120));
            overlay.setTimeSeconds(20);
            overlay.setAnimationEnabled(true);
            render(overlay, 1000, 500);
            assertFalse(overlay.isTimerRunning());
            assertEquals(20, overlay.getTimeSeconds());
            assertNotNull(overlay.getAccessibleContext().getAccessibleDescription());
            overlay.removeNotify();
            assertFalse(overlay.isTimerRunning());
        });
    }

    @Test
    void lifecyclePausesResumesAndDoesNotAccumulateTimeWhileHiddenOrDisabled(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel host = new JPanel();
            GlowParticles overlay = new GlowParticles();
            host.add(overlay);
            try {
                host.addNotify();
                assertTrue(overlay.isShowing());
                assertTrue(overlay.isTimerRunning());
                overlay.setTimeSeconds(20);
                overlay.setAnimationEnabled(false);
                double paused = overlay.getTimeSeconds();
                assertFalse(overlay.isTimerRunning());
                assertEquals(paused, overlay.getTimeSeconds());
                overlay.setAnimationEnabled(true);
                assertTrue(overlay.isTimerRunning());
                overlay.setEnabled(false);
                assertFalse(overlay.isTimerRunning());
                overlay.setEnabled(true);
                assertTrue(overlay.isTimerRunning());
                overlay.setVisible(false);
                assertFalse(overlay.isTimerRunning());
                paused = overlay.getTimeSeconds();
                assertEquals(paused, overlay.getTimeSeconds());
                overlay.setVisible(true);
                assertTrue(overlay.isTimerRunning());
                overlay.setTimeSeconds(3);
                assertTrue(overlay.getTimeSeconds() >= 3 && overlay.getTimeSeconds() < 4);
                host.removeNotify();
                assertFalse(overlay.isTimerRunning());
            } finally { if (host.isDisplayable()) host.removeNotify(); }
        });
    }

    @Test
    void seekIsReproducibleAndGlobalIntensityCanFullyHideTheEffect() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            GlowParticles overlay = new GlowParticles();
            overlay.setAnimationEnabled(false);
            overlay.setTimeSeconds(20);
            int[] original = pixels(render(overlay, 1000, 500));
            assertTrue(Arrays.stream(original).anyMatch(pixel -> (pixel >>> 24) > 0));
            overlay.setTimeSeconds(25);
            assertFalse(Arrays.equals(original, pixels(render(overlay, 1000, 500))));
            overlay.setTimeSeconds(20);
            assertArrayEquals(original, pixels(render(overlay, 1000, 500)));
            render(overlay, 800, 400);
            assertArrayEquals(original, pixels(render(overlay, 1000, 500)));
            overlay.setIntensity(0);
            assertTrue(Arrays.stream(pixels(render(overlay, 1000,
                    500))).allMatch(pixel -> pixel == 0));
            overlay.setIntensity(1);
            overlay.setTravelDistance(5202);
            assertFalse(Arrays.equals(original, pixels(render(overlay, 1000, 500))));
            overlay.setTravelDistance(0);
            assertArrayEquals(original, pixels(render(overlay, 1000, 500)));
            assertDoesNotThrow(() -> render(overlay, 1, 1));
            assertThrows(IllegalArgumentException.class,
                    () -> overlay.setTimeSeconds(Double.NaN));
            assertThrows(IllegalArgumentException.class,
                    () -> overlay.setTimeSeconds(-1));
            assertThrows(IllegalArgumentException.class,
                    () -> overlay.setTimeSeconds(Double.POSITIVE_INFINITY));
            assertThrows(IllegalArgumentException.class,
                    () -> overlay.setIntensity(Float.NaN));
            assertThrows(IllegalArgumentException.class,
                    () -> overlay.setIntensity(1.1f));
            assertThrows(IllegalArgumentException.class,
                    () -> overlay.setTravelDistance(-1));
        });
    }

    @Test
    void galleryControlsRealComponentAndExportsNativeJavaPreviews() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var gallery = new GlowParticlesGallery();
            render(gallery, 1040, 600);
            GlowParticles overlay = (GlowParticles) named(gallery,
                    "regular-glow-particles");
            JCheckBox animate = (JCheckBox) named(gallery, "glow-animate");
            JComboBox<?> view = (JComboBox<?>) named(gallery, "glow-view");
            JSpinner time = (JSpinner) named(gallery, "glow-time");
            JCheckBox travel = (JCheckBox) named(gallery, "glow-original-travel");
            assertEquals(20, overlay.getParticleCount());
            assertEquals(20, overlay.getTimeSeconds());
            animate.doClick(0);
            assertFalse(overlay.isAnimationEnabled());
            time.setValue(40d);
            assertEquals(40, overlay.getTimeSeconds());
            travel.doClick(0);
            assertEquals(5202, overlay.getTravelDistance());
            travel.doClick(0);
            assertEquals(0, overlay.getTravelDistance());
            view.setSelectedIndex(1);
            assertFalse(overlay.isVisible() && overlay.getParent().isVisible());
            view.setSelectedIndex(0);
            assertTrue(overlay.getParent().isVisible());
            time.setValue(20d);
            String destination = System.getenv("LEVERAGE_GLOW_PREVIEW");
            if (destination != null) try {
                Path output = Path.of(destination);
                Files.createDirectories(output);
                ImageIO.write(render(new GlowParticlesGallery.Profiles(),
                        960, 600), "PNG", output.resolve(
                        "leverage-glow-particles-types.png").toFile());
                ImageIO.write(render(gallery, 1040, 600), "PNG",
                        output.resolve("leverage-glow-particles-gallery.png").toFile());
                BufferedImage sheet = new BufferedImage(1000, 660,
                        BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = sheet.createGraphics();
                try {
                    graphics.setColor(new Color(0x050a10));
                    graphics.fillRect(0, 0, 1000, 660);
                    int index = 0;
                    for (int second : new int[] { 0, 8, 20, 40 }) {
                        overlay.setTimeSeconds(second);
                        BufferedImage frame = render(overlay, 500, 300);
                        int x = (index % 2) * 500, y = (index / 2) * 330;
                        graphics.drawImage(frame, x, y, null);
                        graphics.setColor(Color.WHITE);
                        graphics.setFont(LauncherFonts.minecraftSeven(14));
                        graphics.drawString(second + " s", x + 12, y + 320);
                        index++;
                    }
                } finally { graphics.dispose(); }
                ImageIO.write(sheet, "PNG", output.resolve(
                        "leverage-glow-particles-times.png").toFile());
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
        });
    }

    @Test
    void componentGalleryIncludesAndSelectsTheNewTabWithoutRemovingOldComponents(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                var method = ComponentGallery.class.getDeclaredMethod("createContent");
                method.setAccessible(true);
                JPanel content = (JPanel) method.invoke(null);
                JTabbedPane tabs = (JTabbedPane) content.getComponent(0);
                assertEquals(8, tabs.getTabCount());
                assertEquals("Partículas regulares", tabs.getTitleAt(2));
                assertEquals("Partículas moradas", tabs.getTitleAt(3));
                assertEquals("Pestañas", tabs.getTitleAt(4));
                assertEquals("Carrusel", tabs.getTitleAt(5));
                assertEquals("Dropdown", tabs.getTitleAt(6));
                assertEquals("Inputs", tabs.getTitleAt(7));
                assertEquals(7, tabs.getSelectedIndex());
                assertInstanceOf(PixelatedPanelGallery.class, tabs.getComponentAt(1));
                assertInstanceOf(GlowParticlesGallery.class, tabs.getComponentAt(2));
                assertInstanceOf(PurpleParticlesGallery.class, tabs.getComponentAt(3));
                assertInstanceOf(PixelTabsGallery.class, tabs.getComponentAt(4));
                assertInstanceOf(PixelCarouselGallery.class, tabs.getComponentAt(5));
                assertInstanceOf(PixelDropdownGallery.class, tabs.getComponentAt(6));
                assertInstanceOf(PixelInputsGallery.class, tabs.getComponentAt(7));
                render(content, 820, 520);
            } catch (ReflectiveOperationException exception) {
                throw new RuntimeException(exception);
            }
        });
    }

    private static String block(String css, String name) {
        var match = Pattern.compile("(?s)\\." + Pattern.quote(name)
                + " \\{(.*?)\\}").matcher(css);
        assertTrue(match.find(), name);
        return match.group(1);
    }
    private static double value(String body, String property) {
        var match = Pattern.compile("(?<![-a-z])" + property
                + ": (-?[0-9.]+)s").matcher(body);
        assertTrue(match.find(), body);
        return Double.parseDouble(match.group(1));
    }
    private static String hash(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance(
                "SHA-256").digest(Files.readAllBytes(path)));
    }
    private static GlowParticleModel.State sample(
            GlowParticleModel.Particle particle, double time) {
        return GlowParticleModel.sample(particle, time, 1000, 500, 610);
    }
    private static BufferedImage particle(int variant) {
        var image = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            GlowParticleSprites.paint(graphics, new GlowParticleModel.State(1,
                    variant, 100, 100, 2, 1), 1);
        }
        finally { graphics.dispose(); }
        return image;
    }
    private static BufferedImage render(JComponent component, int width, int height) {
        component.setSize(width, height);
        layout(component);
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            component.paint(graphics);
        } finally {
            graphics.dispose();
        }
        return image;
    }
    private static void layout(Container container) {
        container.doLayout();
        for (var child : container.getComponents()) if (
                child instanceof Container nested) layout(nested);
    }
    private static int[] pixels(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(),
                null, 0, image.getWidth());
    }
    private static Component named(Container root, String name) {
        for (var child : root.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container nested) {
                Component result = named(nested, name);
                if (result != null) return result;
            }
        }
        return null;
    }
}
