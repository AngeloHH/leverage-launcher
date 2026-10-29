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
import java.util.HashSet;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class PurpleParticlesTest {
    @Test
    void positionsAndRegularTimingsAreStableBySeedAndIndex() {
        var halves = new HashSet<Integer>();
        for (int i = 0; i < 200; i++) {
            var a = PurpleParticleModel.particle(i, 42);
            assertEquals(a, PurpleParticleModel.particle(i, 42));
            assertEquals(i + 1, a.id());
            assertTrue(a.u() >= .05 && a.u() <= .95);
            assertTrue(a.pixels() >= 6 && a.pixels() <= 11);
            var regular = GlowParticleModel.particle(i, 42);
            assertEquals(regular.riseSeconds(), a.motion().riseSeconds());
            assertEquals(regular.delaySeconds(), a.motion().delaySeconds());
            assertEquals(regular.swingSeconds(), a.motion().swingSeconds());
            assertEquals(regular.pulseSeconds(), a.motion().pulseSeconds());
            assertEquals(1, a.motion().scale());
            assertEquals(2, a.motion().variant());
            assertTrue(a.shade() >= 0 && a.shade() <= 2);
            if (i < 20) halves.add(a.u() >= .5 ? 1 : 0);
        }
        assertEquals(2, halves.size());
        assertNotEquals(PurpleParticleModel.particle(0, 42),
                PurpleParticleModel.particle(0, 43));
    }

    @Test
    void riseUsesRegularDelayEasingShrinkFadeAndPanelRelativeCoordinates() {
        var motion = new GlowParticleModel.Particle(1, "left", .25, 1, 2, 100, 10, 4, 2);
        var particle = new PurpleParticleModel.Particle(1, .25, 8, 1, 0, motion);
        assertEquals(0, PurpleParticleModel.sample(particle, 9, 1000, 500, 40).opacity());
        var start = PurpleParticleModel.sample(particle, 10, 1000, 500, 40);
        assertEquals(0, start.opacity());
        assertEquals(502.5, start.y());
        assertEquals(8, start.pixels());
        var half = PurpleParticleModel.sample(particle, 60, 1000, 500, 40);
        double raised = GlowParticleModel.easeOut(.5), scale = 1 - .25 * raised;
        assertEquals(1, half.opacity(), 1e-10);
        assertEquals(8 * scale, half.pixels(), 1e-10);
        assertEquals(252.5 + 40 * scale, half.x(), 1e-7);
        assertEquals(-107.5 + 610 * (1 - raised), half.y(), 1e-7);
        var resized = PurpleParticleModel.sample(particle, 60, 1200, 600, 40);
        assertEquals(50, resized.x() - half.x(), 1e-7);
        assertEquals(100 * (1 - raised), resized.y() - half.y(), 1e-7);
        var noSwing = PurpleParticleModel.sample(particle, 60, 1000, 500, 0);
        assertEquals(252.5, noSwing.x());
        assertEquals(half.y(), noSwing.y());
        assertEquals(0, PurpleParticleModel.sample(particle,
                110, 1000, 500, 40).opacity());
        var literal = PurpleParticleModel.sample(particle, 60, 1000, 500, 40, 5202);
        assertEquals(half.x(), literal.x());
        assertEquals(half.opacity(), literal.opacity());
        assertTrue(literal.y() > half.y());
    }

    @Test
    void everyPurpleParticleReusesTheExactRegularMotionAtArbitraryInstants() {
        for (long seed : new long[] { 42, Long.MIN_VALUE }) for (int index
                = 0; index < 200; index++) {
            var purple = PurpleParticleModel.particle(index, seed);
            var regular = GlowParticleModel.particle(index, seed);
            for (double time : new double[] { 0, 5, 8, 20, 40, 600 }) {
                var expected = GlowParticleModel.sample(regular, time, 1000, 500, 610);
                var actual = PurpleParticleModel.sample(purple, time, 1000, 500, 40);
                double sourceOpacity = GlowParticleModel.DATA.variants(
                        ).get(regular.variant() - 1).opacity();
                assertEquals(expected.opacity() / sourceOpacity * purple.brightness(),
                        actual.opacity(), 1e-8);
                assertEquals(expected.y(), actual.y());
                if (time >= regular.delaySeconds()) {
                    double expectedAnchor = regular.anchor().equals("left")
                            ? 1000 * regular.fraction() + 2.5 : 1000 * (1
                            - regular.fraction()) - 2.5;
                    double actualAnchor = 1000 * purple.u() + 2.5;
                    assertEquals(expected.x() - expectedAnchor,
                            actual.x() - actualAnchor, 1e-8);
                    assertEquals(expected.scale() / regular.scale() * purple.pixels(),
                            actual.pixels(), 1e-8);
                }
            }
        }
    }

    @Test
    void animationChangePreservesThePreviouslyApprovedPerParticleAppearanceStream() {
        for (int index = 0; index < 200; index++) {
            var random = new java.util.SplittableRandom(42
                    + 0x9e3779b97f4a7c15L * (index + 1L));
            double oldDuration = random.nextDouble(4.5, 8);
            double oldPixels = random.nextDouble(6, 11);
            random.nextDouble(oldDuration);
            random.nextDouble(Math.PI * 2);
            double oldBrightness = random.nextDouble(.65, 1);
            int oldShade = random.nextInt(3);
            var particle = PurpleParticleModel.particle(index, 42);
            assertEquals(oldPixels, particle.pixels());
            assertEquals(oldBrightness, particle.brightness());
            assertEquals(oldShade, particle.shade());
        }
    }

    @Test
    void numericStyleKeepsReferenceProvenanceAndSmoothSymmetricSquareProfile(
            ) throws Exception {
        var profile = PurpleParticleSprites.PROFILE;
        assertEquals(3, profile.colors().size());
        assertEquals(9, profile.edgeProfile().size());
        assertEquals(6, profile.coreDiameter());
        assertEquals("#EC8EDA", profile.colors().getFirst());
        for (int i = 0; i < 9; i++) assertEquals(profile.edgeProfile().get(i),
                profile.edgeProfile().get(8 - i));
        assertTrue(profile.edgeProfile().get(0) < profile.edgeProfile().get(1));
        assertTrue(profile.edgeProfile().get(1) < profile.edgeProfile().get(2));
        assertThrows(UnsupportedOperationException.class,
                () -> profile.edgeProfile().clear());
        try (var input = PurpleParticles.class.getResourceAsStream(
                "/dev/leverage/ui/purple-particles.json")) {
            var json = JsonParser.parseString(new String(input.readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance(
                    "SHA-256").digest(Files.readAllBytes(Path.of(
                    "docs/references/particles/purple-button-reference.png"))));
            assertEquals(hash, json.get("referenceSha256").getAsString());
        }
    }

    @Test
    void softSquareHasGradualAlphaInsteadOfHardRingsAndNoBroadOrangeBloom() {
        var body = single(PurpleParticles.Appearance.DEFAULT.withGlowReach(0));
        int center = body.getRGB(50, 50) >>> 24;
        assertTrue(center > 0 && center < 255);
        assertEquals(center, body.getRGB(52, 52) >>> 24);
        var levels = new HashSet<Integer>();
        for (int x = 40; x <= 50; x++) levels.add(body.getRGB(x, 50) >>> 24);
        assertTrue(levels.size() >= 5);
        assertTrue((body.getRGB(54, 50) >>> 24) < center);
        assertTrue((body.getRGB(56, 50) >>> 24) <(body.getRGB(54, 50) >>> 24));
        var halo = single(PurpleParticles.Appearance.DEFAULT.withGlowReach(2));
        assertTrue((halo.getRGB(65, 50) >>> 24) >(body.getRGB(65, 50) >>> 24));
        assertEquals(0, halo.getRGB(80, 50) >>> 24);
        var far = single(PurpleParticles.Appearance.DEFAULT.withDistance(
                1).withGlowReach(0));
        assertTrue((far.getRGB(50, 50) >>> 24) < center);
        var hidden = single(PurpleParticles.Appearance.DEFAULT.withIntensity(0));
        assertTrue(Arrays.stream(pixels(hidden)).allMatch(pixel -> pixel == 0));
    }

    @Test
    void effectIsAChildlessTransparentClickThroughOverlayNotAButtonWrapper(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var effect = new PurpleParticles();
            effect.setSize(500, 300);
            assertEquals(0, effect.getComponentCount());
            assertFalse(effect.isOpaque());
            assertFalse(effect.isFocusable());
            assertFalse(effect.contains(50, 50));
            assertEquals(0, effect.getAccessibleContext().getAccessibleChildrenCount());
            JLayeredPane layers = new JLayeredPane();
            layers.setSize(500, 300);
            JButton underneath = new JButton("Underlying control");
            underneath.setBounds(20, 20, 200, 60);
            layers.add(underneath, JLayeredPane.DEFAULT_LAYER);
            layers.add(effect, JLayeredPane.PALETTE_LAYER);
            assertSame(underneath, layers.findComponentAt(50, 50));
            effect.setParticleCount(0);
            assertTrue(Arrays.stream(pixels(render(effect, 500,
                    300))).allMatch(p -> p == 0));
            assertThrows(ClassNotFoundException.class,
                    () -> Class.forName("dev.leverage.ui.PurpleParticleButton"));
        });
    }

    @Test
    void lifecyclePausesOnHideDisableEmptyAndRemovalAndSpeedDoesNotSeek(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var effect = new PurpleParticles();
            JPanel host = new JPanel();
            host.add(effect);
            try {
                assertFalse(effect.isTimerRunning());
                effect.setTimeSeconds(20);
                assertEquals(20, effect.getTimeSeconds());
                host.addNotify();
                assertTrue(effect.isTimerRunning());
                effect.setSpeed(2);
                assertTrue(effect.getTimeSeconds() >= 20 && effect.getTimeSeconds() < 21);
                effect.setAnimationEnabled(false);
                double paused = effect.getTimeSeconds();
                effect.setSpeed(.5);
                assertEquals(paused, effect.getTimeSeconds());
                effect.setAnimationEnabled(true);
                effect.setEnabled(false);
                assertFalse(effect.isTimerRunning());
                effect.setEnabled(true);
                assertTrue(effect.isTimerRunning());
                effect.setVisible(false);
                assertFalse(effect.isTimerRunning());
                effect.setVisible(true);
                assertTrue(effect.isTimerRunning());
                effect.setParticleCount(0);
                assertFalse(effect.isTimerRunning());
                effect.setParticleCount(20);
                assertTrue(effect.isTimerRunning());
                host.removeNotify();
                assertFalse(effect.isTimerRunning());
            } finally { if (host.isDisplayable()) host.removeNotify(); }
        });
    }

    @Test
    void appearanceChangesNeverReservePaddingResizeThePanelOrAddAControl(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var effect = new PurpleParticles();
            effect.setAnimationEnabled(false);
            effect.setSize(640, 480);
            var preferred = effect.getPreferredSize();
            effect.setAppearance(new PurpleParticles.Appearance(4, .8, 1, 3));
            effect.setParticleAppearance(0, new PurpleParticles.Appearance(4, .5, 1, 3));
            effect.setSpread(96);
            assertEquals(new java.awt.Dimension(640, 480), effect.getSize());
            assertEquals(preferred, effect.getPreferredSize());
            assertEquals(0, effect.getComponentCount());
            assertFalse(effect.contains(320, 240));
            assertDoesNotThrow(() -> render(effect, 640, 480));
        });
    }

    @Test
    void globalAndIndividualSettingsRetainUnaffectedParticlesAndResetWithoutSeeking(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var effect = new PurpleParticles();
            effect.setAnimationEnabled(false);
            effect.setTimeSeconds(1.5);
            int[] original = pixels(render(effect, 1000, 500));
            effect.setParticleCount(1);
            int[] first = pixels(render(effect, 1000, 500));
            effect.setParticleCount(2);
            effect.setParticleAppearance(1,
                    PurpleParticles.Appearance.DEFAULT.withIntensity(0));
            assertArrayEquals(first, pixels(render(effect, 1000, 500)));
            var retained = effect.particleAt(0);
            effect.setParticleCount(40);
            assertEquals(retained, effect.particleAt(0));
            effect.setSizeMultiplier(2);
            effect.setDistance(.5);
            effect.setGlowReach(2);
            effect.setIntensity(.5);
            assertEquals(PurpleParticles.Appearance.DEFAULT,
                    effect.getParticleAppearance(0));
            assertEquals(0, effect.getParticleAppearance(1).intensity());
            effect.setParticleCount(1);
            effect.setParticleCount(2);
            assertEquals(PurpleParticles.Appearance.DEFAULT,
                    effect.getParticleAppearance(1));
            effect.setSeed(142);
            assertNotEquals(retained, effect.particleAt(0));
            effect.setSpread(50);
            effect.setSpeed(2);
            effect.resetConfiguration();
            assertEquals(1.5, effect.getTimeSeconds());
            assertFalse(effect.isAnimationEnabled());
            assertEquals(20, effect.getParticleCount());
            assertEquals(42, effect.getSeed());
            assertEquals(40, effect.getSpread());
            assertEquals(1, effect.getSpeed());
            assertEquals(0, effect.getTravelDistance());
            assertArrayEquals(original, pixels(render(effect, 1000, 500)));
        });
    }

    @Test
    void cacheIsBoundedAndMaximumPopulationDoesNotAllocateLargeGlowTextures(
            ) throws Exception {
        for (int shade = 0; shade < 3; shade++) for (int step = 0; step <= 1000; step++) {
            var sprite = PurpleParticleSprites.sprite(shade, step / 1000d);
            assertTrue(sprite.image().getWidth() <= 40);
        }
        assertEquals(63, PurpleParticleSprites.cacheSize());
        assertSame(PurpleParticleSprites.sprite(0, .5),
                PurpleParticleSprites.sprite(0, .501));
        SwingUtilities.invokeAndWait(() -> {
            var effect = new PurpleParticles();
            effect.setAnimationEnabled(false);
            effect.setParticleCount(200);
            effect.setAppearance(new PurpleParticles.Appearance(4, .1, 1, 3));
            for (int i = 0; i < 200; i++) effect.setParticleAppearance(i,
                    new PurpleParticles.Appearance(4, .2, 1, 3));
            assertTrue(Arrays.stream(pixels(render(effect, 400,
                    240))).anyMatch(pixel -> (pixel >>> 24) > 0));
            assertEquals(63, PurpleParticleSprites.cacheSize());
        });
    }

    @Test
    void seekResizeAndVisibilityDoNotRegenerateTheFieldAndInvalidValuesAreRejected(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var effect = new PurpleParticles();
            effect.setAnimationEnabled(false);
            effect.setTimeSeconds(1);
            int[] original = pixels(render(effect, 1000, 500));
            effect.setTimeSeconds(2);
            assertFalse(Arrays.equals(original, pixels(render(effect, 1000, 500))));
            effect.setTimeSeconds(1);
            render(effect, 800, 400);
            assertArrayEquals(original, pixels(render(effect, 1000, 500)));
            effect.setIntensity(0);
            assertTrue(Arrays.stream(pixels(render(effect, 1000,
                    500))).allMatch(pixel -> pixel == 0));
            assertThrows(IllegalArgumentException.class,
                    () -> effect.setParticleCount(-1));
            assertThrows(IllegalArgumentException.class,
                    () -> effect.setParticleCount(201));
            assertThrows(IllegalArgumentException.class,
                    () -> effect.setSizeMultiplier(Double.NaN));
            assertThrows(IllegalArgumentException.class, () -> effect.setIntensity(1.1));
            assertThrows(IllegalArgumentException.class, () -> effect.setDistance(-1));
            assertThrows(IllegalArgumentException.class, () -> effect.setGlowReach(4));
            assertThrows(IllegalArgumentException.class, () -> effect.setSpread(97));
            assertThrows(IllegalArgumentException.class, () -> effect.setSpeed(0));
            assertThrows(IllegalArgumentException.class,
                    () -> effect.setTimeSeconds(Double.POSITIVE_INFINITY));
            assertThrows(NullPointerException.class, () -> effect.setAppearance(null));
            assertThrows(IllegalArgumentException.class,
                    () -> effect.setTravelDistance(-1));
            assertThrows(IllegalArgumentException.class,
                    () -> effect.setTravelDistance(100_001));
            assertThrows(IndexOutOfBoundsException.class,
                    () -> effect.getParticleAppearance(20));
            assertDoesNotThrow(() -> render(effect, 1, 1));
        });
    }

    @Test
    void galleryOnlyDisplaysFreeParticlesAndControlsTheWholeFieldAndIndividualItems(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var gallery = new PurpleParticlesGallery();
            render(gallery, 1040, 650);
            var effect = (PurpleParticles) named(gallery, "purple-particles-overlay");
            ((JCheckBox) named(gallery, "purple-animate")).doClick(0);
            assertEquals(0, effect.getComponentCount());
            assertEquals(1, effect.getParent().getComponentCount());
            JSpinner count = (JSpinner) named(gallery, "purple-count"),
                    size = (JSpinner) named(gallery, "purple-size");
            JComboBox<?> target = (JComboBox<?>) named(gallery, "purple-target");
            count.setValue(40);
            size.setValue(150);
            assertEquals(40, effect.getParticleCount());
            assertEquals(41, target.getItemCount());
            assertEquals(1.5, effect.getSizeMultiplier());
            target.setSelectedIndex(2);
            ((JSpinner) named(gallery, "purple-distance")).setValue(50);
            assertEquals(.5, effect.getParticleAppearance(1).distance());
            assertEquals(PurpleParticles.Appearance.DEFAULT,
                    effect.getParticleAppearance(0));
            ((JSpinner) named(gallery, "purple-spread")).setValue(48);
            ((JSpinner) named(gallery, "purple-speed")).setValue(200);
            assertEquals(48, effect.getSpread());
            assertEquals(2, effect.getSpeed());
            JComboBox<?> view = (JComboBox<?>) named(gallery, "purple-view");
            view.setSelectedIndex(1);
            assertFalse(effect.getParent().isVisible());
            view.setSelectedIndex(0);
            JCheckBox literal = (JCheckBox) named(gallery, "purple-original-travel");
            literal.doClick(0);
            assertEquals(5202, effect.getTravelDistance());
            ((JButton) named(gallery, "purple-reset")).doClick(0);
            assertEquals(20, effect.getParticleCount());
            assertEquals(20, effect.getTimeSeconds());
            assertFalse(effect.isAnimationEnabled());
            assertEquals(0, effect.getTravelDistance());
            assertFalse(literal.isSelected());
            String destination = System.getenv("LEVERAGE_PURPLE_OVERLAY_PREVIEW");
            if (destination != null) try {
                Path output = Path.of(destination);
                Files.createDirectories(output);
                ImageIO.write(render(gallery, 1040, 650), "PNG",
                        output.resolve("leverage-purple-overlay-gallery.png").toFile());
                ImageIO.write(render(gallery, 820, 520), "PNG",
                        output.resolve("leverage-purple-overlay-small.png").toFile());
                ImageIO.write(render(new PurpleParticlesGallery.Detail(),
                        960, 440), "PNG", output.resolve(
                        "leverage-purple-overlay-detail.png").toFile());
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
        });
    }

    private static BufferedImage single(PurpleParticles.Appearance appearance) {
        BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            PurpleParticleSprites.paint(g, new PurpleParticleModel.State(1, 50, 50,
                    6, 1, 0), appearance, PurpleParticles.Appearance.DEFAULT);
        }
        finally {
            g.dispose();
        }
        return image;
    }
    private static BufferedImage render(JComponent component, int width, int height) {
        component.setSize(width, height);
        layout(component);
        BufferedImage image = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            component.paint(g);
        } finally {
            g.dispose();
        }
        return image;
    }
    private static void layout(Container root) {
        root.doLayout();
        for (var child : root.getComponents()) if (
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
