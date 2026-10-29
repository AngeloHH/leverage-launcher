package dev.leverage.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import javax.swing.text.PlainDocument;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PixelInputStyleTest {
    @BeforeEach void useTheLaunchersLookAndFeel() throws Exception {
        SwingUtilities.invokeAndWait(com.formdev.flatlaf.FlatDarkLaf::setup);
    }

    @Test void ordinaryInputAndDropdownShareTheExactSurfaceOutlineAndFont(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextField field = PixelInputStyle.apply(new JTextField("Leverage"));
            PixelDropdown<String> dropdown = new PixelDropdown<>(List.of("Leverage"));
            layout(field, 434, 48);
            layout(dropdown, 434, 48);
            BufferedImage input = render(field), select = render(dropdown);
            for (int[] point : new int[][] { { 0, 12 }, { 12, 0 }, { 433, 12 }, { 12,
                    47 }, { 12, 2 }, { 12, 7 }, { 12, 8 }, { 100, 12 } }) assertEquals(
                    rgb(select, point[0], point[1]), rgb(input, point[0], point[1]));
            assertEquals(0x898481, rgb(input, 0, 12));
            assertEquals(0x131211, rgb(input, 12, 2));
            assertEquals(0x262423, rgb(input, 100, 12));
            assertEquals(dropdown.getFont(), field.getFont());
            assertEquals("Minecraft Seven", field.getFont().getFamily());
            assertEquals(16, field.getFont().getSize());
            assertEquals(48, field.getPreferredSize().height);
            assertEquals(new Insets(10, 14, 10, 10), field.getInsets());
            assertEquals(Color.WHITE, field.getCaretColor());
        });
    }

    @Test void stylingPreservesValueDocumentFilterListenersAndSelection(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PlainDocument document = new PlainDocument();
            document.setDocumentFilter(new DocumentFilter() {
                @Override public void replace(FilterBypass bypass,
                        int offset, int length, String text,
                        AttributeSet attributes) throws BadLocationException {
                    super.replace(bypass, offset, length, text == null ? null
                            : text.replaceAll("[^0-9]", ""), attributes);
                }
            });
            JTextField field = new JTextField(document, "1234", 20);
            field.select(1, 3);
            AtomicInteger events = new AtomicInteger();
            field.addActionListener(event -> events.incrementAndGet());
            assertSame(field, PixelInputStyle.apply(field));
            assertSame(document, field.getDocument());
            assertNotNull(((AbstractDocument) field.getDocument()).getDocumentFilter());
            assertEquals("1234", field.getText());
            assertEquals(1, field.getSelectionStart());
            assertEquals(3, field.getSelectionEnd());
            field.replaceSelection("a9b");
            assertEquals("194", field.getText());
            field.postActionEvent();
            assertEquals(1, events.get());
            assertNotNull(field.getInputMap().get(KeyStroke.getKeyStroke("LEFT")));
            assertNotNull(field.getActionMap().get("select-all"));
        });
    }

    @Test void theSameStyleWorksForPasswordsWithoutChangingTheirMaskOrValue(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPasswordField field = new JPasswordField("contraseña-de-prueba", 20);
            field.setEchoChar('*');
            var document = field.getDocument();
            assertSame(field, PixelInputStyle.apply(field));
            assertSame(document, field.getDocument());
            assertEquals('*', field.getEchoChar());
            assertTrue(field.echoCharIsSet());
            assertArrayEquals("contraseña-de-prueba".toCharArray(), field.getPassword());
            layout(field, 434, 48);
            render(field);
            SwingUtilities.updateComponentTreeUI(field);
            assertEquals('*', field.getEchoChar());
            assertEquals("Minecraft Seven", field.getFont().getFamily());
            field.setText("");
            assertEquals(0, field.getPassword().length);
            field.setText("otra");
            assertArrayEquals("otra".toCharArray(), field.getPassword());
        });
    }

    @Test void existingPlaceholderPropertiesStillRenderWithoutBecomingTheValue(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextField field = new JTextField();
            field.putClientProperty("JTextField.placeholderText", "Nombre de usuario");
            PixelInputStyle.apply(field);
            layout(field, 434, 48);
            BufferedImage placeholder = render(field);
            assertEquals("", field.getText());
            assertEquals("Nombre de usuario", field.getClientProperty(
                    "JTextField.placeholderText"));
            field.putClientProperty("JTextField.placeholderText", "");
            BufferedImage empty = render(field);
            assertTrue(differences(placeholder, empty) > 30);
            field.setText("Angel");
            assertEquals("Angel", field.getText());
        });
    }

    @Test void themeUpdatesDisabledAndReadOnlyStatesKeepTheRecessedStyle(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextField field = PixelInputStyle.apply(new JTextField("Texto"));
            layout(field, 434, 48);
            for (boolean enabled : new boolean[] { true, false, true }) {
                field.setEnabled(enabled);
                field.setEditable(false);
                SwingUtilities.updateComponentTreeUI(field);
                BufferedImage image = render(field);
                assertEquals(0x898481, rgb(image, 0, 12));
                assertEquals(0x131211, rgb(image, 12, 2));
                assertEquals(0x262423, rgb(image, 100, 12));
                assertEquals("Minecraft Seven", field.getFont().getFamily());
                assertEquals(new Insets(10, 14, 10, 10), field.getInsets());
                assertFalse(field.isEditable());
            }
            field.setEditable(true);
            assertTrue(field.isEditable());
            assertEquals("Texto", field.getText());
        });
    }

    @Test void applyingTheStyleTwiceDoesNotGrowPaddingOrDuplicateActions(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextField field = new JTextField(20);
            AtomicInteger events = new AtomicInteger();
            field.addActionListener(event -> events.incrementAndGet());
            PixelInputStyle.apply(field);
            Dimension size = field.getPreferredSize();
            PixelInputStyle.apply(field);
            assertEquals(size, field.getPreferredSize());
            assertEquals(new Insets(10, 14, 10, 10), field.getInsets());
            field.postActionEvent();
            assertEquals(1, events.get());
            assertEquals(135, field.getMinimumSize().width);
            assertEquals(48, field.getMaximumSize().height);
            assertThrows(NullPointerException.class, () -> PixelInputStyle.apply(null));
        });
    }

    @Test void longValuesStillUseNativeHorizontalScrollingAndTextSelection(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextField field = PixelInputStyle.apply(new JTextField(
                    "Texto largo ".repeat(30)));
            layout(field, 180, 48);
            field.setCaretPosition(field.getDocument().getLength());
            render(field);
            try {
                field.scrollRectToVisible(field.modelToView2D(
                        field.getDocument().getLength()).getBounds());
            }
            catch (BadLocationException e) { throw new RuntimeException(e); }
            assertTrue(field.getScrollOffset() > 0);
            field.selectAll();
            assertEquals(field.getText(), field.getSelectedText());
            field.replaceSelection("Nuevo");
            assertEquals("Nuevo", field.getText());
        });
    }

    @Test void galleryProvidesEditableDisabledAndReadOnlyExamplesWithoutSendingContent(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PixelInputsGallery gallery = new PixelInputsGallery();
            layout(gallery, 820, 487);
            JTextField text = (JTextField) named(gallery, "pixel-input-text");
            JPasswordField password = (JPasswordField) named(gallery,
                    "pixel-input-password");
            assertTrue(password.echoCharIsSet());
            assertEquals(434, text.getWidth());
            assertEquals(48, text.getHeight());
            text.setText("Contenido privado de prueba");
            text.postActionEvent();
            JLabel note = (JLabel) named(gallery, "pixel-inputs-note");
            assertTrue(note.getText().startsWith("Enter recibido"));
            assertFalse(note.getText().contains(text.getText()));
            ((JCheckBox) named(gallery, "pixel-inputs-disabled")).doClick(0);
            assertFalse(text.isEnabled());
            assertFalse(password.isEnabled());
            ((JCheckBox) named(gallery, "pixel-inputs-disabled")).doClick(0);
            ((JCheckBox) named(gallery, "pixel-inputs-read-only")).doClick(0);
            assertTrue(text.isEnabled());
            assertFalse(text.isEditable());
            assertFalse(password.isEditable());
        });
    }

    @Test void fullGallerySelectsInputsAndRetainsAllTheApprovedComponents(
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
                assertEquals("Inputs", tabs.getTitleAt(7));
                assertInstanceOf(PixelDropdownGallery.class, tabs.getComponentAt(6));
                assertInstanceOf(PixelInputsGallery.class, tabs.getComponentAt(7));
                Component text = named(root, "pixel-input-text");
                assertTrue(text.getY() >= 0);
                assertEquals(48, text.getHeight());
            } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
        });
    }

    @Test void exportsNativeInputPreviewsWhenRequested() throws Exception {
        String directory = System.getenv("LEVERAGE_INPUT_PREVIEW");
        if (directory == null || directory.isBlank()) return;
        Path output = Path.of(directory);
        Files.createDirectories(output);
        SwingUtilities.invokeAndWait(() -> {
            try {
                PixelInputsGallery gallery = new PixelInputsGallery();
                layout(gallery, 1040, 617);
                ImageIO.write(render(gallery), "png",
                        output.resolve("leverage-inputs-gallery.png").toFile());
                layout(gallery, 820, 487);
                ImageIO.write(render(gallery), "png",
                        output.resolve("leverage-inputs-compact.png").toFile());
            } catch (Exception e) { throw new RuntimeException(e); }
        });
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
    private static int differences(BufferedImage first, BufferedImage second) {
        int count = 0;
        for (int y = 0; y < first.getHeight(); y++) for (int x = 0; x < first.getWidth();
                x++) if (first.getRGB(x, y) != second.getRGB(x, y)) count++;
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
