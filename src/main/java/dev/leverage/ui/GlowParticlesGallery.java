package dev.leverage.ui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

// Local gallery for the orange glow particles. Does not modify the launcher home screen.
public final class GlowParticlesGallery extends JPanel {
    public GlowParticlesGallery() {
        super(new BorderLayout());
        setName("glow-particles-gallery");
        GlowParticles particles = new GlowParticles();
        particles.setName("regular-glow-particles");
        particles.setTimeSeconds(20);
        JPanel cards = new JPanel(new CardLayout());
        JPanel backdrop = new JPanel(new BorderLayout());
        backdrop.setBackground(new Color(0x050a10));
        backdrop.add(particles, BorderLayout.CENTER);
        cards.add(backdrop, "animation");
        cards.add(new Profiles(), "profiles");
        add(cards, BorderLayout.CENTER);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEADING, 12, 10));
        JComboBox<String> view = new JComboBox<>(new String[] { "Animación completa",
                "Los cuatro tipos" });
        view.setName("glow-view");
        view.getAccessibleContext().setAccessibleName("Vista de partículas");
        JCheckBox animate = new JCheckBox("Animar", true);
        animate.setName("glow-animate");
        animate.addActionListener(event -> particles.setAnimationEnabled(
                animate.isSelected()));
        view.addActionListener(event -> ((CardLayout) cards.getLayout()).show(cards,
                view.getSelectedIndex() == 0 ? "animation" : "profiles"));
        JSpinner time = new JSpinner(new SpinnerNumberModel(20d, 0d, 3600d, 1d));
        time.setName("glow-time");
        time.getAccessibleContext().setAccessibleName("Ir al segundo de la animación");
        time.addChangeListener(event -> particles.setTimeSeconds(((
                Number) time.getValue()).doubleValue()));
        JCheckBox literalTravel = new JCheckBox("Recorrido original (5202 px)");
        literalTravel.setName("glow-original-travel");
        literalTravel.addActionListener(event -> particles.setTravelDistance(
                literalTravel.isSelected() ? 5202 : 0));
        controls.add(view);
        controls.add(animate);
        controls.add(new JLabel("Ir a (s)"));
        controls.add(time);
        controls.add(literalTravel);
        for (var component : controls.getComponents()) component.setFont(
                LauncherFonts.minecraftSeven(14f));
        add(controls, BorderLayout.NORTH);
        JLabel note = new JLabel();
        note.setFont(LauncherFonts.minecraftSeven(13f));
        note.setBorder(BorderFactory.createEmptyBorder(8, 12, 10, 12));
        Runnable updateNote = () -> note.setText(particles.getParticleCount()
                + " partículas · " + (literalTravel.isSelected()
                ? "recorrido original: 5202 px" : "recorrido adaptado al panel"));
        literalTravel.addActionListener(event -> updateNote.run());
        updateNote.run();
        add(note, BorderLayout.SOUTH);
        JPanel inspector = createInspector(particles, time, literalTravel, updateNote);
        add(inspector, BorderLayout.EAST);
        view.addActionListener(event -> {
            inspector.setVisible(view.getSelectedIndex() == 0);
            revalidate();
            repaint();
        });
    }

    private JPanel createInspector(GlowParticles particles, JSpinner time,
            JCheckBox literalTravel, Runnable updateNote) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setPreferredSize(new Dimension(270, 0));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 1, 0, 0, new Color(0x454548)),
                BorderFactory.createEmptyBorder(12, 12, 12, 12)));
        GridBagConstraints grid = new GridBagConstraints();
        grid.gridx = 0;
        grid.gridy = 0;
        grid.insets = new Insets(5, 0, 5, 0);
        grid.fill = GridBagConstraints.HORIZONTAL;
        grid.weightx = 1;
        grid.gridwidth = 2;
        JLabel title = new JLabel("Partículas dinámicas");
        title.setFont(LauncherFonts.minecraftTen(18));
        panel.add(title, grid);
        JSpinner count = spinner("glow-count", 20, 0, GlowParticles.MAX_PARTICLES, 1);
        JComboBox<String> target = new JComboBox<>();
        target.setName("glow-target");
        target.setMaximumRowCount(12);
        target.getAccessibleContext().setAccessibleName(
                "Editar el conjunto o una partícula individual");
        JSpinner size = spinner("glow-size", 100, 25, 400, 25);
        JSpinner intensity = spinner("glow-intensity", 100, 0, 100, 5);
        JSpinner distance = spinner("glow-distance", 0, 0, 100, 5);
        JSpinner reach = spinner("glow-reach", 100, 0, 300, 25);
        count.setToolTipText("De 0 a " + GlowParticles.MAX_PARTICLES
                + ". Los cambios no reinician la animación.");
        size.setToolTipText("100 % es el tamaño original. No depende de la lejanía.");
        intensity.setToolTipText(
                "100 % es la intensidad original; 0 % oculta las partículas.");
        distance.setToolTipText(
                "0 %: aspecto original. 100 %: centro más difuso. No cambia tamaño ni"
                + " velocidad.");
        reach.setToolTipText(
                "100 % es el halo original. 0 % quita el halo, sin ocultar el centro.");
        row(panel, grid, 1, "Cantidad", count);
        grid.gridy = 2;
        grid.gridx = 0;
        grid.gridwidth = 2;
        panel.add(new JLabel("Editar"), grid);
        grid.gridy = 3;
        panel.add(target, grid);
        row(panel, grid, 4, "Tamaño (%)", size);
        row(panel, grid, 5, "Intensidad (%)", intensity);
        row(panel, grid, 6, "Lejanía (%)", distance);
        row(panel, grid, 7, "Alcance (%)", reach);
        JTextArea help = new JTextArea();
        help.setEditable(false);
        help.setFocusable(false);
        help.setOpaque(false);
        help.setLineWrap(true);
        help.setWrapStyleWord(true);
        help.setRows(4);
        grid.gridy = 8;
        grid.gridx = 0;
        grid.gridwidth = 2;
        panel.add(help, grid);
        JButton reset = new JButton("Restaurar original");
        reset.setName("glow-reset");
        grid.gridy = 9;
        panel.add(reset, grid);
        grid.gridy = 10;
        grid.weighty = 1;
        panel.add(javax.swing.Box.createVerticalStrut(1), grid);

        boolean[] loading = { false };
        Runnable loadValues = () -> {
            if (loading[0] || target.getSelectedIndex() < 0) return;
            loading[0] = true;
            try {
                int index = target.getSelectedIndex() - 1;
                var appearance = index < 0 ? particles.getAppearance()
                        : particles.getParticleAppearance(index);
                size.setValue((int) Math.round(appearance.size() * 100));
                intensity.setValue((int) Math.round(appearance.intensity() * 100));
                distance.setValue((int) Math.round(appearance.distance() * 100));
                reach.setValue((int) Math.round(appearance.glowReach() * 100));
                help.setText(index < 0 ? (
                        "Los ajustes del conjunto se combinan con los de cada partícu"
                        + "la. Lejanía solo añade desenfoque.")
                        : "Ajustes propios de la partícula " + (index + 1)
                        + ". Se combinan con los del conjunto, sin cambiar las demás.");
            } finally { loading[0] = false; }
        };
        Runnable rebuildTargets = () -> {
            int selection = Math.max(0, Math.min(target.getSelectedIndex(),
                    particles.getParticleCount()));
            loading[0] = true;
            try {
                target.removeAllItems();
                target.addItem("Todo el conjunto");
                for (int i = 1; i <= particles.getParticleCount();
                        i++) target.addItem("Partícula " + i);
                target.setSelectedIndex(selection);
            } finally { loading[0] = false; }
            loadValues.run();
            updateNote.run();
        };
        javax.swing.event.ChangeListener changeAppearance = event -> {
            if (loading[0]) return;
            var appearance = new GlowParticles.Appearance(number(size) / 100d, number(
                    intensity) / 100d, number(distance) / 100d, number(reach) / 100d);
            if (target.getSelectedIndex() == 0) particles.setAppearance(appearance);
            else particles.setParticleAppearance(target.getSelectedIndex() - 1,
                    appearance);
        };
        for (var field : new JSpinner[] { size, intensity, distance,
                reach }) field.addChangeListener(changeAppearance);
        target.addActionListener(event -> loadValues.run());
        count.addChangeListener(event -> {
            if (!loading[0]) {
                particles.setParticleCount(number(count));
                rebuildTargets.run();
            }
        });
        reset.addActionListener(event -> {
            particles.resetConfiguration();
            loading[0] = true;
            try {
                count.setValue(20);
                target.setSelectedIndex(0);
                literalTravel.setSelected(false);
                time.setValue(20d);
            }
            finally { loading[0] = false; }
            particles.setTimeSeconds(20);
            rebuildTargets.run();
        });
        setControlFonts(panel);
        title.setFont(LauncherFonts.minecraftTen(18));
        rebuildTargets.run();
        return panel;
    }

    private static int number(JSpinner field) {
        return ((Number) field.getValue()).intValue();
    }
    private static JSpinner spinner(String name, int value,
            int minimum, int maximum, int step) {
        JSpinner field = new JSpinner(new SpinnerNumberModel(value,
                minimum, maximum, step));
        field.setName(name);
        field.setPreferredSize(new Dimension(76, 28));
        field.getAccessibleContext().setAccessibleName(name);
        return field;
    }
    private static void row(JPanel panel, GridBagConstraints grid,
            int row, String label, javax.swing.JComponent field) {
        grid.gridy = row;
        grid.gridwidth = 1;
        grid.gridx = 0;
        grid.weightx = 1;
        JLabel text = new JLabel(label);
        text.setLabelFor(field);
        panel.add(text, grid);
        field.getAccessibleContext().setAccessibleName(label);
        grid.gridx = 1;
        grid.weightx = 0;
        panel.add(field, grid);
    }
    private static void setControlFonts(java.awt.Container container) {
        for (var child : container.getComponents()) {
            child.setFont(LauncherFonts.minecraftSeven(14));
            if (child instanceof java.awt.Container nested) setControlFonts(nested);
        }
    }

    static final class Profiles extends JPanel {
        Profiles() { setBackground(new Color(0x050a10)); }
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                String[] names = { "Amarilla con halo", "Naranja", "Dorada", "Difusa" };
                for (int variant = 1; variant <= 4; variant++) {
                    double x = ((variant - 1) % 2 + .5) * getWidth() / 2;
                    double y = ((variant - 1) / 2 + .5) * getHeight() / 2;
                    var definition = GlowParticleModel.DATA.variants().get(variant - 1);
                    GlowParticleSprites.paint(g, new GlowParticleModel.State(variant,
                            variant, x, y, 2.2, definition.opacity()), 1);
                }
                g.setFont(LauncherFonts.minecraftTen(15f));
                g.setColor(new Color(0xe4e1dd));
                for (int i = 0; i < names.length; i++) {
                    int x = (int)((i % 2 + .5) * getWidth() / 2)
                            - g.getFontMetrics().stringWidth(names[i]) / 2;
                    int y = (int)((i / 2 + .5) * getHeight() / 2) + 64;
                    g.drawString(names[i], x, y);
                }
            } finally { g.dispose(); }
        }
    }
}
