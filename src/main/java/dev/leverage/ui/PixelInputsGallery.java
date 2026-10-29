package dev.leverage.ui;

import javax.swing.*;
import java.awt.*;
import java.util.List;

// Ordinary Swing fields using the same surface as PixelDropdown; no form or network
// logic.
public final class PixelInputsGallery extends JPanel {
    public PixelInputsGallery() {
        super(new BorderLayout());
        setName("pixel-inputs-gallery");
        JTextField text = PixelInputStyle.apply(new JTextField("Leverage", 24));
        text.setName("pixel-input-text");
        JTextField empty = PixelInputStyle.apply(new JTextField(24));
        empty.setName("pixel-input-empty");
        empty.putClientProperty("JTextField.placeholderText", "Nombre de usuario");
        JPasswordField password = PixelInputStyle.apply(
                new JPasswordField("ejemplo", 24));
        password.setName("pixel-input-password");
        PixelDropdown<String> dropdown = new PixelDropdown<>(List.of("United States",
                "Uruguay", "Venezuela"));
        dropdown.setName("pixel-input-dropdown");
        dropdown.setPreferredSize(new Dimension(434, 48));
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEADING, 12, 10));
        JCheckBox disabled = new JCheckBox("Deshabilitados");
        disabled.setName("pixel-inputs-disabled");
        JCheckBox readOnly = new JCheckBox("Solo lectura");
        readOnly.setName("pixel-inputs-read-only");
        disabled.addActionListener(event -> {
            for (JTextField field : new JTextField[] { text, empty,
                    password }) field.setEnabled(!disabled.isSelected());
            dropdown.setEnabled(!disabled.isSelected());
        });
        readOnly.addActionListener(event -> {
            for (JTextField field : new JTextField[] { text, empty,
                    password }) field.setEditable(!readOnly.isSelected());
        });
        controls.add(disabled);
        controls.add(readOnly);
        for (Component child : controls.getComponents()) child.setFont(
                LauncherFonts.minecraftSeven(14));
        add(controls, BorderLayout.NORTH);
        JPanel examples = new JPanel(new GridBagLayout());
        examples.setBackground(new Color(0x171615));
        GridBagConstraints grid = new GridBagConstraints();
        grid.gridx = 0;
        grid.fill = GridBagConstraints.HORIZONTAL;
        JLabel heading = new JLabel("INPUTS");
        heading.setFont(LauncherFonts.minecraftTen(24));
        heading.setForeground(Color.WHITE);
        grid.insets = new Insets(0, 0, 12, 0);
        examples.add(heading, grid);
        addField(examples, grid, 1, "Dropdown (referencia)", dropdown);
        addField(examples, grid, 3, "Texto", text);
        addField(examples, grid, 5, "Vacío / placeholder", empty);
        addField(examples, grid, 7, "Contraseña", password);
        add(examples, BorderLayout.CENTER);
        JLabel note = new JLabel("Pulsa Enter para probar el campo (sin enviar datos).");
        note.setName("pixel-inputs-note");
        note.setFont(LauncherFonts.minecraftSeven(12));
        note.setBorder(BorderFactory.createEmptyBorder(8, 12, 10, 12));
        add(note, BorderLayout.SOUTH);
        for (JTextField field : new JTextField[] { text, empty,
                password }) field.addActionListener(event -> note.setText(
                "Enter recibido (sin enviar ni mostrar el contenido)."));
    }
    private static void addField(JPanel host, GridBagConstraints grid,
            int row, String title, JComponent field) {
        JLabel label = new JLabel(title);
        label.setFont(LauncherFonts.minecraftSeven(14));
        label.setForeground(Color.WHITE);
        label.setLabelFor(field);
        field.getAccessibleContext().setAccessibleName(title);
        grid.gridy = row;
        grid.insets = new Insets(0, 0, 4, 0);
        host.add(label, grid);
        grid.gridy = row + 1;
        grid.insets = new Insets(0, 0, 10, 0);
        host.add(field, grid);
    }
}
