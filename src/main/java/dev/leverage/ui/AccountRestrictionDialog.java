package dev.leverage.ui;
import static dev.leverage.i18n.I18n.t;

import javax.swing.*;
import java.awt.*;

// Account restriction notice using the launcher's native pixel controls.
final class AccountRestrictionDialog {
    private AccountRestrictionDialog() { }

    static void show(Component owner, boolean banned, String message) {
        if (GraphicsEnvironment.isHeadless()
                || SwingUtilities.getWindowAncestor(owner) == null) return;
        String title = banned ? t("restriction.banned") : t("restriction.suspended");
        var dialog = new JDialog(SwingUtilities.getWindowAncestor(owner),
                "Leverage · " + title, Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setName("account-restriction-dialog");
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        var panel = new JPanel(new BorderLayout(0, 20));
        panel.setBackground(JourneyStyle.INK);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(4, 0, 0, 0, new Color(0xD4441A)),
                BorderFactory.createEmptyBorder(24, 24, 24, 24)));
        panel.add(JourneyStyle.label(title, 26, JourneyStyle.PAPER,
                true), BorderLayout.NORTH);
        var text = new JTextArea(message + t("restriction.backLogin"));
        text.setName("account-restriction-message");
        text.setFont(LauncherFonts.minecraftSeven(14));
        text.setForeground(JourneyStyle.PAPER);
        text.setBackground(JourneyStyle.INK);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setEditable(false);
        text.setCaretPosition(0);
        text.setSize(448, Short.MAX_VALUE);
        int height = Math.min(280, Math.max(100, text.getPreferredSize().height + 8));
        var scroll = JourneyStyle.scroll(text, JourneyStyle.INK);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setPreferredSize(new Dimension(448, height));
        panel.add(scroll, BorderLayout.CENTER);
        var accept = new PixelButton(t("common.ok"), PixelButton.Variant.PRIMARY);
        accept.setName("account-restriction-accept");
        accept.setFont(LauncherFonts.minecraftTen(14));
        accept.setPreferredSize(new Dimension(160, 48));
        accept.addActionListener(event -> dialog.dispose());
        var actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        actions.setOpaque(false);
        actions.add(accept);
        panel.add(actions, BorderLayout.SOUTH);
        dialog.setContentPane(panel);
        dialog.getRootPane().setDefaultButton(accept);
        dialog.getRootPane().registerKeyboardAction(event -> dialog.dispose(),
                KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.pack();
        dialog.setResizable(false);
        dialog.setLocationRelativeTo(owner);
        try {
            dialog.setVisible(true);
        } finally {
            dialog.dispose();
        }
    }
}
