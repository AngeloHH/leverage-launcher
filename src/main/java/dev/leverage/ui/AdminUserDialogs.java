package dev.leverage.ui;

import dev.leverage.i18n.I18n;
import static dev.leverage.i18n.I18n.t;

import dev.leverage.client.LauncherUsersClient;
import javax.swing.*;
import java.awt.*;
import java.util.Arrays;
import java.util.Objects;

// Explicit, launcher-styled confirmation forms; no account action runs from merely
// opening one.
final class AdminUserDialogs {
    private AdminUserDialogs() { }

    record Edit(UsersPanel.Action action, String value, char[] password,
            java.time.Instant expiresAt) implements AutoCloseable {
        Edit(UsersPanel.Action action, String value, char[] password) {
            this(action, value, password, null);
        }
        Edit {
            Objects.requireNonNull(action);
            Objects.requireNonNull(value);
            Objects.requireNonNull(password);
        }
        @Override public void close() { Arrays.fill(password, '\0'); }
    }

    static Edit show(Component owner, UsersPanel.Action action,
            LauncherUsersClient.User user) {
        ConfirmationPanel content = new ConfirmationPanel(action, user);
        Editor editor = content.editor;
        Window window = SwingUtilities.getWindowAncestor(owner);
        JDialog dialog = new JDialog(window, title(action),
                Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        PixelButton cancel = content.cancel, confirm = content.confirm;
        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(confirm);
        dialog.getRootPane().registerKeyboardAction(event -> dialog.dispose(),
                KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        Edit[] answer = new Edit[1];
        cancel.addActionListener(event -> dialog.dispose());
        confirm.addActionListener(event -> {
            try {
                answer[0] = editor.validatedEdit();
                dialog.dispose();
            }
            catch (IllegalArgumentException failure) {
                editor.showError(failure.getMessage());
            }
        });
        dialog.pack();
        dialog.setResizable(false);
        dialog.setLocationRelativeTo(owner);
        SwingUtilities.invokeLater(editor::focusInput);
        try {
            dialog.setVisible(true);
            return answer[0];
        }
        finally {
            editor.clearSecrets();
            dialog.dispose();
        }
    }

    static String tooltip(String text) {
        return I18n.transform(text == null ? "" : text,
                value -> "<html><div style='width:320px'>" + value.replace("&",
                "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"",
                "&quot;").replace("'", "&#39;").replace("\n", "<br>") + "</div></html>");
    }

    private static boolean destructive(UsersPanel.Action action) {
        return action == UsersPanel.Action.BAN || action == UsersPanel.Action.CLEAR_SKIN;
    }
    private static String title(UsersPanel.Action action) {
        return switch (action) {
            case BAN -> t("users.banTitle");
            case CLEAR_SKIN -> t("users.clearSkin");
            case PASSWORD -> t("profile.changePassword");
            case EMAIL -> t("profile.changeEmail");
        };
    }
    private static String confirmText(UsersPanel.Action action) {
        return switch (action) {
            case BAN -> t("users.banConfirm");
            case CLEAR_SKIN -> t("users.clearSkin");
            default -> t("users.saveChange");
        };
    }

    static final class ConfirmationPanel extends JPanel {
        final Editor editor;
        final PixelButton cancel = new PixelButton(t("common.cancel"),
                PixelButton.Variant.DARK);
        final PixelButton confirm;
        ConfirmationPanel(UsersPanel.Action action, LauncherUsersClient.User user) {
            super(new BorderLayout(0, 22));
            setName("admin-user-confirmation");
            setBackground(JourneyStyle.INK);
            setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
            editor = new Editor(action, user);
            add(editor, BorderLayout.CENTER);
            JPanel actions = new JPanel(new GridLayout(1, 2, 12, 0));
            actions.setOpaque(false);
            cancel.setName("admin-user-cancel");
            confirm = new PixelButton(confirmText(action), destructive(action)
                    ? PixelButton.Variant.DANGER : PixelButton.Variant.PRIMARY);
            confirm.setName("admin-user-confirm");
            actions.add(cancel);
            actions.add(confirm);
            add(actions, BorderLayout.SOUTH);
        }
    }

    // Separate from the window so validation, masking and real rendering can be tested
    // headlessly.
    static final class Editor extends JPanel {
        private final UsersPanel.Action action;
        private final JTextField value = PixelInputStyle.apply(new JTextField());
        private final JTextField expires = PixelInputStyle.apply(new JTextField());
        private final JPasswordField password
                = PixelInputStyle.apply(new JPasswordField());
        private final JPasswordField repeat = PixelInputStyle.apply(new JPasswordField());
        private final JLabel error = JourneyStyle.label(" ",
                12, new Color(0xFF9B87), false);

        Editor(UsersPanel.Action action, LauncherUsersClient.User user) {
            this.action = Objects.requireNonNull(action);
            setName("admin-user-editor-" + action.name().toLowerCase(
                    java.util.Locale.ROOT));
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBackground(JourneyStyle.INK);
            setBorder(BorderFactory.createEmptyBorder());
            JLabel heading = JourneyStyle.label(title(action),
                    25, JourneyStyle.PAPER, true);
            heading.setAlignmentX(LEFT_ALIGNMENT);
            add(heading);
            add(Box.createVerticalStrut(10));
            JLabel account = JourneyStyle.label(user.username(),
                    16, JourneyStyle.GREEN, false);
            account.setName("admin-user-target");
            account.setToolTipText(tooltip(user.username()));
            account.setAlignmentX(LEFT_ALIGNMENT);
            add(account);
            add(Box.createVerticalStrut(14));
            String explanation = switch (action) {
                case BAN -> t("users.banDescription");
                case CLEAR_SKIN -> t("users.clearSkinDescription");
                case PASSWORD -> t("users.passwordDescription");
                case EMAIL -> t("users.emailDescription");
            };
            JTextArea help = new JTextArea(explanation);
            help.setName("admin-user-explanation");
            help.setFont(LauncherFonts.minecraftSeven(13));
            help.setForeground(new Color(0xB9B5B3));
            help.setOpaque(false);
            help.setLineWrap(true);
            help.setWrapStyleWord(true);
            help.setEditable(false);
            help.setFocusable(false);
            help.setAlignmentX(LEFT_ALIGNMENT);
            help.setPreferredSize(new Dimension(436, 60));
            help.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));
            add(help);
            add(Box.createVerticalStrut(18));
            value.setName(action == UsersPanel.Action.BAN
                    ? "admin-user-ban-reason" : "admin-user-email");
            password.setName("admin-user-password");
            repeat.setName("admin-user-repeat-password");
            if (action == UsersPanel.Action.BAN) {
                addField(t("users.banReason"), value);
                add(Box.createVerticalStrut(12));
                expires.setName("admin-user-ban-until");
                addField(t("users.banEnd"), expires);
                add(Box.createVerticalStrut(5));
                JLabel zone = JourneyStyle.label(t("users.banZone",
                        java.time.ZoneId.systemDefault().toString()),
                        11, new Color(0xB9B5B3), false);
                zone.setAlignmentX(LEFT_ALIGNMENT);
                add(zone);
            }
            if (action == UsersPanel.Action.EMAIL) {
                value.setText(user.email());
                addField(t("users.newEmail"), value);
            }
            if (action == UsersPanel.Action.PASSWORD) {
                addField(t("users.newPassword"), password);
                add(Box.createVerticalStrut(14));
                addField(t("auth.repeatPasswordLabel"), repeat);
            }
            error.setName("admin-user-validation");
            error.setAlignmentX(LEFT_ALIGNMENT);
            error.setPreferredSize(new Dimension(436, 36));
            error.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
            add(Box.createVerticalStrut(10));
            add(error);
        }

        private void addField(String text, JTextField field) {
            JLabel label = JourneyStyle.label(text, 13, JourneyStyle.PAPER, false);
            label.setLabelFor(field);
            label.setAlignmentX(LEFT_ALIGNMENT);
            field.setAlignmentX(LEFT_ALIGNMENT);
            field.getAccessibleContext().setAccessibleName(text);
            add(label);
            add(Box.createVerticalStrut(8));
            add(field);
        }

        @Override public Dimension getPreferredSize() {
            Dimension natural = super.getPreferredSize();
            return new Dimension(436, natural.height);
        }
        void focusInput() {
            if (action == UsersPanel.Action.PASSWORD) password.requestFocusInWindow();
            else if (action != UsersPanel.Action.CLEAR_SKIN) value.requestFocusInWindow();
        }
        void clearSecrets() {
            password.setText("");
            repeat.setText("");
        }
        void showError(String message) {
            error.setText(message);
            error.setToolTipText(tooltip(message));
        }

        Edit validatedEdit() {
            if (action == UsersPanel.Action.CLEAR_SKIN)
                    return new Edit(action, "", new char[0]);
            String clean = value.getText().trim();
            if (action == UsersPanel.Action.BAN) {
                if (clean.isBlank() || clean.codePointCount(0, clean.length()) > 1_000 ||
                        clean.codePoints().anyMatch(character -> Character.isISOControl(
                        character) && character != '\n' && character != '\t'))
                        throw new IllegalArgumentException(t("users.invalidReason"));
                java.time.Instant end = null;
                if (!expires.getText().isBlank()) {
                    try {
                        var format = java.time.format.DateTimeFormatter.ofPattern(
                                "dd/MM/uuuu HH:mm").withResolverStyle(
                                java.time.format.ResolverStyle.STRICT);
                        var local = java.time.LocalDateTime.parse(
                                expires.getText().trim(), format);
                        var zone = java.time.ZoneId.systemDefault();
                        var offsets = zone.getRules().getValidOffsets(local);
                        if (offsets.size() != 1) throw new IllegalArgumentException(
                                t("users.ambiguousDate"));
                        end = local.toInstant(offsets.getFirst());
                    } catch (java.time.DateTimeException invalid) {
                        throw new IllegalArgumentException(t("users.invalidDate"));
                    }
                    if (!end.isAfter(java.time.Instant.now()))
                            throw new IllegalArgumentException(t("users.futureDate"));
                }
                return new Edit(action, clean, new char[0], end);
            }
            if (action == UsersPanel.Action.EMAIL) {
                if (clean.length() > 254 || !clean.matches(
                        "[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
                        throw new IllegalArgumentException(t("error.emailValid"));
                return new Edit(action, clean, new char[0]);
            }
            char[] next = password.getPassword(), again = repeat.getPassword();
            boolean handedOff = false;
            try {
                int length = Character.codePointCount(next, 0, next.length);
                boolean blank = true;
                for (char character : next) if (!Character.isWhitespace(character)) {
                    blank = false;
                    break;
                }
                if (blank || length < 8 || length > 128)
                        throw new IllegalArgumentException(t("error.passwordLengthAlt"));
                if (!Arrays.equals(next, again)) throw new IllegalArgumentException(
                        t("error.passwordMismatch"));
                clearSecrets();
                handedOff = true;
                return new Edit(action, "", next);
            } finally {
                Arrays.fill(again, '\0');
                if (!handedOff) Arrays.fill(next, '\0');
            }
        }
    }
}
