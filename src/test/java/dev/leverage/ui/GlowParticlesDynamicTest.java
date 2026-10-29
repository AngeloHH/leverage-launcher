package dev.leverage.ui;

import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
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
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class GlowParticlesDynamicTest {
    @Test
    void initialConfigurationRetainsEveryPixelOfTheApprovedEffect() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var effect = new GlowParticles();
            effect.setAnimationEnabled(false);
            assertEquals(20, effect.getParticleCount());
            assertEquals(GlowParticles.Appearance.DEFAULT, effect.getAppearance());
            for (double time : new double[] { 0, 8, 20, 40 }) {
                effect.setTimeSeconds(time);
                BufferedImage original = new BufferedImage(1000,
                        500, BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = original.createGraphics();
                try {
                    for (var particle : GlowParticleModel.DATA.particles(
                            )) GlowParticleSprites.paint(graphics,
                            GlowParticleModel.sample(particle, time, 1000, 500, 610), 1);
                } finally { graphics.dispose(); }
                assertArrayEquals(pixels(original), pixels(render(effect, 1000, 500)));
            }
        });
    }

    @Test
    void countChangesKeepMotionSettingsAndClockOfRetainedParticles() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var effect = new GlowParticles();
            effect.setAnimationEnabled(false);
            effect.setTimeSeconds(20);
            var custom = new GlowParticles.Appearance(2, .7, .4, 2);
            effect.setParticleAppearance(2, custom);
            effect.setParticleCount(80);
            assertEquals(80, effect.getParticleCount());
            assertEquals(20, effect.getTimeSeconds());
            assertEquals(custom, effect.getParticleAppearance(2));
            for (int i = 0; i < 20; i++) assertEquals(GlowParticleModel.DATA.particles(
                    ).get(i), effect.particleAt(i));
            for (int i = 20; i < 80; i++) {
                var particle = effect.particleAt(i);
                assertEquals(i + 1, particle.id());
                assertTrue(particle.variant() >= 1 && particle.variant() <= 4);
                assertTrue(particle.fraction() >= .02 && particle.fraction() < .23);
                assertTrue(particle.delaySeconds() <= 0);
                assertTrue(particle.riseSeconds() >= 12 && particle.riseSeconds() < 72);
                assertEquals(GlowParticles.Appearance.DEFAULT,
                        effect.getParticleAppearance(i));
            }
            var retained = effect.particleAt(40);
            effect.setParticleCount(41);
            effect.setParticleCount(80);
            assertEquals(retained, effect.particleAt(40));
            assertEquals(custom, effect.getParticleAppearance(2));
            effect.setParticleCount(2);
            effect.setParticleCount(3);
            assertEquals(GlowParticles.Appearance.DEFAULT,
                    effect.getParticleAppearance(2));
            effect.setParticleCount(GlowParticles.MAX_PARTICLES);
            assertEquals(200, effect.getParticleCount());
            effect.setParticleCount(0);
            assertTrue(Arrays.stream(pixels(render(effect, 500,
                    300))).allMatch(p -> p == 0));
        });
    }

    @Test
    void generatedParticlesAreStableBySeedAndIndexAndDefaultsNeverChangeWithSeed(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var first = new GlowParticles();
            var second = new GlowParticles();
            first.setSeed(42);
            second.setSeed(42);
            first.setParticleCount(30);
            second.setParticleCount(60);
            for (int i = 0; i < 30; i++) assertEquals(first.particleAt(i),
                    second.particleAt(i));
            var old = first.particleAt(20);
            first.setSeed(Long.MIN_VALUE);
            assertNotEquals(old, first.particleAt(20));
            for (int i = 0; i < 20; i++) assertEquals(GlowParticleModel.DATA.particles(
                    ).get(i), first.particleAt(i));
            first.setSeed(42);
            assertEquals(old, first.particleAt(20));
            assertEquals(42, first.getSeed());
        });
    }

    @Test
    void globalAndIndividualAppearanceCombineWithoutMutatingOtherParticles(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var effect = new GlowParticles();
            effect.setAnimationEnabled(false);
            effect.setParticleCount(2);
            effect.setTimeSeconds(20);
            effect.setAppearance(new GlowParticles.Appearance(1.25, .8, 0, 1));
            effect.setParticleAppearance(1, new GlowParticles.Appearance(1.5, .6, 0, 1));
            BufferedImage expected = new BufferedImage(1000, 500,
                    BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = expected.createGraphics();
            try {
                for (int i = 0; i < 2; i++) {
                    var state = GlowParticleModel.sample(effect.particleAt(i),
                            20, 1000, 500, 610);
                    GlowParticleSprites.paint(graphics, new GlowParticleModel.State(
                            state.id(), state.variant(), state.x(), state.y(),
                            state.scale() * 1.25 * (i == 1 ? 1.5 : 1),
                            state.opacity() * .8 * (i == 1 ? .6 : 1)), 1);
                }
            } finally { graphics.dispose(); }
            assertArrayEquals(pixels(expected), pixels(render(effect, 1000, 500)));
            assertEquals(GlowParticles.Appearance.DEFAULT,
                    effect.getParticleAppearance(0));
            effect.setDistance(.25);
            effect.setGlowReach(2);
            effect.setSizeMultiplier(2);
            effect.setIntensity(.5f);
            assertEquals(.25, effect.getDistance());
            assertEquals(2, effect.getGlowReach());
            assertEquals(2, effect.getSizeMultiplier());
            assertEquals(.5f, effect.getIntensity());
            assertEquals(1.5, effect.getParticleAppearance(1).size());
        });
    }

    @Test
    void zeroIndividualIntensityHidesOnlyThatParticleAndZeroGlobalHidesAll(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var effect = new GlowParticles();
            effect.setAnimationEnabled(false);
            effect.setTimeSeconds(20);
            effect.setParticleCount(1);
            int[] first = pixels(render(effect, 1000, 500));
            effect.setParticleCount(2);
            effect.setParticleAppearance(1,
                    GlowParticles.Appearance.DEFAULT.withIntensity(0));
            assertArrayEquals(first, pixels(render(effect, 1000, 500)));
            effect.setIntensity(0);
            assertTrue(Arrays.stream(pixels(render(effect, 1000,
                    500))).allMatch(p -> p == 0));
        });
    }

    @Test
    void distanceDefocusesCoreAndHaloReachDoesNotEnlargeTheSharpCenter() {
        var normal = single(new GlowParticles.Appearance(1, 1, 0, 1));
        var wide = single(new GlowParticles.Appearance(1, 1, 0, 2));
        var coreOnly = single(new GlowParticles.Appearance(1, 1, 0, 0));
        var distant = single(new GlowParticles.Appearance(1, 1, 1, 0));
        int core = Color.decode("#e98d44").getRGB();
        for (int y = 195; y < 205; y++) for (int x = 195; x < 205; x++) {
            assertEquals(core, normal.getRGB(x, y));
            assertEquals(core, wide.getRGB(x, y));
            assertEquals(core, coreOnly.getRGB(x, y));
        }
        assertEquals(100, Arrays.stream(pixels(coreOnly)).filter(
                pixel -> (pixel >>> 24) > 0).count());
        assertTrue((wide.getRGB(240, 200) >>> 24) >(normal.getRGB(240, 200) >>> 24));
        assertTrue((distant.getRGB(200, 200) >>> 24) <(coreOnly.getRGB(200, 200) >>> 24));
        assertTrue((distant.getRGB(210, 200) >>> 24) > 0);
        assertNotEquals(core, distant.getRGB(200, 200));
    }

    @Test
    void extraBlurCacheIsBoundedAndIndependentOfQuantitySizeAndHaloReach() {
        for (int variant = 1; variant <= 4; variant++) for (int i = 0; i <= 1000; i++) {
            var core = GlowParticleSprites.core(variant, i / 1000d);
            assertTrue(core.image().getWidth() < 80 && core.image().getHeight() < 80);
        }
        assertEquals(84, GlowParticleSprites.coreCacheSize());
        assertSame(GlowParticleSprites.core(2, .5), GlowParticleSprites.core(2, .501));
    }

    @Test
    void emptyPopulationStopsTimerAndRestoringRestartsOnlyWhenVisible() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel host = new JPanel();
            GlowParticles effect = new GlowParticles();
            host.add(effect);
            try {
                host.addNotify();
                assertTrue(effect.isTimerRunning());
                effect.setTimeSeconds(20);
                effect.setParticleCount(35);
                effect.setSizeMultiplier(2);
                effect.setDistance(.5);
                effect.setParticleAppearance(0,
                        GlowParticles.Appearance.DEFAULT.withGlowReach(2));
                assertTrue(effect.isTimerRunning());
                assertTrue(effect.getTimeSeconds() >= 20 && effect.getTimeSeconds() < 21);
                effect.setParticleCount(0);
                assertFalse(effect.isTimerRunning());
                double time = effect.getTimeSeconds();
                assertEquals(time, effect.getTimeSeconds());
                effect.setParticleCount(30);
                assertTrue(effect.isTimerRunning());
                effect.setVisible(false);
                effect.setParticleCount(0);
                effect.resetConfiguration();
                assertFalse(effect.isTimerRunning());
                assertEquals(20, effect.getParticleCount());
            } finally { host.removeNotify(); }
        });
    }

    @Test
    void maximumPopulationAndCombinedAppearanceStayFiniteAndRenderWithoutNewLargeTextures(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var effect = new GlowParticles();
            effect.setAnimationEnabled(false);
            effect.setTimeSeconds(20);
            effect.setParticleCount(GlowParticles.MAX_PARTICLES);
            effect.setAppearance(new GlowParticles.Appearance(4, .1, 1, 3));
            for (int i = 0; i < effect.getParticleCount();
                    i++) effect.setParticleAppearance(i,
                    new GlowParticles.Appearance(4, .2, 1, 3));
            var image = render(effect, 400, 240);
            assertTrue(Arrays.stream(pixels(image)).anyMatch(
                    pixel -> (pixel >>> 24) > 0));
            assertTrue(GlowParticleSprites.coreCacheSize() <= 84);
        });
    }

    @Test
    void invalidValuesAreRejectedAndResetRestoresExactOriginalWithoutSeeking(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var effect = new GlowParticles();
            effect.setAnimationEnabled(false);
            effect.setTimeSeconds(20);
            int[] original = pixels(render(effect, 1000, 500));
            assertThrows(IllegalArgumentException.class,
                    () -> effect.setParticleCount(-1));
            assertThrows(IllegalArgumentException.class,
                    () -> effect.setParticleCount(201));
            assertThrows(IllegalArgumentException.class,
                    () -> effect.setSizeMultiplier(.1));
            assertThrows(IllegalArgumentException.class, () -> effect.setGlowReach(3.1));
            assertThrows(IllegalArgumentException.class, () -> effect.setDistance(-.1));
            assertThrows(IllegalArgumentException.class,
                    () -> effect.setDistance(Double.NaN));
            assertThrows(IllegalArgumentException.class,
                    () -> effect.setSizeMultiplier(Double.POSITIVE_INFINITY));
            assertThrows(NullPointerException.class, () -> effect.setAppearance(null));
            assertThrows(IndexOutOfBoundsException.class, () ->
                    effect.setParticleAppearance(20, GlowParticles.Appearance.DEFAULT));
            effect.setParticleCount(80);
            effect.setDistance(.8);
            effect.setGlowReach(3);
            effect.setSeed(142);
            effect.setParticleAppearance(0, new GlowParticles.Appearance(3, .2, .8, 2));
            effect.setTravelDistance(5202);
            effect.resetConfiguration();
            assertEquals(20, effect.getTimeSeconds());
            assertFalse(effect.isAnimationEnabled());
            assertEquals(0, effect.getSeed());
            assertEquals(0, effect.getTravelDistance());
            assertArrayEquals(original, pixels(render(effect, 1000, 500)));
        });
    }

    @Test
    void galleryEditsGlobalAndIndividualValuesAndExportsRealComponentPreviews(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var gallery = new GlowParticlesGallery();
            render(gallery, 1040, 650);
            var effect = (GlowParticles) named(gallery, "regular-glow-particles");
            effect.setAnimationEnabled(false);
            JSpinner count = (JSpinner) named(gallery, "glow-count");
            JComboBox<?> target = (JComboBox<?>) named(gallery, "glow-target");
            JSpinner size = (JSpinner) named(gallery, "glow-size");
            JSpinner intensity = (JSpinner) named(gallery, "glow-intensity");
            JSpinner distance = (JSpinner) named(gallery, "glow-distance");
            JSpinner reach = (JSpinner) named(gallery, "glow-reach");
            count.setValue(40);
            assertEquals(40, effect.getParticleCount());
            assertEquals(41, target.getItemCount());
            size.setValue(150);
            distance.setValue(25);
            reach.setValue(200);
            intensity.setValue(70);
            assertEquals(new GlowParticles.Appearance(1.5, .7,
                    .25, 2), effect.getAppearance());
            target.setSelectedIndex(2);
            assertEquals(100, size.getValue());
            distance.setValue(80);
            intensity.setValue(40);
            assertEquals(new GlowParticles.Appearance(1, .4, .8,
                    1), effect.getParticleAppearance(1));
            assertEquals(GlowParticles.Appearance.DEFAULT,
                    effect.getParticleAppearance(0));
            target.setSelectedIndex(0);
            assertEquals(150, size.getValue());
            assertEquals(25, distance.getValue());
            count.setValue(0);
            assertEquals(1, target.getItemCount());
            ((JButton) named(gallery, "glow-reset")).doClick(0);
            assertEquals(20, effect.getParticleCount());
            assertEquals(20, effect.getTimeSeconds());
            assertEquals(100, size.getValue());
            assertEquals(GlowParticles.Appearance.DEFAULT,
                    effect.getParticleAppearance(1));
            assertFalse(((JCheckBox) named(gallery,
                    "glow-original-travel")).isSelected());
            String destination = System.getenv("LEVERAGE_DYNAMIC_GLOW_PREVIEW");
            if (destination != null) try {
                Path output = Path.of(destination);
                Files.createDirectories(output);
                ImageIO.write(render(gallery, 1040, 650), "PNG", output.resolve(
                        "leverage-dynamic-particles-gallery.png").toFile());
                ImageIO.write(render(gallery, 820, 520), "PNG",
                        output.resolve("leverage-dynamic-particles-small.png").toFile());
                BufferedImage sheet = new BufferedImage(1000, 600,
                        BufferedImage.TYPE_INT_RGB);
                Graphics2D g = sheet.createGraphics();
                try {
                    g.setColor(new Color(0x050a10));
                    g.fillRect(0, 0, 1000, 600);
                    String[] names = { "Original", "Lejanía: 100 %",
                            "Alcance: 200 %", "Sin halo" };
                    var settings = new GlowParticles.Appearance[] {
                            GlowParticles.Appearance.DEFAULT,
                            GlowParticles.Appearance.DEFAULT.withDistance(1),
                                GlowParticles.Appearance.DEFAULT.withGlowReach(2),
                            GlowParticles.Appearance.DEFAULT.withGlowReach(0) };
                    for (int i = 0; i < 4; i++) {
                        int x = (i % 2) * 500, y = (i / 2) * 300;
                        Graphics2D cell = (Graphics2D) g.create(x, y, 500, 300);
                        try {
                            GlowParticleSprites.paint(cell, new GlowParticleModel.State(1,
                                    2, 250, 130, 2.6, 1), settings[i],
                                    GlowParticles.Appearance.DEFAULT);
                            cell.setFont(LauncherFonts.minecraftSeven(16));
                            cell.setColor(Color.WHITE);
                            cell.drawString(names[i], 25, 270);
                        } finally { cell.dispose(); }
                    }
                } finally { g.dispose(); }
                ImageIO.write(sheet, "PNG", output.resolve(
                        "leverage-dynamic-particles-parameters.png").toFile());
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
        });
    }

    private static BufferedImage single(GlowParticles.Appearance appearance) {
        BufferedImage image = new BufferedImage(400, 400, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            GlowParticleSprites.paint(g, new GlowParticleModel.State(1, 2, 200,
                    200, 2, 1), appearance, GlowParticles.Appearance.DEFAULT);
        }
        finally { g.dispose(); }
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
