package dev.leverage.ui;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Graphics;

// Live, local-only preview of the actual Swing component; no launcher/account state.
public final class PixelatedPanelGallery extends JPanel {
    private final PixelatedPanel preview = new PixelatedPanel();

    public PixelatedPanelGallery() {
        super(new BorderLayout());
        setName("pixelated-panel-gallery");
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEADING, 12, 10));
        var placements = new JComboBox<>(new Choice[] {
                new Choice("Una esquina", PixelatedPanel.Placement.BOTTOM_LEFT),
                    new Choice("Dos esquinas inferiores",
                    PixelatedPanel.Placement.BOTTOM_CORNERS), new Choice(
                    "Esquinas opuestas", PixelatedPanel.Placement.OPPOSITE_CORNERS),
                    new Choice("Un lateral completo", PixelatedPanel.Placement.RIGHT),
                    new Choice("Dos laterales completos", PixelatedPanel.Placement.SIDES),
                    new Choice("Todo el contorno", PixelatedPanel.Placement.ALL) });
        placements.setName("pixelated-placement");
        placements.getAccessibleContext().setAccessibleName("Disposición del contenedor");
        placements.setFont(LauncherFonts.minecraftSeven(14f));
        placements.addActionListener(event -> preview.setPlacement(((
                Choice) placements.getSelectedItem()).placement()));
        JSpinner size = new JSpinner(new SpinnerNumberModel(28, 12, 64, 4));
        size.setName("pixelated-block-size");
        size.getAccessibleContext().setAccessibleName(
                "Tamaño único del cuadrado, en píxeles");
        size.addChangeListener(event -> preview.setBlockSize(((
                Number) size.getValue()).intValue()));
        JSpinner seed = new JSpinner(new SpinnerNumberModel(8, 0, 15, 1));
        seed.setName("pixelated-seed");
        seed.getAccessibleContext().setAccessibleName("Distribución del patrón");
        seed.addChangeListener(event -> preview.setSeed(((
                Number) seed.getValue()).longValue()));
        controls.add(placements);
        controls.add(label("Cuadrado (px)"));
        controls.add(size);
        controls.add(label("Distribución"));
        controls.add(seed);
        add(controls, BorderLayout.NORTH);

        JPanel backdrop = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics graphics) {
                for (int y = 0; y < getHeight(); y += 14) for (int x = 0;
                        x < getWidth(); x += 14) {
                    graphics.setColor(((x / 14 + y / 14) & 1) == 0
                            ? new Color(0xd8d7d4) : new Color(0xc8c7c4));
                    graphics.fillRect(x, y, 14, 14);
                }
            }
        };
        backdrop.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        preview.setName("pixelated-preview");
        backdrop.add(preview, BorderLayout.CENTER);
        add(backdrop, BorderLayout.CENTER);
        JLabel note = label(
                "El cuadriculado representa transparencia. El gris solo aparece hacia"
                + " abajo.");
        note.setBorder(BorderFactory.createEmptyBorder(8, 12, 10, 12));
        add(note, BorderLayout.SOUTH);
    }

    private static JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.setFont(LauncherFonts.minecraftSeven(14f));
        return label;
    }

    private record Choice(String label, PixelatedPanel.Placement placement) {
        @Override public String toString() { return label; }
    }
}
