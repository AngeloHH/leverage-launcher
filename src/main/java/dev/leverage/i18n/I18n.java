package dev.leverage.i18n;

import dev.leverage.ui.LauncherFonts;
import javax.accessibility.AccessibleContext;
import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.*;
import java.beans.PropertyChangeListener;
import java.lang.ref.*;
import java.text.MessageFormat;
import java.util.*;
import java.util.List;

// UTF-8 labels and live Swing bindings never translate user content.
public final class I18n {
    public enum Language {
        SPANISH("es", "Español"), ENGLISH("en", "English"),
        RUSSIAN("ru", "Русский"), CHINESE("zh-CN", "简体中文");
        private final String tag, nativeName;
        Language(String tag, String nativeName) {
            this.tag = tag;
            this.nativeName = nativeName;
        }
        public String tag() { return tag; }
        public Locale locale() { return Locale.forLanguageTag(tag); }
        @Override public String toString() { return nativeName; }
        public static Language fromTag(String tag) {
            return Arrays.stream(values())
                    .filter(value -> value.tag.equalsIgnoreCase(tag))
                    .findFirst().orElse(SPANISH);
        }
    }
    private static volatile Language language = Language.SPANISH;
    private static final String BASE = "dev.leverage.i18n.messages";
    private static final ResourceBundle.Control CONTROL = ResourceBundle.Control
            .getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);
    private static final ResourceBundle FALLBACK = ResourceBundle.getBundle(BASE,
            Locale.forLanguageTag("es"), CONTROL);
    private static volatile ResourceBundle bundle = FALLBACK;
    private static final ReferenceQueue<String> QUEUE = new ReferenceQueue<>();
    private static final Map<IdentityReference, Message> MESSAGES = new HashMap<>();
    private static final List<WeakReference<Component>> ROOTS = new ArrayList<>();
    private static final String BINDINGS = "leverage.i18n.bindings",
            OBSERVED = "leverage.i18n.observed", BASE_FONT = "leverage.i18n.font";
    private static boolean refreshing, windowsObserved;
    private record Message(String key, Object[] arguments,
            java.util.function.UnaryOperator<String> transform) {
        Message(String key, Object[] arguments) {
            this(key, arguments, java.util.function.UnaryOperator.identity());
        }
        String render() {
            String pattern = bundle.containsKey(key) ? bundle.getString(key)
                    : FALLBACK.containsKey(key) ? FALLBACK.getString(key) : key;
            if (arguments.length == 0) return transform.apply(pattern);
            Object[] values = arguments.clone();
            for (int i = 0; i < values.length; i++)
                if (values[i] instanceof String text) values[i] = resolve(text);
            // Translators can write normal apostrophes; only {0}, {1}, ... are special.
            return transform.apply(new MessageFormat(
                    pattern.replace("'", "''"), locale()).format(values));
        }
    }
    private static final class IdentityReference extends WeakReference<String> {
        private final int hash;
        IdentityReference(String value, ReferenceQueue<String> queue) {
            super(value, queue);
            hash = System.identityHashCode(value);
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return this == other || other instanceof IdentityReference ref
                    && get() != null && get() == ref.get();
        }
    }
    private I18n() { }
    public static Language language() { return language; }
    public static Locale locale() { return language.locale(); }

    // Bind by identity: equal user-entered text must remain unchanged.
    public static String t(String key, Object... arguments) {
        Message message = new Message(key, arguments.clone());
        String text = new String(message.render());
        synchronized (MESSAGES) {
            drain();
            MESSAGES.put(new IdentityReference(text, QUEUE), message);
        }
        return text;
    }
    private static void drain() {
        Reference<? extends String> ref;
        while ((ref = QUEUE.poll()) != null) MESSAGES.remove(ref);
    }
    private static Message message(String value) {
        if (value == null) return null;
        synchronized (MESSAGES) {
            drain();
            return MESSAGES.get(new IdentityReference(value, null));
        }
    }
    // Resolve a catalog label retained in an enum, model or custom-painted component.
    public static String resolve(String value) {
        Message message = message(value);
        return message == null ? value : message.render();
    }
    public static String serverError(String code) {
        String key = "api." + code;
        return FALLBACK.containsKey(key) ? t(key) : t("api.default", code);
    }
    // Preserve the binding through presentation changes such as HTML wrapping.
    public static String transform(String value,
            java.util.function.UnaryOperator<String> operation) {
        Message original = message(value);
        if (original == null) return operation.apply(value);
        Message changed = new Message(original.key, original.arguments,
                text -> operation.apply(original.transform.apply(text)));
        String result = new String(changed.render());
        synchronized (MESSAGES) {
            drain();
            MESSAGES.put(new IdentityReference(result, QUEUE), changed);
        }
        return result;
    }
    public static JTextArea textArea(String text) {
        JTextArea field = new JTextArea(text);
        Message message = message(text);
        if (message != null) bindings(field).put("text", message);
        return field;
    }
    public static JTextField textField(String text) {
        JTextField field = new JTextField();
        setText(field, text);
        return field;
    }
    public static void setText(JTextComponent field, String text) {
        field.setText(text);
        Message message = message(text);
        if (message == null) bindings(field).remove("text");
        else bindings(field).put("text", message);
    }

    // Call before window creation, or on the EDT at the transition midpoint.
    public static void setLanguage(Language next) {
        Objects.requireNonNull(next);
        if (!ROOTS.isEmpty() && !SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Change the UI language on the EDT");
        language = next;
        bundle = ResourceBundle.getBundle(BASE, next.locale(), CONTROL);
        JOptionPane.setDefaultLocale(next.locale());
        JFileChooser.setDefaultLocale(next.locale());
        for (String[] item : new String[][] {
                { "OptionPane.okButtonText", "common.ok" },
                { "OptionPane.cancelButtonText", "common.cancel" },
                { "OptionPane.yesButtonText", "common.yes" },
                { "OptionPane.noButtonText", "common.no" }
        }) UIManager.put(item[0], t(item[1]));
        refreshing = true;
        try {
            // Reuse the tree to preserve live form values and selections.
            ROOTS.removeIf(ref -> ref.get() == null);
            for (var ref : List.copyOf(ROOTS)) {
                Component root = ref.get();
                if (root != null) {
                    refresh(root);
                    root.invalidate();
                    if (root instanceof JComponent panel) panel.revalidate();
                    root.repaint();
                }
            }
        } finally { refreshing = false; }
    }
    public static void attach(Component root) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException(
                "Attach Swing bindings on the EDT");
        ROOTS.removeIf(ref -> ref.get() == null);
        if (ROOTS.stream().noneMatch(ref -> ref.get() == root)) ROOTS.add(
                new WeakReference<>(root));
        observe(root);
        boolean was = refreshing;
        refreshing = true;
        try {
            refresh(root);
        } finally {
            refreshing = was;
        }
        if (!windowsObserved && !GraphicsEnvironment.isHeadless()) {
            // Owned dialogs join the tree without retaining closed windows.
            windowsObserved = true;
            Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
                if (event instanceof WindowEvent windowEvent
                        && windowEvent.getID() == WindowEvent.WINDOW_OPENED) {
                    Window window = windowEvent.getWindow();
                    for (Window owner = window.getOwner(); owner != null;
                            owner = owner.getOwner()) {
                        Window current = owner;
                        if (ROOTS.stream().anyMatch(ref -> ref.get() == current)) {
                            attach(window);
                            break;
                        }
                    }
                }
            }, AWTEvent.WINDOW_EVENT_MASK);
        }
    }
    private static void observe(Component component) {
        if (component instanceof Dialog dialog
                && component instanceof RootPaneContainer window) {
            var pane = window.getRootPane();
            if (pane.getClientProperty("leverage.i18n.window") == null) {
                pane.putClientProperty("leverage.i18n.window", true);
                pane.putClientProperty("leverage.i18n.title", message(dialog.getTitle()));
                dialog.addPropertyChangeListener("title", event -> {
                    if (!refreshing) pane.putClientProperty("leverage.i18n.title",
                            message(dialog.getTitle()));
                });
            }
        }
        if (component instanceof JComponent target
                && target.getClientProperty(OBSERVED) == null) {
            target.putClientProperty(OBSERVED, true);
            if (!bindings(target).containsKey("text")) capture(target,
                    "text", text(target));
            capture(target, "ToolTipText", target.getToolTipText());
            capture(target, "JTextField.placeholderText",
                    target.getClientProperty("JTextField.placeholderText"));
            var context = target.getAccessibleContext();
            if (context != null) {
                captureName(target, context.getAccessibleName());
                capture(target, "accessibleDescription",
                        context.getAccessibleDescription());
                context.addPropertyChangeListener(event -> {
                    if (!refreshing) {
                        if (AccessibleContext.ACCESSIBLE_NAME_PROPERTY.equals(
                                event.getPropertyName())) captureName(target,
                                event.getNewValue());
                        if (AccessibleContext.ACCESSIBLE_DESCRIPTION_PROPERTY.equals(
                                event.getPropertyName())) capture(target,
                                "accessibleDescription", event.getNewValue());
                    }
                });
            }
            PropertyChangeListener listener = event -> {
                if (refreshing) return;
                String name = event.getPropertyName();
                if (name == null) return;
                if (name.equals("text") || name.equals("ToolTipText")
                        || name.equals("JTextField.placeholderText")) {
                    capture(target, name, event.getNewValue());
                    if (name.equals("text")) updateFont(target);
                } else if (name.equals("font")
                        && event.getNewValue() instanceof Font font) {
                    target.putClientProperty(BASE_FONT, font);
                    updateFont(target);
                }
            };
            target.addPropertyChangeListener(listener);
            Font font = target.getFont();
            if (font != null) {
                target.putClientProperty(BASE_FONT, font);
                updateFont(target);
            }
        }
        if (component instanceof Container container) {
            if (!(component instanceof JComponent target)
                    || target.getClientProperty("leverage.i18n.container") == null) {
                if (component instanceof JComponent target) target.putClientProperty(
                        "leverage.i18n.container", true);
                container.addContainerListener(new ContainerAdapter() {
                    @Override public void componentAdded(ContainerEvent event) {
                        observe(event.getChild());
                        boolean was = refreshing;
                        refreshing = true;
                        try {
                            refresh(event.getChild());
                        } finally {
                            refreshing = was;
                        }
                    }
                });
            }
            for (Component child : container.getComponents()) observe(child);
        }
    }
    @SuppressWarnings("unchecked") private static Map<String,
            Message> bindings(JComponent component) {
        Object existing = component.getClientProperty(BINDINGS);
        if (existing != null) return (Map<String, Message>) existing;
        Map<String, Message> bindings = new HashMap<>();
        component.putClientProperty(BINDINGS, bindings);
        return bindings;
    }
    private static void capture(JComponent component, String property, Object value) {
        if (property.equals("text") && component instanceof JTextComponent field
                && field.isEditable()) return;
        Message message = value instanceof String text ? message(text) : null;
        if (message == null) bindings(component).remove(property);
        else bindings(component).put(property, message);
    }
    private static void captureName(JComponent component, Object value) {
        // Do not turn Swing's implicit button/label name into a frozen explicit name.
        if ((component instanceof JLabel || component instanceof AbstractButton)
                && value == text(component)) bindings(component).remove("accessibleName");
        else capture(component, "accessibleName", value);
    }
    private static String text(JComponent component) {
        if (component instanceof JLabel label) return label.getText();
        if (component instanceof AbstractButton button) return button.getText();
        if (component instanceof JTextComponent field
                && !field.isEditable()) return field.getText();
        return null;
    }
    private static void updateFont(JComponent target) {
        if (!(target.getClientProperty(BASE_FONT) instanceof Font base)) return;
        Font font = LauncherFonts.forLocale(base);
        String text = text(target);
        if (text != null) font = LauncherFonts.forText(font, resolve(text));
        if (!font.equals(target.getFont())) {
            boolean was = refreshing;
            refreshing = true;
            try {
                target.setFont(font);
            } finally {
                refreshing = was;
            }
        }
    }
    private static void refresh(Component component) {
        component.setLocale(locale());
        if (component instanceof JComponent target) {
            for (var entry : bindings(target).entrySet()) {
                Message bound = entry.getValue();
                String value = new String(bound.render());
                synchronized (MESSAGES) {
                    drain();
                    MESSAGES.put(new IdentityReference(value, QUEUE), bound);
                }
                switch (entry.getKey()) {
                    case "text" -> {
                        if (target instanceof JLabel label) label.setText(value);
                        else if (target instanceof AbstractButton button) button.setText(
                                value);
                        else if (target instanceof JTextComponent field
                                && !field.isEditable()) field.setText(value);
                    }
                    case "ToolTipText" -> target.setToolTipText(value);
                    case "JTextField.placeholderText" -> target.putClientProperty(
                            entry.getKey(), value);
                    case "accessibleName" ->
                            target.getAccessibleContext().setAccessibleName(value);
                    case "accessibleDescription" ->
                            target.getAccessibleContext().setAccessibleDescription(value);
                }
            }
            updateFont(target);
        }
        if (component instanceof Dialog dialog
                && component instanceof RootPaneContainer window
                && window.getRootPane().getClientProperty(
                "leverage.i18n.title") instanceof Message title) dialog.setTitle(
                title.render());
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) refresh(child);
            container.invalidate();
        }
    }
}
