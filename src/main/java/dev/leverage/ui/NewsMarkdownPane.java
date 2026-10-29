package dev.leverage.ui;
import static dev.leverage.i18n.I18n.t;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;
import javax.swing.undo.*;
import java.awt.*;
import java.awt.event.*;
import java.util.regex.*;

// One native text surface: visible grey Markdown while editing, styled reading after
// Save.
final class NewsMarkdownPane extends JTextPane {
    static final Color SYNTAX = new Color(0xa29b96);
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})([ \\t]+)(.*)$");
    private static final Pattern LIST = Pattern.compile(
            "^(\\s*(?:[-+*]|\\d+[.)])[ \\t]+)(.*)$");
    private String markdown = "";
    private boolean editing, updating, scheduled;
    private final UndoManager undo = new UndoManager();
    private Runnable changed = () -> { };
    NewsMarkdownPane() {
        setOpaque(false);
        setBorder(null);
        setMargin(new Insets(0, 0, 0, 0));
        setForeground(JourneyStyle.INK);
        setCaretColor(JourneyStyle.INK);
        setFont(LauncherFonts.minecraftSeven(16));
        setEditable(false);
        setName("news-markdown");
        getAccessibleContext().setAccessibleName(t("news.editorAccessible"));
        ((AbstractDocument) getDocument()).setDocumentFilter(new DocumentFilter() {
            @Override public void insertString(FilterBypass fb, int offset,
                    String text, AttributeSet attrs) throws BadLocationException {
                replace(fb, offset, 0, text, attrs);
            }
            @Override public void replace(FilterBypass fb, int offset, int length,
                    String text, AttributeSet attrs) throws BadLocationException {
                if (updating || fb.getDocument().getLength() - length
                        + (text == null ? 0 : text.length()) <=
                        dev.leverage.service.NewsService.MAX_MARKDOWN) super.replace(fb,
                        offset, length, text, attrs);
            }
        });
        getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                edit();
            }
            public void removeUpdate(DocumentEvent e) {
                edit();
            }
            public void changedUpdate(DocumentEvent e) { }
            private void edit() {
                if (updating || !editing) return;
                markdown = getText();
                if (!scheduled) {
                    scheduled = true;
                    SwingUtilities.invokeLater(() -> {
                        scheduled = false;
                        if (editing) {
                            decorate();
                            changed.run();
                        }
                    });
                }
            }
        });
        getDocument().addUndoableEditListener(e -> {
            if (!updating && (!(e.getEdit() instanceof DocumentEvent event)
                    || event.getType() != DocumentEvent.EventType.CHANGE)) undo.addEdit(
                    e.getEdit());
        });
        int shortcut;
        try { shortcut = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx(); }
        catch (HeadlessException ignored) { shortcut = InputEvent.CTRL_DOWN_MASK; }
        getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, shortcut), "news-undo");
        getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, shortcut), "news-redo");
        getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Z,
                shortcut | InputEvent.SHIFT_DOWN_MASK), "news-redo");
        getActionMap().put("news-undo", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (editing && undo.canUndo()) undo.undo();
            }
        });
        getActionMap().put("news-redo", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (editing && undo.canRedo()) undo.redo();
            }
        });
    }
    void onChange(Runnable listener) { changed = listener; }
    @Override public String getText() {
        try {
            return getDocument().getText(0, getDocument().getLength());
        } catch (BadLocationException e) {
            throw new IllegalStateException(e);
        }
    }
    String markdown() { return editing ? getText() : markdown; }
    boolean editing() { return editing; }
    void showMarkdown(String text, boolean edit) {
        markdown = text == null ? "" : text;
        editing = edit;
        updating = true;
        try {
            var render = render(markdown, editing);
            setText("");
            copy(render, getStyledDocument(), true);
            setEditable(editing);
            setCaretPosition(0);
            undo.discardAllEdits();
        }
        finally {
            updating = false;
        }
        revalidate();
        repaint();
    }
    private void decorate() {
        updating = true;
        try {
            copy(render(getText(), true), getStyledDocument(), false);
        } finally {
            updating = false;
        }
        revalidate();
        repaint();
    }
    private static void copy(StyledDocument source, StyledDocument target, boolean text) {
        try {
            if (text) target.insertString(0, source.getText(0, source.getLength()), null);
            target.setCharacterAttributes(0, target.getLength(),
                    style(LauncherFonts.minecraftSeven(16), JourneyStyle.INK), true);
            for (int at = 0; at < source.getLength();) {
                Element element = source.getCharacterElement(at);
                int end = Math.min(source.getLength(), element.getEndOffset());
                target.setCharacterAttributes(at, end - at,
                        element.getAttributes(), true);
                at = end;
            }
            var paragraph = new SimpleAttributeSet();
            StyleConstants.setSpaceAbove(paragraph, 0);
            StyleConstants.setSpaceBelow(paragraph, 5);
            StyleConstants.setLineSpacing(paragraph, .25f);
            target.setParagraphAttributes(0, target.getLength(), paragraph, true);
        } catch (BadLocationException e) { throw new IllegalStateException(e); }
    }
    private static SimpleAttributeSet style(Font font, Color color) {
        var attributes = new SimpleAttributeSet();
        StyleConstants.setFontFamily(attributes, font.getFamily());
        StyleConstants.setFontSize(attributes, font.getSize());
        StyleConstants.setBold(attributes, font.isBold());
        StyleConstants.setItalic(attributes, font.isItalic());
        StyleConstants.setForeground(attributes, color);
        return attributes;
    }
    private static void append(StyledDocument doc, String text, AttributeSet attributes) {
        Font original = new Font(StyleConstants.getFontFamily(attributes),
                (StyleConstants.isBold(attributes) ? Font.BOLD
                : 0) | (StyleConstants.isItalic(attributes) ? Font.ITALIC : 0),
                StyleConstants.getFontSize(attributes));
        Font compatible = LauncherFonts.forText(original, text);
        if (!compatible.equals(original)) {
            var adjusted = new SimpleAttributeSet(attributes);
            StyleConstants.setFontFamily(adjusted, compatible.getFamily());
            attributes = adjusted;
        }
        try {
            doc.insertString(doc.getLength(), text, attributes);
        } catch (BadLocationException e) {
            throw new IllegalStateException(e);
        }
    }
    private static AttributeSet marker() {
        return style(LauncherFonts.minecraftSeven(14), SYNTAX);
    }
    static StyledDocument render(String markdown, boolean edit) {
        var doc = new DefaultStyledDocument();
        String[] lines = markdown.split("\n", -1);
        String fence = null;
        for (int row = 0; row < lines.length; row++) {
            String line = lines[row];
            Font font = LauncherFonts.minecraftSeven(16);
            var base = style(font, JourneyStyle.INK);
            var fenceMatch = Pattern.compile("^\\s*(`{3,}|~{3,})(.*)$").matcher(line);
            if (fenceMatch.matches() && (fence == null || (fenceMatch.group(
                    1).charAt(0) == fence.charAt(0) && fenceMatch.group(
                    1).length() >= fence.length() && fenceMatch.group(2).isBlank()))) {
                fence = fence == null ? fenceMatch.group(1) : null;
                if (edit) append(doc, line, marker());
            } else if (fence != null) append(doc, line, style(new Font(Font.MONOSPACED,
                    Font.PLAIN, 14), JourneyStyle.INK));
            else {
                Matcher heading = HEADING.matcher(line), list = LIST.matcher(line),
                        quote = Pattern.compile("^(\\s*>[ \\t]?)(.*)$").matcher(line);
                if (heading.matches()) {
                    int level = heading.group(1).length();
                    font = LauncherFonts.minecraftTen(new int[] { 27, 25, 20, 19, 17, 16 }
                    [level - 1]);
                    base = style(font, JourneyStyle.INK);
                    if (edit) append(doc, heading.group(1) + heading.group(2), marker());
                    String content = heading.group(3);
                    Matcher closing = Pattern.compile(
                            "^(.*?)([ \\t]+#+[ \\t]*)$").matcher(content);
                    inline(doc, closing.matches() ? closing.group(1) : content,
                            edit, base, 0);
                    if (edit && closing.matches()) append(doc,
                            closing.group(2), marker());
                } else if (line.matches("\\s*(?:-{3,}|\\*{3,}|_{3,})\\s*")) {
                    append(doc, edit ? line : "────────────", marker());
                }
                else if (list.matches()) {
                    String prefix = list.group(1), content = list.group(2);
                    append(doc, edit ? prefix : prefix.strip().matches("\\d+[.)]")
                            ? prefix : "• ", edit ? marker() : base);
                    Matcher task = Pattern.compile(
                            "^(\\[([ xX])][ \\t]+)(.*)$").matcher(content);
                    if (task.matches()) {
                        append(doc, edit ? task.group(1) : task.group(2).isBlank()
                                ? "☐ " : "☑ ", edit ? marker() : base);
                        content = task.group(3);
                    }
                    inline(doc, content, edit, base, 0);
                } else if (quote.matches()) {
                    append(doc, edit ? quote.group(1) : "▎ ", marker());
                    inline(doc, quote.group(2), edit, style(font,
                            new Color(0x52604a)), 0);
                }
                else inline(doc, line, edit, base, 0);
            }
            if (row < lines.length - 1) append(doc, "\n",
                    style(LauncherFonts.minecraftSeven(16), JourneyStyle.INK));
        } return doc;
    }
    private static void inline(StyledDocument doc, String text,
            boolean edit, AttributeSet base, int depth) {
        if (depth > 8) {
            append(doc, text, base);
            return;
        }
        for (int i = 0; i < text.length();) {
            if (text.charAt(i) == '\\' && i + 1 < text.length()
                    && "\\`*_[]{}()#+.!>~-".indexOf(text.charAt(i + 1)) >= 0) {
                if (edit) append(doc, "\\", marker());
                append(doc, text.substring(i + 1, i + 2), base);
                i += 2;
                continue;
            }
            if (text.charAt(i) == '[' || text.startsWith("![", i)) {
                int labelStart = i + (text.charAt(i) == '!' ? 2 : 1),
                        labelEnd = text.indexOf("](", labelStart),
                        end = labelEnd < 0 ? -1 : text.indexOf(')', labelEnd + 2);
                if (end >= 0) {
                    String url = text.substring(labelEnd + 2, end),
                            label = text.substring(labelStart, labelEnd);
                    if (edit) append(doc, text.substring(i, labelStart), marker());
                    var link = new SimpleAttributeSet(base);
                    StyleConstants.setForeground(link, new Color(0x2d6b22));
                    StyleConstants.setUnderline(link, true);
                    inline(doc, label, edit, link, depth + 1);
                    if (edit) append(doc, "](" + url + ")", marker());
                    i = end + 1;
                    continue;
                }
            }
            boolean matched = false;
            for (String delimiter : new String[] { "**", "__", "~~", "*", "_", "`" }) {
                if (!text.startsWith(delimiter, i)) continue;
                if (delimiter.equals("_") && i > 0 && Character.isLetterOrDigit(
                        text.charAt(i - 1))) continue;
                int end = text.indexOf(delimiter, i + delimiter.length());
                if (end <= i + delimiter.length()) continue;
                String content = text.substring(i + delimiter.length(), end);
                if (content.startsWith(" ") || content.endsWith(" ")) continue;
                var attributes = new SimpleAttributeSet(base);
                if (delimiter.equals("`")) {
                    StyleConstants.setFontFamily(attributes, Font.MONOSPACED);
                    StyleConstants.setFontSize(attributes, 14);
                    StyleConstants.setBackground(attributes, new Color(0xeee6df));
                }
                else if (delimiter.equals("~~")) StyleConstants.setStrikeThrough(
                        attributes, true);
                else if (delimiter.length() == 2) StyleConstants.setBold(
                        attributes, true);
                else StyleConstants.setItalic(attributes, true);
                if (edit) append(doc, delimiter, marker());
                if (delimiter.equals("`")) append(doc, content, attributes);
                else inline(doc, content, edit, attributes, depth + 1);
                if (edit) append(doc, delimiter, marker());
                i = end + delimiter.length();
                matched = true;
                break;
            }
            if (!matched) {
                int next = i + 1;
                while (next < text.length() && "\\[!*_~`".indexOf(
                        text.charAt(next)) < 0) next++;
                append(doc, text.substring(i, next), base);
                i = next;
            }
        }
    }
}
