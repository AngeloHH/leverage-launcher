package dev.leverage.ui;

import com.formdev.flatlaf.FlatDarkLaf;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.JTextField;
import javax.swing.JTabbedPane;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.lang.reflect.InvocationTargetException;

// Standalone development window for approving launcher components before layout work.
public final class ComponentGallery {
    private static final Color TEXT = new Color(28, 28, 28);
    private static final Color MUTED_TEXT = new Color(73, 73, 73);

    private ComponentGallery() {
    }

    public static void open() {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException(
                    "La galería gráfica no está disponible en un entorno headless.");
        }
        if (SwingUtilities.isEventDispatchThread()) {
            createAndShow();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(ComponentGallery::createAndShow);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Se interrumpió el inicio de la galería.",
                    exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("No se pudo abrir la galería.",
                    exception.getCause());
        }
    }

    private static void createAndShow() {
        FlatDarkLaf.setup();
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("Button.arc", 0);

        JFrame frame = new JFrame("Leverage — Component Gallery");
        LauncherIcon.apply(frame);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(820, 520));
        frame.setSize(1040, 650);
        frame.setLocationRelativeTo(null);
        frame.setContentPane(createContent());
        frame.setVisible(true);
    }

    private static JPanel createContent() {
        JPanel host = new JPanel(new java.awt.BorderLayout());
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Botones", createButtonsContent());
        tabs.addTab("Contenedor pixelado", new PixelatedPanelGallery());
        tabs.addTab("Partículas regulares", new GlowParticlesGallery());
        tabs.addTab("Partículas moradas", new PurpleParticlesGallery());
        tabs.addTab("Pestañas", new PixelTabsGallery());
        tabs.addTab("Carrusel", new PixelCarouselGallery());
        tabs.addTab("Dropdown", new PixelDropdownGallery());
        tabs.addTab("Inputs", new PixelInputsGallery());
        tabs.setSelectedIndex(7);
        host.add(tabs, java.awt.BorderLayout.CENTER);
        return host;
    }

    private static JPanel createButtonsContent() {
        StoneTexturePanel background = new StoneTexturePanel();
        background.setLayout(new GridBagLayout());
        background.setBorder(BorderFactory.createEmptyBorder(36, 42, 36, 42));

        JPanel card = new TranslucentCard();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createEmptyBorder(30, 38, 28, 38));

        JLabel title = new JLabel("LEVERAGE COMPONENT GALLERY");
        title.setAlignmentX(0.5f);
        title.setForeground(TEXT);
        title.setFont(LauncherFonts.minecraftTen(27f));

        JLabel subtitle = new JLabel(
                "Reusable pixel buttons — hover, click and navigate with Tab");
        subtitle.setAlignmentX(0.5f);
        subtitle.setForeground(MUTED_TEXT);
        subtitle.setFont(LauncherFonts.minecraftSeven(16f));

        JLabel status = new JLabel("Select a component to preview its action",
                SwingConstants.CENTER);
        status.setAlignmentX(0.5f);
        status.setForeground(new Color(45, 94, 38));
        status.setFont(LauncherFonts.minecraftSeven(15f));

        JTextField input = createTextInput();

        card.add(title);
        card.add(Box.createVerticalStrut(8));
        card.add(subtitle);
        card.add(Box.createVerticalStrut(20));
        card.add(input);
        card.add(Box.createVerticalStrut(19));
        card.add(createButtonGrid(status));
        card.add(Box.createVerticalStrut(22));
        card.add(status);

        GridBagConstraints constraints = new GridBagConstraints();
        constraints.weightx = 1;
        constraints.weighty = 1;
        constraints.fill = GridBagConstraints.NONE;
        background.add(card, constraints);
        return background;
    }

    private static JTextField createTextInput() {
        JTextField input = new JTextField();
        input.putClientProperty("JTextField.placeholderText", "Text input");
        input.setAlignmentX(0.5f);
        input.setFont(LauncherFonts.minecraftSeven(16f));
        input.setForeground(new Color(24, 24, 24));
        input.setCaretColor(new Color(24, 24, 24));
        input.setBackground(Color.WHITE);
        input.setOpaque(true);
        input.setPreferredSize(new Dimension(506, 44));
        input.setMaximumSize(new Dimension(506, 44));
        input.setMinimumSize(new Dimension(220, 44));
        input.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(
                Color.BLACK, 2), BorderFactory.createEmptyBorder(0, 11, 0, 11)));
        return input;
    }

    private static JPanel createButtonGrid(JLabel status) {
        JPanel grid = new JPanel(new GridBagLayout());
        grid.setOpaque(false);

        PixelButton primary = button("Primary button", PixelButton.Variant.PRIMARY,
                PixelButton.Glyph.NONE, 250);
        PixelButton secondary = button("Secondary button",
                PixelButton.Variant.SECONDARY, PixelButton.Glyph.NONE, 230);
        PixelButton tertiary = button("Tertiary button",
                PixelButton.Variant.NEUTRAL, PixelButton.Glyph.NONE, 250);
        PixelButton external = button("External button", PixelButton.Variant.PRIMARY,
                PixelButton.Glyph.EXTERNAL_LINK, 230);
        PixelButton icon = button("Icon button", PixelButton.Variant.DARK,
                PixelButton.Glyph.HEADSET, 210);
        PixelButton disabled = button("Disabled button", PixelButton.Variant.DISABLED,
                PixelButton.Glyph.REFRESH, 250);
        disabled.setEnabled(false);

        add(grid, primary, 0, 0);
        add(grid, secondary, 1, 0);
        add(grid, tertiary, 0, 1);
        add(grid, external, 1, 1);
        add(grid, icon, 0, 2);
        add(grid, disabled, 1, 2);

        for (var component : grid.getComponents()) {
            if (component instanceof PixelButton pixelButton && pixelButton.isEnabled()) {
                pixelButton.addActionListener(event -> status.setText(
                        pixelButton.getText() + " pressed"));
            }
        }
        return grid;
    }

    private static PixelButton button(String text, PixelButton.Variant variant,
            PixelButton.Glyph glyph, int width) {
        PixelButton button = new PixelButton(text, variant, glyph);
        button.setPreferredSize(new Dimension(width, 50));
        return button;
    }

    private static void add(JPanel panel, PixelButton button, int x, int y) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = x;
        constraints.gridy = y;
        constraints.insets = new Insets(10, 13, 10, 13);
        constraints.anchor = GridBagConstraints.CENTER;
        panel.add(button, constraints);
    }

    private static final class TranslucentCard extends JPanel {
        private TranslucentCard() {
            setOpaque(false);
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, getWidth(), getHeight());

                g.setColor(new Color(185, 185, 185));
                g.fillRect(0, 0, getWidth(), 2);
                g.fillRect(0, getHeight() - 2, getWidth(), 2);
                g.fillRect(0, 0, 2, getHeight());
                g.fillRect(getWidth() - 2, 0, 2, getHeight());
            } finally {
                g.dispose();
            }
            super.paintComponent(graphics);
        }
    }
}
