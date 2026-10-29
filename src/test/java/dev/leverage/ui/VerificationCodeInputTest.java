package dev.leverage.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.event.ActionEvent;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerificationCodeInputTest {
    @Test
    void pasteDistributesDigitsAndPreservesLeadingZeroEvenFromAnotherSlot(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var input = new VerificationCodeInput();
            JTextField[] digits = fields(input);
            digits[0].setText("012345");
            assertEquals("012345", input.getCode());
            digits[4].setText("987654");
            assertEquals("987654", input.getCode());
            input.clear();
            assertEquals("", input.getCode());
            assertTrue(Arrays.stream(digits).allMatch(
                    field -> field.getText().isEmpty()));
        });
    }

    @Test
    void rejectsLettersAndLongPasteWithoutCorruptingTheCode() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var input = new VerificationCodeInput();
            JTextField[] digits = fields(input);
            digits[0].setText("12 34-56");
            assertEquals("123456", input.getCode());
            digits[1].replaceSelection("letters");
            assertEquals("123456", input.getCode());
            digits[2].replaceSelection("1234567");
            assertEquals("123456", input.getCode());
        });
    }

    @Test
    void backspaceOnEmptySlotClearsPreviousDigitAndEnterSubmits() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var input = new VerificationCodeInput();
            JTextField[] digits = fields(input);
            digits[0].setText("1");
            digits[1].getActionMap().get("previous-or-delete")
                    .actionPerformed(new ActionEvent(digits[1],
                    ActionEvent.ACTION_PERFORMED, ""));
            assertEquals("", digits[0].getText());
            digits[0].setText("012345");
            int[] submitted = { 0 };
            input.addActionListener(event -> submitted[0]++);
            digits[5].postActionEvent();
            assertEquals(1, submitted[0]);
        });
    }

    private static JTextField[] fields(VerificationCodeInput input) {
        return Arrays.stream(input.getComponents()).filter(JTextField.class::isInstance)
                .map(JTextField.class::cast).toArray(JTextField[]::new);
    }
}
