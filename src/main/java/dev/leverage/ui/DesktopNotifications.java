package dev.leverage.ui;

import javax.swing.SwingUtilities;
import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.AWTException;
import java.awt.Component;
import java.awt.Frame;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.function.LongSupplier;

// Native desktop messages, shared by setup and home without reserving layout space.
final class DesktopNotifications implements AutoCloseable {
    interface Sink extends AutoCloseable {
        boolean show(String message, boolean error) throws AWTException;
        @Override default void close() { }
    }
    private static final System.Logger LOG = System.getLogger(
            DesktopNotifications.class.getName());
    private final Sink sink;
    private final LongSupplier clock;
    private String lastMessage;
    private boolean lastError, closed, unavailable;
    private long lastTime;

    DesktopNotifications(Component owner) {
        this(new WithFallback(new NativeTray(owner), new Toast(owner)), System::nanoTime);
    }
    DesktopNotifications(Sink sink, LongSupplier clock) {
        this.sink = sink;
        this.clock = clock;
    }

    void show(String message, boolean error) {
        if (message == null || message.isBlank()) return;
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> show(message, error));
            return;
        }
        if (closed || unavailable) return;
        long now = clock.getAsLong();
        if (message.equals(lastMessage) && error == lastError
                && now - lastTime < 3_000_000_000L) return;
        try {
            if (sink.show(message, error)) {
                lastMessage = message;
                lastError = error;
                lastTime = now;
            }
        } catch (AWTException | RuntimeException failure) {
            unavailable = true;
            LOG.log(System.Logger.Level.WARNING,
                    "Las notificaciones del sistema no están disponibles.", failure);
        }
    }

    @Override public void close() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::close);
            return;
        }
        if (closed) return;
        closed = true;
        sink.close();
    }

    // Unsupported native notifications must not silently swallow login or installation
    // errors.
    static final class WithFallback implements Sink {
        private final Sink nativeSink, fallback;
        WithFallback(Sink nativeSink, Sink fallback) {
            this.nativeSink = nativeSink;
            this.fallback = fallback;
        }
        @Override public boolean show(String message, boolean error) throws AWTException {
            try { if (nativeSink.show(message, error)) return true; }
            catch (AWTException | RuntimeException unavailable) {
            // Use a visible, non-modal notice instead.
            }
            return fallback.show(message, error);
        }
        @Override public void close() {
            try {
                nativeSink.close();
            } finally {
                fallback.close();
            }
        }
    }

    // Overlay only: never reserves space, changes the page height or blocks keyboard
    // input.
    private static final class Toast implements Sink {
        private final Component owner;
        private JWindow popup;
        private Timer dismiss;
        Toast(Component owner) { this.owner = owner; }
        @Override public boolean show(String message, boolean error) {
            Window window = SwingUtilities.getWindowAncestor(owner);
            if (!owner.isShowing() || window == null) return false;
            close();
            int width = Math.max(220, Math.min(410, window.getWidth() - 36));
            Color background = new Color(0x171615), foreground = new Color(0xFAF4EF);
            JPanel panel = new JPanel(new BorderLayout(0, 10));
            panel.setBackground(background);
            panel.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(3, 3, 3, 3,
                    error ? new Color(0xD4441A) : new Color(0x6CC349)),
                    BorderFactory.createEmptyBorder(12, 14, 12, 14)));
            JLabel title = new JLabel("Leverage");
            title.setForeground(foreground);
            title.setFont(LauncherFonts.minecraftTen(16));
            panel.add(title, BorderLayout.NORTH);
            JTextArea text = new JTextArea(message);
            text.setEditable(false);
            text.setFocusable(false);
            text.setLineWrap(true);
            text.setWrapStyleWord(true);
            text.setFont(LauncherFonts.minecraftSeven(14));
            text.setForeground(foreground);
            text.setBackground(background);
            text.setSize(width - 36, Short.MAX_VALUE);
            panel.add(text, BorderLayout.CENTER);
            popup = new JWindow(window);
            popup.setFocusableWindowState(false);
            popup.setContentPane(panel);
            popup.setSize(width, Math.min(Math.max(88, text.getPreferredSize().height
                    + 68), Math.max(100, window.getHeight() - 70)));
            popup.setLocation(window.getX() + window.getWidth() - width - 18,
                    window.getY() + Math.max(20, window.getInsets().top) + 18);
            popup.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mousePressed(java.awt.event.MouseEvent event) {
                    close();
                }
            });
            popup.setVisible(true);
            dismiss = new Timer(error ? 12000 : 7000, event -> close());
            dismiss.setRepeats(false);
            dismiss.start();
            return true;
        }
        @Override public void close() {
            if (dismiss != null) {
                dismiss.stop();
                dismiss = null;
            }
            if (popup != null) {
                popup.dispose();
                popup = null;
            }
        }
    }

    private static final class NativeTray implements Sink {
        private final Component owner;
        private TrayIcon icon;
        private SystemTray tray;
        NativeTray(Component owner) { this.owner = owner; }
        @Override public boolean show(String message, boolean error) throws AWTException {
            // Invisible/headless panels must not send desktop notifications.
            Window window = SwingUtilities.getWindowAncestor(owner);
            if (!owner.isShowing() || window == null
                    || !SystemTray.isSupported()) return false;
            if (icon == null) {
                tray = SystemTray.getSystemTray();
                TrayIcon created = new TrayIcon(LauncherIcon.images().get(2), "Leverage");
                created.setImageAutoSize(true);
                created.addActionListener(event -> {
                    if (window instanceof Frame frame) frame.setExtendedState(
                            frame.getExtendedState() & ~Frame.ICONIFIED);
                    window.toFront();
                    window.requestFocus();
                });
                tray.add(created);
                icon = created;
                window.addWindowListener(new WindowAdapter() {
                    @Override public void windowClosed(WindowEvent event) {
                        close();
                    }
                });
            }
            icon.displayMessage("Leverage", message, error
                    ? TrayIcon.MessageType.ERROR : TrayIcon.MessageType.INFO);
            return true;
        }
        @Override public void close() {
            if (icon != null) {
                tray.remove(icon);
                icon = null;
            }
        }
    }
}
