package dev.leverage.ui;

import dev.leverage.i18n.I18n;

import static dev.leverage.i18n.I18n.t;

import dev.leverage.skin.DefaultSkin;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.AbstractAction;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Cursor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;

// The account avatar shares the player's skin and head overlay.
public final class PlayerAccountCard extends JPanel {
    static final int COMPACT_AVATAR_SIZE = 32;
    private final Avatar avatar = new Avatar();
    private final JLabel username = new JLabel("Jugador", SwingConstants.LEADING);
    private Runnable accountAction;
    private String fullName = "Jugador";
    private boolean compact;

    void setCompact(boolean value) {
        compact = value;
        revalidate();
    }

    public PlayerAccountCard() {
        setLayout(null);
        setOpaque(false);
        setPreferredSize(new Dimension(248, 42));
        username.setForeground(Color.WHITE);
        username.putClientProperty("html.disable", true);
        username.setName("account-username");
        username.setFont(LauncherFonts.minecraftTen(16f));
        username.setIconTextGap(6);
        add(avatar);
        add(username);
        setSkin(DefaultSkin.image());
        setAccount("Jugador", false);
        setFocusable(true);
        MouseAdapter listener = new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (javax.swing.SwingUtilities.isLeftMouseButton(event)) {
                    requestFocusInWindow();
                    activate();
                }
            }
        };
        addMouseListener(listener);
        for (var child : getComponents()) {
            child.addMouseListener(listener);
            if (child instanceof JComponent component)
                component.setInheritsPopupMenu(true);
        }
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "open-account");
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke("SPACE"), "open-account");
        getActionMap().put("open-account", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                activate();
            }
        });
    }

    @Override public void doLayout() {
        int size = compact ? COMPACT_AVATAR_SIZE : 38, left = compact ? 40 : 50;
        avatar.setBounds(0, (getHeight() - size) / 2, size, size);
        username.setBounds(left, compact ? 0 : (getHeight() - 24) / 2,
                Math.max(0, getWidth() - left - 4), 24);
        var metrics = username.getFontMetrics(LauncherFonts.forLocale(
                username.getFont()));
        String text = fullName;
        if (metrics.stringWidth(I18n.resolve(text)) > username.getWidth()) {
            int count = text.length();
            while (count > 0 && metrics.stringWidth(I18n.resolve(
                    text.substring(0, count) + "...")) > username.getWidth()) count--;
            text = metrics.stringWidth(I18n.resolve("...")) <= username.getWidth()
                    ? text.substring(0, count) + "..." : "";
        }
        username.setText(text);
    }

    public void setAccount(String name, boolean online) {
        String displayName = name == null || name.isBlank() ? "Jugador" : name;
        fullName = displayName;
        username.setText(displayName);
        username.setToolTipText(displayName);
        getAccessibleContext().setAccessibleName(displayName + " · " + (online
                ? t("connection.online") : t("connection.offlineShort")));
        avatar.getAccessibleContext().setAccessibleName(t("profile.avatar", displayName));
        if (getWidth() > 0) doLayout();
        repaint();
    }

    public void setAccountMenu(JPopupMenu menu) {
        setComponentPopupMenu(menu);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setToolTipText(t("settings.accountHover"));
    }

    public void setAccountAction(Runnable action) {
        accountAction = java.util.Objects.requireNonNull(action);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    private void activate() {
        if (accountAction != null) accountAction.run();
        else if (getComponentPopupMenu() != null) getComponentPopupMenu().show(this,
                0, getHeight());
    }

    public void setSkin(BufferedImage skin) {
        if (skin == null || skin.getWidth() != 64 || (skin.getHeight() != 64
                && skin.getHeight() != 32)) {
            throw new IllegalArgumentException(t("skins.dimensions"));
        }
        BufferedImage face = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
            face.setRGB(x, y, skin.getRGB(8 + x, 8 + y) | 0xff000000);
        }
        Graphics2D g = face.createGraphics();
        try { g.drawImage(skin, 0, 0, 8, 8, 40, 8, 48, 16, null); }
        finally { g.dispose(); }
        avatar.face = face;
        avatar.repaint();
    }

    private static final class Avatar extends JComponent {
        private BufferedImage face;

        Avatar() {
            // JComponent itself does not provide a concrete accessible context.
            accessibleContext = new AccessibleJComponent() { };
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setColor(new Color(0x6cc349));
                g.fillRect(0, 0, getWidth(), 2);
                g.fillRect(0, getHeight() - 2, getWidth(), 2);
                g.fillRect(0, 2, 2, getHeight() - 4);
                g.fillRect(getWidth() - 2, 2, 2, getHeight() - 4);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g.drawImage(face, 3, 3, getWidth() - 6, getHeight() - 6, null);
            } finally { g.dispose(); }
        }
    }
}
