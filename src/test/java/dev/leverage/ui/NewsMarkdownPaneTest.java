package dev.leverage.ui;

import org.junit.jupiter.api.Test;
import javax.swing.text.StyleConstants;
import static dev.leverage.ui.JourneyPreviewSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class NewsMarkdownPaneTest {
    @Test void symbolsAreGreyAndTitlesUseTenWithoutChangingTheMarkdownSource(
            )throws Exception {
        String raw=
                "# Título\n\n**Negrita** y *cursiva* con ~~tachado~~ y `código`.\n\n-"
                + " Lista\n> Cita\n[Enlace](https://example.test)";
        onEdt(()-> {
            var pane=new NewsMarkdownPane();
            pane.showMarkdown(raw, true);
            assertEquals(raw, pane.markdown());
            assertEquals(raw, pane.getText());
            assertEquals(NewsMarkdownPane.SYNTAX, StyleConstants.getForeground(
                    pane.getStyledDocument().getCharacterElement(0).getAttributes()));
            assertEquals(LauncherFonts.minecraftTen(27).getFamily(),
                    StyleConstants.getFontFamily(pane.getStyledDocument(
                    ).getCharacterElement(3).getAttributes()));
            pane.showMarkdown(raw, false);
            assertFalse(pane.isEditable());
            assertEquals(raw, pane.markdown());
            assertTrue(pane.getText().contains("Título"));
            assertFalse(pane.getText().contains("**"));
            assertFalse(pane.getText().contains("https://"));
            pane.showMarkdown(pane.markdown(), true);
            assertEquals(raw, pane.getText());
            return null;
        });
    }
    @Test void typingRecolorsHeadingsAndUndoIgnoresStyleChanges()throws Exception {
        var pane=onEdt(()-> {
            var p=new NewsMarkdownPane();
            p.showMarkdown("Texto", true);
            p.getDocument().insertString(0, "### ", null);
            return p;
        });
        drainEdt();
        onEdt(()-> {
            assertEquals("### Texto", pane.markdown());
            assertEquals(LauncherFonts.minecraftTen(20).getFamily(),
                    StyleConstants.getFontFamily(pane.getStyledDocument(
                    ).getCharacterElement(5).getAttributes()));
            pane.getActionMap().get("news-undo").actionPerformed(null);
            assertEquals("Texto", pane.markdown());
            return null;
        });
    }
    @Test void rawHtmlIsLiteralAndEscapedMarkdownIsNotStyled()throws Exception {
        var doc=NewsMarkdownPane.render("<script>alert(1)</script> \\*literal\\*", false);
        assertEquals("<script>alert(1)</script> *literal*",
                doc.getText(0, doc.getLength()));
    }
    @Test void geometryNeverMakesTwoByTwoHolesOrExtrusionsAndExtendsWithoutRerolling() {
        for (int seed=0; seed<512; seed++)for (int width:new int[] { 6,
                8, 12, 13, 24, 25, 32, 33, 64, 128 }) {
            var rows=NewsBlockEdge.build(width, seed);
            var grown=NewsBlockEdge.build(width+5, seed);
            for (int x=0; x<width; x++) {
                for (int y=0; y<3; y++)assertEquals(rows[y][x], grown[y][x]);
                assertFalse(rows[0][x]&&rows[1][x]);
                if (x+1<width) {
                    assertFalse(rows[1][x]&&rows[1][x+1]&&rows[2][x]&&rows[2][x+1]);
                    assertFalse(!rows[1][x]&&!rows[1][x+1]&&!rows[2][x]&&!rows[2][x+1]);
                } }
        }
    }
}
