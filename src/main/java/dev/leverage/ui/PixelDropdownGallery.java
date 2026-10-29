package dev.leverage.ui;

import javax.swing.*;
import java.awt.*;
import java.util.List;

// A usable native dropdown plus an inline inspection of the popup's renderer and
// scrollbar.
public final class PixelDropdownGallery extends JPanel {
    public PixelDropdownGallery() {
        super(new BorderLayout());
        setName("pixel-dropdown-gallery");
        PixelDropdown<String> countries = new PixelDropdown<>(List.of("Argentina",
                "Chile", "España", "México", "United States", "Uruguay", "Venezuela"));
        countries.setName("dropdown-countries");
        countries.getAccessibleContext().setAccessibleName("País");
        countries.setSelectedIndex(4);
        PixelDropdown<String> versions = new PixelDropdown<>(List.of("Última versión",
                "1.21.11", "1.21.10", "1.21.9", "1.21.8", "1.21.7",
                "1.21.6", "1.21.5", "1.21.4"));
        versions.setName("dropdown-versions");
        versions.getAccessibleContext().setAccessibleName("Versión de Minecraft");
        JPanel examples = new JPanel(new CardLayout());
        examples.add(example("PAÍS", countries), "countries");
        examples.add(example("VERSIÓN", versions), "versions");
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEADING, 12, 10));
        JComboBox<String> view = new JComboBox<>(new String[] {
                "Países (referencia)", "Versiones" });
        view.setName("dropdown-gallery-view");
        view.getAccessibleContext().setAccessibleName("Ejemplo de dropdown");
        view.addActionListener(event -> ((CardLayout) examples.getLayout()).show(examples,
                view.getSelectedIndex() == 0 ? "countries" : "versions"));
        JCheckBox disabled = new JCheckBox("Deshabilitado");
        disabled.setName("dropdown-gallery-disabled");
        disabled.addActionListener(event -> {
            countries.setEnabled(!disabled.isSelected());
            versions.setEnabled(!disabled.isSelected());
        });
        controls.add(view);
        controls.add(disabled);
        for (var child : controls.getComponents()) child.setFont(
                LauncherFonts.minecraftSeven(14));
        add(controls, BorderLayout.NORTH);
        add(examples, BorderLayout.CENTER);
        JLabel note = new JLabel(
                "Clic para abrir · flechas · Enter / Escape · escribir para buscar un"
                + "a opción");
        note.setFont(LauncherFonts.minecraftSeven(12));
        note.setBorder(BorderFactory.createEmptyBorder(8, 12, 10, 12));
        add(note, BorderLayout.SOUTH);
    }
    private static JPanel example(String title, PixelDropdown<String> dropdown) {
        JPanel host = new JPanel(new GridBagLayout());
        host.setBackground(new Color(0x171615));
        GridBagConstraints grid = new GridBagConstraints();
        grid.gridx = 0;
        grid.fill = GridBagConstraints.HORIZONTAL;
        grid.insets = new Insets(0, 0, 14, 0);
        JLabel heading = new JLabel(title);
        heading.setFont(LauncherFonts.minecraftTen(24));
        heading.setForeground(Color.WHITE);
        host.add(heading, grid);
        grid.gridy = 1;
        dropdown.setPreferredSize(new Dimension(434, 48));
        host.add(dropdown, grid);
        grid.gridy = 2;
        JLabel caption = new JLabel("Vista de las opciones del menú");
        caption.setFont(LauncherFonts.minecraftSeven(13));
        caption.setForeground(new Color(0xaba09c));
        host.add(caption, grid);
        grid.gridy = 3;
        JComponent options = dropdown.createOptionsPreview(3);
        options.setName(dropdown.getName() + "-options");
        host.add(options, grid);
        grid.gridy = 4;
        JLabel selection = new JLabel("Seleccionado: " + dropdown.getSelectedItem());
        selection.setName(dropdown.getName() + "-selection");
        selection.setFont(LauncherFonts.minecraftSeven(14));
        selection.setForeground(Color.WHITE);
        host.add(selection, grid);
        dropdown.addActionListener(event -> selection.setText("Seleccionado: "
                + dropdown.getSelectedItem()));
        return host;
    }
}
