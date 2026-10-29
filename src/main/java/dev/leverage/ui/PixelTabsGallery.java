package dev.leverage.ui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

// Reference-sized tab component and a variable-width example, without modifying dashboard
// navigation.
public final class PixelTabsGallery extends JPanel {
    public PixelTabsGallery() {
        super(new BorderLayout());
        setName("pixel-tabs-gallery");
        PixelTabs years = example(new String[] { "2026",
                "2025", "2024", "2023", "MORE" });
        years.setName("pixel-tabs-years");
        PixelTabs sections = example(new String[] { "INICIO",
                "MODS", "RECURSOS", "AJUSTES" });
        sections.setName("pixel-tabs-sections");
        JPanel views = new JPanel(new CardLayout());
        views.add(center(years), "years");
        views.add(center(sections), "sections");
        add(views, BorderLayout.CENTER);
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEADING, 12, 10));
        JComboBox<String> view = new JComboBox<>(new String[] { "Años (referencia)",
                "Secciones (anchos distintos)" });
        view.setName("pixel-tabs-view");
        view.getAccessibleContext().setAccessibleName("Ejemplo de pestañas");
        view.addActionListener(event -> ((CardLayout) views.getLayout()).show(views,
                view.getSelectedIndex() == 0 ? "years" : "sections"));
        JCheckBox animate = new JCheckBox("Animar indicador", true);
        animate.setName("pixel-tabs-animate");
        animate.addActionListener(event -> {
            years.setAnimationEnabled(animate.isSelected());
            sections.setAnimationEnabled(animate.isSelected());
        });
        JButton next = new JButton("Siguiente pestaña");
        next.setName("pixel-tabs-next");
        next.addActionListener(event -> {
            PixelTabs tabs = view.getSelectedIndex() == 0 ? years : sections;
            tabs.setSelectedIndex((tabs.getSelectedIndex() + 1) % tabs.getTabCount());
        });
        controls.add(view);
        controls.add(animate);
        controls.add(next);
        for (var child : controls.getComponents()) child.setFont(
                LauncherFonts.minecraftSeven(14));
        add(controls, BorderLayout.NORTH);
        JLabel note = new JLabel(
                "Posición: 0,5 s · ancho: 0,25 s · clic, flechas, Inicio/Fin y rueda");
        note.setFont(LauncherFonts.minecraftSeven(13));
        note.setBorder(BorderFactory.createEmptyBorder(8, 12, 10, 12));
        add(note, BorderLayout.SOUTH);
    }
    private static PixelTabs example(String[] titles) {
        PixelTabs tabs = new PixelTabs();
        tabs.setPreferredSize(new Dimension(608, 300));
        for (String title : titles) {
            JPanel page = new JPanel(new GridBagLayout());
            page.setBackground(new Color(0x171615));
            GridBagConstraints grid = new GridBagConstraints();
            grid.gridx = 0;
            grid.insets = new Insets(8, 16, 8, 16);
            JLabel heading = new JLabel("Contenido de " + title);
            heading.setFont(LauncherFonts.minecraftTen(23));
            heading.setForeground(new Color(0xe6e2de));
            page.add(heading, grid);
            grid.gridy = 1;
            JLabel subtitle = new JLabel("Cada pestaña muestra su propio panel.");
            subtitle.setFont(LauncherFonts.minecraftSeven(15));
            subtitle.setForeground(new Color(0xaba09c));
            page.add(subtitle, grid);
            tabs.addTab(title, page);
        }
        return tabs;
    }
    private static JPanel center(PixelTabs tabs) {
        JPanel host = new JPanel(new GridBagLayout());
        host.setBackground(new Color(0x262423));
        host.setBorder(BorderFactory.createEmptyBorder(24, 20, 24, 20));
        host.add(tabs);
        return host;
    }
}
