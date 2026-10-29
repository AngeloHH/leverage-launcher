package dev.leverage.ui;

import dev.leverage.i18n.I18n;
import static dev.leverage.i18n.I18n.t;

import dev.leverage.client.LauncherAuthClient.AccountProfile;
import javax.swing.*;
import java.awt.Color;
import java.awt.Dimension;
import java.util.Arrays;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

// Server-backed account editing; secrets exist only in masked fields and the active
// request.
public final class ProfileSettingsPanel extends JPanel {
    enum Kind { USERNAME, EMAIL, PASSWORD }
    record Edit(Kind kind, String value, char[] currentPassword, char[] newPassword) {
        void clear() {
            Arrays.fill(currentPassword, '\0');
            Arrays.fill(newPassword, '\0');
        }
    }
    private final JLabel state = label(t("profile.signIn"));
    private final JTextField username = field(new JTextField(), "profile-username");
    private final JTextField email = field(new JTextField(), "profile-email");
    private final JPasswordField current = field(new JPasswordField(),
            "profile-current-password");
    private final JPasswordField password = field(new JPasswordField(),
            "profile-new-password");
    private final JPasswordField repeat = field(new JPasswordField(),
            "profile-repeat-password");
    private final PixelButton saveName = button(t("profile.saveName"),
            "profile-save-username");
    private final PixelButton saveEmail = button(t("profile.changeEmail"),
            "profile-save-email");
    private final PixelButton savePassword = button(t("profile.changePassword"),
            "profile-save-password");
    private final PixelButton refresh = button(t("profile.refresh"), "profile-refresh");
    private final JPanel passwordFields = new JPanel();
    private boolean online, loaded, busy;
    private Consumer<Edit> edit = value -> value.clear();
    private BiConsumer<String, Boolean> notice = (text, error) -> { };

    public ProfileSettingsPanel() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
        setAlignmentX(LEFT_ALIGNMENT);
        setName("profile-settings");
        state.setName("profile-state");
        state.setFont(LauncherFonts.minecraftSeven(12f));
        state.setForeground(new Color(0xA7A09A));
        state.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        add(state);
        add(Box.createVerticalStrut(12));
        addField(t("auth.username"), username);
        add(saveName);
        add(new PixelSeparator());
        addField(t("auth.email"), email);
        add(saveEmail);
        var hint = I18n.textArea(t("profile.emailHint"));
        hint.setFont(LauncherFonts.minecraftSeven(12f));
        hint.setForeground(new Color(0xA7A09A));
        hint.setOpaque(false);
        hint.setEditable(false);
        hint.setFocusable(false);
        hint.setLineWrap(true);
        hint.setWrapStyleWord(true);
        hint.setAlignmentX(LEFT_ALIGNMENT);
        hint.setPreferredSize(new Dimension(0, 42));
        hint.setMinimumSize(new Dimension(0, 42));
        hint.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        add(Box.createVerticalStrut(10));
        add(hint);
        add(Box.createVerticalStrut(12));
        addField(t("profile.currentPassword"), current);
        var confirm = label(t("profile.confirmHint"));
        confirm.setFont(LauncherFonts.minecraftSeven(12f));
        confirm.setForeground(new Color(0xA7A09A));
        add(confirm);
        add(Box.createVerticalStrut(14));
        add(refresh);
        add(new PixelSeparator());
        var expand = new JButton(t("profile.passwordOpen"));
        expand.setName("profile-password-toggle");
        expand.setFont(LauncherFonts.minecraftSeven(14f));
        expand.setForeground(new Color(0x86D562));
        expand.setContentAreaFilled(false);
        expand.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));
        expand.setAlignmentX(LEFT_ALIGNMENT);
        expand.setCursor(java.awt.Cursor.getPredefinedCursor(
                java.awt.Cursor.HAND_CURSOR));
        add(expand);
        passwordFields.setLayout(new BoxLayout(passwordFields, BoxLayout.Y_AXIS));
        passwordFields.setOpaque(false);
        passwordFields.setAlignmentX(LEFT_ALIGNMENT);
        passwordFields.setName("profile-password-fields");
        passwordFields.setVisible(false);
        add(passwordFields);
        addField(passwordFields, t("users.newPassword"), password);
        addField(passwordFields, t("auth.repeatPasswordLabel"), repeat);
        passwordFields.add(savePassword);
        expand.addActionListener(event -> {
            boolean visible = !passwordFields.isVisible();
            passwordFields.setVisible(visible);
            expand.setText(visible ? t("profile.passwordClose")
                    : t("profile.passwordOpen"));
            if (!visible) {
                password.setText("");
                repeat.setText("");
            }
            revalidate();
            repaint();
        });
        saveName.addActionListener(event -> submit(Kind.USERNAME));
        saveEmail.addActionListener(event -> submit(Kind.EMAIL));
        savePassword.addActionListener(event -> submit(Kind.PASSWORD));
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED)
                    != 0 && !isShowing()) clearSecrets();
        });
        updateEnabled();
    }

    void onEdit(Consumer<Edit> handler) { edit = handler; }
    public void onNotice(BiConsumer<String, Boolean> handler) { notice = handler; }
    public void onRefresh(Runnable handler) {
        refresh.addActionListener(event -> handler.run());
    }

    public void setIdentity(String name, boolean connected) {
        online = connected;
        loaded = false;
        busy = false;
        username.setText(connected ? name : "");
        email.setText("");
        clearSecrets();
        state.setText(connected ? t("profile.loadHint") : t("profile.signIn"));
        updateEnabled();
    }
    public void load(AccountProfile profile) {
        username.setText(profile.username());
        email.setText(profile.email());
        online = true;
        loaded = true;
        busy = false;
        state.setText(t("profile.connected"));
        clearSecrets();
        updateEnabled();
    }
    public void setBusy(boolean value) {
        busy = value;
        updateEnabled();
    }
    public void clearSecrets() {
        current.setText("");
        password.setText("");
        repeat.setText("");
    }

    private void updateEnabled() {
        boolean enabled = online && loaded && !busy;
        for (JTextField field : new JTextField[] { username, email, current,
                password, repeat }) field.setEnabled(enabled);
        for (PixelButton button : new PixelButton[] { saveName, saveEmail,
                savePassword }) button.setEnabled(enabled);
        refresh.setEnabled(online && !busy);
    }

    private void submit(Kind kind) {
        if (!online || !loaded || busy) return;
        char[] old = current.getPassword(), next = password.getPassword(),
                again = repeat.getPassword();
        boolean handedOff = false;
        try {
            if (old.length == 0) throw new IllegalArgumentException(
                    t("profile.enterCurrentPassword"));
            String value = kind == Kind.USERNAME ? username.getText().trim()
                    : kind == Kind.EMAIL ? email.getText().trim() : "";
            if (kind == Kind.USERNAME && !value.matches("[A-Za-z0-9_]{3,16}"))
                    throw new IllegalArgumentException(t("error.usernameFormat"));
            if (kind == Kind.EMAIL && (value.length() > 254
                    || !value.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")))
                    throw new IllegalArgumentException(t("error.emailValidAlt"));
            if (kind == Kind.PASSWORD) {
                int length = Character.codePointCount(next, 0, next.length);
                if (length < 8 || length > 128) throw new IllegalArgumentException(
                        t("error.passwordLengthAlt"));
                if (!Arrays.equals(next, again)) throw new IllegalArgumentException(
                        t("error.passwordMismatch"));
            } else {
                Arrays.fill(next, '\0');
                next = new char[0];
            }
            clearSecrets();
            edit.accept(new Edit(kind, value, old, next));
            handedOff = true;
        } catch (IllegalArgumentException failure) {
            notice.accept(failure.getMessage(), true);
        }
        finally {
            Arrays.fill(again, '\0');
            if (!handedOff) {
                Arrays.fill(old, '\0');
                Arrays.fill(next, '\0');
            }
        }
    }

    private void addField(String title, JTextField field) {
        addField(this, title, field);
    }
    private static void addField(JPanel target, String title, JTextField field) {
        target.add(label(title));
        target.add(Box.createVerticalStrut(6));
        target.add(field);
        target.add(Box.createVerticalStrut(10));
    }
    private static JLabel label(String text) {
        var label = new JLabel(text);
        label.putClientProperty("html.disable", true);
        label.setFont(LauncherFonts.minecraftSeven(14f));
        label.setForeground(new Color(0xF1EDEA));
        label.setAlignmentX(LEFT_ALIGNMENT);
        return label;
    }
    private static PixelButton button(String title, String name) {
        var button = new PixelButton(title, "profile-refresh".equals(name)
                ? PixelButton.Variant.DARK : PixelButton.Variant.PRIMARY);
        button.setName(name);
        button.setFont(LauncherFonts.minecraftTen(14f));
        button.setAlignmentX(LEFT_ALIGNMENT);
        button.setMaximumSize(new Dimension(230, 40));
        button.setPreferredSize(new Dimension(230, 40));
        return button;
    }
    private static<T extends JTextField> T field(T field, String name) {
        field.setName(name);
        PixelInputStyle.apply(field);
        field.setPreferredSize(new Dimension(0, 48));
        field.setMinimumSize(new Dimension(100, 48));
        field.setAlignmentX(LEFT_ALIGNMENT);
        return field;
    }
}
