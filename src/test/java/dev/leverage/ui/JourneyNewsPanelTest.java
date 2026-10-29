package dev.leverage.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static dev.leverage.ui.JourneyPreviewSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class JourneyNewsPanelTest {
    @Test void laysOutTwoColumnsAndWrapsTheThirdCardWithoutDuplicatingProductionContent(
            ) throws Exception {
        var clicks=new AtomicInteger();
        var page=onEdt(()-> {
            FlatDarkLaf.setup();
            var real=new JourneyNewsPanel(()-> { });
            assertEquals(1, real.getComponentCount(),
                    "The column change must not fabricate another article");
            return new JourneyNewsPanel(List.of(
                new JourneyNewsPanel.Article("LA EDAD DEL COBRE",
                    "Nuevas ideas para construir, descubrir y dar vida a tu mundo"
                    + ".", JourneyStyle.COPPER),
                new JourneyNewsPanel.Article("NOTICIA DE PRUEBA",
                    "Contenido aislado para verificar la segunda columna.",
                    JourneyStyle.COPPER),
                new JourneyNewsPanel.Article("TERCERA NOTICIA DE PRUEBA",
                    "Contenido aislado para verificar el salto de fila y el ajust" +
                    "e del texto.", JourneyStyle.COPPER)), clicks::incrementAndGet);
        });
        for (int[] size:SIZES) {
            var image=onEdt(()-> {
                page.setSize(size[0], 1);
                var result=render(page, size[0], page.getPreferredSize().height);
                assertControlsFit(page);
                var first=named(page, "news-card-0", JPanel.class);
                var second=named(page, "news-card-1", JPanel.class);
                var third=named(page, "news-card-2", JPanel.class);
                assertEquals(first.getY(), second.getY());
                assertEquals(first.getHeight(), second.getHeight());
                assertEquals(24, second.getX()-first.getX()-first.getWidth());
                assertTrue(Math.abs(first.getWidth()-second.getWidth())<=1);
                assertEquals(first.getX(), third.getX());
                assertEquals(24, third.getY()-first.getY()-first.getHeight());
                assertTrue(third.getY()+third.getHeight()<page.getHeight());
                return result;
            });
            write(outputDirectory(), "news-two-column-fixture-"+size[0], image);
        }
        onEdt(()-> {
            named(page, "news-read-update", JButton.class).doClick(0);
            named(page, "news-read-update-1", JButton.class).doClick(0);
            assertEquals(2, clicks.get());
            return null;
        });
    }
}
