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
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

// Independent particle field, displayed with the same review workflow as the orange
// overlay.
public final class PurpleParticlesGallery extends JPanel {
    public PurpleParticlesGallery() {
        super(new BorderLayout());
        setName("purple-particles-gallery");
        PurpleParticles particles = new PurpleParticles();
        particles.setName("purple-particles-overlay");
        particles.setTimeSeconds(20);
        JPanel stage = new JPanel(new BorderLayout());
        stage.setBackground(new Color(0x171615));
        stage.add(particles, BorderLayout.CENTER);
        JPanel cards = new JPanel(new CardLayout());
        cards.add(stage, "field");
        cards.add(new Detail(), "detail");
        add(cards, BorderLayout.CENTER);
        JLabel note = new JLabel();
        note.setFont(LauncherFonts.minecraftSeven(13));
        note.setBorder(BorderFactory.createEmptyBorder(8, 12, 10, 12));
        Runnable updateNote = () -> note.setText(particles.getParticleCount()
                + " partículas libres · misma animación que las regulares · "
                + (particles.getTravelDistance() == 0
                ? "recorrido adaptado" : "recorrido: 5202 px"));
                updateNote.run();
                add(note, BorderLayout.SOUTH);
        JPanel playback = new JPanel(new FlowLayout(FlowLayout.LEADING, 12, 9));
        JComboBox<String> view = new JComboBox<>(new String[] { "Animación",
                "Detalle ampliado" });
        view.setName("purple-view");
        view.getAccessibleContext().setAccessibleName("Vista de partículas moradas");
        JCheckBox animate = new JCheckBox("Animar", true);
        animate.setName("purple-animate");
        animate.addActionListener(event -> particles.setAnimationEnabled(
                animate.isSelected()));
        JSpinner time = new JSpinner(new SpinnerNumberModel(20d, 0d, 3600d, .1));
        time.setName("purple-time");
        time.getAccessibleContext().setAccessibleName(
                "Ir al segundo de la animación morada");
        time.addChangeListener(event -> particles.setTimeSeconds(((
                Number) time.getValue()).doubleValue()));
        JCheckBox literal = new JCheckBox("Recorrido original (5202 px)");
        literal.setName("purple-original-travel");
        literal.addActionListener(event -> {
            particles.setTravelDistance(literal.isSelected() ? 5202 : 0);
            updateNote.run();
        });
        playback.add(view);
        playback.add(animate);
        playback.add(new JLabel("Ir a (s)"));
        playback.add(time);
        playback.add(literal);
        fonts(playback);
        add(playback, BorderLayout.NORTH);
        JPanel inspector = inspector(particles, time, literal, updateNote);
        add(inspector, BorderLayout.EAST);
        view.addActionListener(event -> {
            ((CardLayout) cards.getLayout()).show(cards,
                    view.getSelectedIndex() == 0 ? "field" : "detail");
            inspector.setVisible(view.getSelectedIndex() == 0);
            revalidate();
            repaint();
        });
    }

    private JPanel inspector(PurpleParticles particles, JSpinner time,
            JCheckBox literal, Runnable updateNote) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setPreferredSize(new Dimension(270, 0));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 1, 0, 0, new Color(0x454548)),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));
        GridBagConstraints grid = new GridBagConstraints();
        grid.gridx = 0;
        grid.gridy = 0;
        grid.gridwidth = 2;
        grid.fill = GridBagConstraints.HORIZONTAL;
        grid.weightx = 1;
        grid.insets = new Insets(2, 0, 2, 0);
        JLabel title = new JLabel("Partículas libres");
        panel.add(title, grid);
        JSpinner count = spinner("purple-count", 20, 0, PurpleParticles.MAX_PARTICLES, 1);
        JComboBox<String> target = new JComboBox<>();
        target.setName("purple-target");
        target.setMaximumRowCount(10);
        target.getAccessibleContext().setAccessibleName(
                "Editar el conjunto o una partícula morada");
        JSpinner size = spinner("purple-size", 100, 25, 400, 25),
                intensity = spinner("purple-intensity", 100, 0, 100, 5);
        JSpinner distance = spinner("purple-distance", 0, 0, 100, 5),
                reach = spinner("purple-reach", 100, 0, 300, 25);
        JSpinner spread = spinner("purple-spread", 40, 0, 96, 2),
                speed = spinner("purple-speed", 100, 10, 400, 10);
        row(panel, grid, 1, "Cantidad", count);
        grid.gridx = 0;
        grid.gridy = 2;
        grid.gridwidth = 2;
        panel.add(new JLabel("Editar"), grid);
        grid.gridy = 3;
        panel.add(target, grid);
        row(panel, grid, 4, "Tamaño (%)", size);
        row(panel, grid, 5, "Intensidad (%)", intensity);
        row(panel, grid, 6, "Lejanía (%)", distance);
        row(panel, grid, 7, "Alcance (%)", reach);
        row(panel, grid, 8, "Oscilación (px)", spread);
        row(panel, grid, 9, "Velocidad (%)", speed);
        JButton reset = new JButton("Restaurar valores");
        reset.setName("purple-reset");
        grid.gridx = 0;
        grid.gridy = 10;
        grid.gridwidth = 2;
        grid.insets = new Insets(10, 0, 4, 0);
        panel.add(reset, grid);
        JTextArea help = new JTextArea(
                "Misma animación que las regulares: ascenso, oscilación, pulso y desv"
                + "anecimiento. El aspecto morado se conserva.");
        help.setEditable(false);
        help.setFocusable(false);
        help.setOpaque(false);
        help.setLineWrap(true);
        help.setWrapStyleWord(true);
        help.setRows(4);
        grid.gridy = 11;
        grid.insets = new Insets(5, 0, 2, 0);
        panel.add(help, grid);
        grid.gridy = 12;
        grid.weighty = 1;
        panel.add(javax.swing.Box.createVerticalStrut(1), grid);
        size.setToolTipText(
                "Tamaño del cuerpo cuadrado, con el borde suave de la referencia.");
        distance.setToolTipText(
                "Desenfoque adicional, sin cambiar el tamaño configurado.");
        reach.setToolTipText("Extensión del resplandor compacto; no agranda el centro.");
        spread.setToolTipText(
                "Amplitud lateral; 40 px reproduce la oscilación de las regulares. No"
                + " limita el ascenso.");
        boolean[] loading = { false };
        Runnable load = () -> {
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
            } finally { loading[0] = false; }
        };
        Runnable rebuild = () -> {
            int selected = Math.max(0, Math.min(target.getSelectedIndex(),
                    particles.getParticleCount()));
            loading[0] = true;
            try {
                target.removeAllItems();
                target.addItem("Todo el conjunto");
                for (int i = 1; i <= particles.getParticleCount();
                        i++) target.addItem("Partícula " + i);
                target.setSelectedIndex(selected);
            } finally { loading[0] = false; }
            load.run();
            updateNote.run();
        };
        javax.swing.event.ChangeListener appearanceChange = event -> {
            if (loading[0]) return;
            var appearance = new PurpleParticles.Appearance(value(size) / 100d,
                    value(intensity) / 100d, value(distance) / 100d, value(reach) / 100d);
            if (target.getSelectedIndex() == 0) particles.setAppearance(appearance);
            else particles.setParticleAppearance(target.getSelectedIndex() - 1,
                    appearance);
        };
        for (var field : new JSpinner[] { size, intensity, distance,
                reach }) field.addChangeListener(appearanceChange);
        target.addActionListener(event -> load.run());
        count.addChangeListener(event -> {
            if (!loading[0]) {
                particles.setParticleCount(value(count));
                rebuild.run();
            }
        });
        spread.addChangeListener(event -> {
            if (!loading[0]) particles.setSpread(value(spread));
        });
        speed.addChangeListener(event -> {
            if (!loading[0]) particles.setSpeed(value(speed) / 100d);
        });
        reset.addActionListener(event -> {
            particles.resetConfiguration();
            loading[0] = true;
            try {
                count.setValue(20);
                target.setSelectedIndex(0);
                spread.setValue(40);
                speed.setValue(100);
                literal.setSelected(false);
                time.setValue(20d);
            }
            finally { loading[0] = false; }
            particles.setTimeSeconds(20);
            rebuild.run();
        });
        fonts(panel);
        title.setFont(LauncherFonts.minecraftTen(18));
        rebuild.run();
        return panel;
    }

    static final class Detail extends JPanel {
        Detail() { setBackground(new Color(0x171615)); }
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                String[] names = { "Rosa", "Lila", "Rosa violeta" };
                g.setColor(new Color(0xeee5ef));
                g.setFont(LauncherFonts.minecraftTen(20));
                g.drawString("Perfil de borde suave · ampliado ×4", 24, 42);
                for (int i = 0; i < 3; i++) {
                    double x = (i + .5) * getWidth() / 3;
                    PurpleParticleSprites.paint(g, new PurpleParticleModel.State(i + 1,
                            x, getHeight() * .47, 24, .8, i),
                            PurpleParticles.Appearance.DEFAULT,
                            PurpleParticles.Appearance.DEFAULT);
                    g.setColor(new Color(0xeee5ef));
                    g.setFont(LauncherFonts.minecraftSeven(16));
                    g.drawString(names[i], (int) x - g.getFontMetrics().stringWidth(
                            names[i]) / 2, (int)(getHeight() * .47) + 96);
                }
                g.setFont(LauncherFonts.minecraftSeven(14));
                g.drawString(
                        "Mismo perfil del campo de partículas. No hay botón ni superf"
                        + "icie dibujada.", 24, getHeight() - 28);
            } finally { g.dispose(); }
        }
    }
    private static int value(JSpinner field) {
        return ((Number) field.getValue()).intValue();
    }
    private static JSpinner spinner(String name, int value, int min, int max, int step) {
        JSpinner field = new JSpinner(new SpinnerNumberModel(value, min, max, step));
        field.setName(name);
        field.setPreferredSize(new Dimension(76, 26));
        return field;
    }
    private static void row(JPanel panel, GridBagConstraints grid,
            int row, String label, JSpinner field) {
        grid.gridy = row;
        grid.gridx = 0;
        grid.gridwidth = 1;
        grid.weightx = 1;
        JLabel text = new JLabel(label);
        text.setLabelFor(field);
        panel.add(text, grid);
        field.getAccessibleContext().setAccessibleName(label);
        grid.gridx = 1;
        grid.weightx = 0;
        panel.add(field, grid);
    }
    private static void fonts(java.awt.Container root) {
        for (var child : root.getComponents()) {
            child.setFont(LauncherFonts.minecraftSeven(14));
            if (child instanceof java.awt.Container nested) fonts(nested);
        }
    }
}
