package dev.leverage.ui;

import dev.leverage.i18n.I18n;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;

// Six digit-only fields styled as large recessed pixel squares.
public final class VerificationCodeInput extends JPanel {
    private static final int DIGITS = 6;
    private static final int SIZE = 64;
    private static final int GAP = 10;
    private final JTextField[] fields = new JTextField[DIGITS];
    private final List<ActionListener> listeners = new ArrayList<>();
    private boolean distributing;

    public VerificationCodeInput() {
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
        setOpaque(false);
        setAlignmentX(0.5f);
        Dimension size = new Dimension(DIGITS * SIZE + (DIGITS - 1) * GAP, SIZE);
        setPreferredSize(size);
        setMinimumSize(new Dimension(218, SIZE));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, SIZE));
        for (int index = 0; index<DIGITS; index++) {
            int digitIndex = index;
            JTextField field = new DigitField();
            fields[index] = field;
            ((AbstractDocument) field.getDocument()).setDocumentFilter(
                    new DigitFilter(index));
            field.getAccessibleContext().setAccessibleName(I18n.t("auth.digit",
                    Integer.toString(index + 1)));
            field.addActionListener(event -> {
                ActionEvent submitted = new ActionEvent(this,
                        ActionEvent.ACTION_PERFORMED, "verify");
                for (ActionListener listener : List.copyOf(
                        listeners)) listener.actionPerformed(submitted);
            });
            bind(field, "BACK_SPACE", "previous-or-delete", () -> {
                if (!field.getText().isEmpty()) {
                    field.setText("");
                } else if (digitIndex > 0) {
                    fields[digitIndex - 1].setText("");
                    focusDigit(digitIndex - 1);
                }
            });
            bind(field, "LEFT", "previous-digit", () -> focusDigit(Math.max(0,
                    digitIndex - 1)));
            bind(field, "RIGHT", "next-digit", () -> focusDigit(
                    Math.min(DIGITS - 1, digitIndex + 1)));
            if (index > 0) add(Box.createHorizontalStrut(GAP));
            add(field);
        }
    }

    public JTextField firstField() {
        return fields[0];
    }

    public String getCode() {
        StringBuilder code = new StringBuilder(DIGITS);
        for (JTextField field : fields) {
            if (field.getText().length() != 1) return "";
            code.append(field.getText());
        }
        return code.toString();
    }

    public void clear() {
        distributing = true;
        try {
            for (JTextField field : fields) field.setText("");
        } finally {
            distributing = false;
        }
    }

    public void addActionListener(ActionListener listener) {
        listeners.add(listener);
    }

    private void focusDigit(int index) {
        fields[index].requestFocusInWindow();
        fields[index].selectAll();
    }

    private static void bind(JTextField field, String key, String name, Runnable action) {
        field.getInputMap().put(KeyStroke.getKeyStroke(key), name);
        field.getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                action.run();
            }
        });
    }

    private final class DigitFilter extends DocumentFilter {
        private final int index;

        private DigitFilter(int index) {
            this.index = index;
        }

        @Override public void insertString(FilterBypass bypass, int offset,
                String text, AttributeSet attributes) throws BadLocationException {
            replace(bypass, offset, 0, text, attributes);
        }

        @Override public void replace(FilterBypass bypass, int offset, int length,
                String text, AttributeSet attributes) throws BadLocationException {
            if (distributing || text == null || text.isEmpty()) {
                bypass.replace(offset, length, text, attributes);
                return;
            }
            String digits = text.replaceAll("[\\s-]", "");
            if (!digits.matches("[0-9]{1,6}")) return;
            int start = digits.length() == DIGITS ? 0 : index;
            if (start + digits.length() > DIGITS) return;
            distributing = true;
            try {
                for (int i = 0; i < digits.length(); i++) {
                    int destination = start + i;
                    String digit = digits.substring(i, i + 1);
                    if (destination == index) {
                        bypass.replace(0, bypass.getDocument().getLength(),
                                digit, attributes);
                    } else {
                        fields[destination].setText(digit);
                    }
                }
            } finally {
                distributing = false;
            }
            int next = Math.min(DIGITS - 1, start + digits.length());
            SwingUtilities.invokeLater(() -> focusDigit(next));
        }
    }

    private static final class DigitField extends JTextField {
        private DigitField() {
            setHorizontalAlignment(SwingConstants.CENTER);
            setFont(LauncherFonts.minecraftSeven(30f));
            setForeground(Color.WHITE);
            setCaretColor(Color.WHITE);
            setBackground(new Color(0x26, 0x24, 0x23));
            setSelectionColor(new Color(0x36, 0xB0, 0x30));
            setSelectedTextColor(Color.WHITE);
            setBorder(BorderFactory.createLineBorder(new Color(0x6B, 0x65, 0x62), 2));
            setOpaque(false);
            putClientProperty("JComponent.minimumWidth", 0);
            Dimension size = new Dimension(SIZE, SIZE);
            setPreferredSize(size);
            setMinimumSize(new Dimension(28, SIZE));
            setMaximumSize(size);
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setColor(getBackground());
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(new Color(0, 0, 0, 128));
                g.fillRect(2, 2, getWidth() - 4, 6);
            } finally {
                g.dispose();
            }
            super.paintComponent(graphics);
        }
    }
}
