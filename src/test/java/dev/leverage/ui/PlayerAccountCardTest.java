package dev.leverage.ui;

import dev.leverage.skin.DefaultSkin;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class PlayerAccountCardTest {
    @Test
    void showsAccountNameAndOnlineOrOfflineStatus() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PlayerAccountCard card = new PlayerAccountCard();
            card.setAccount("Alex_Test", true);
            var labels = Arrays.stream(card.getComponents(
                    )).filter(JLabel.class::isInstance)
                    .map(JLabel.class::cast).toList();
            assertTrue(labels.stream().anyMatch(label -> "Alex_Test".equals(
                    label.getText())));
            assertTrue(labels.stream().anyMatch(label -> "Online".equals(
                    label.getText())));
            assertEquals("Alex_Test · Online",
                    card.getAccessibleContext().getAccessibleName());
            card.setAccount(null, false);
            assertTrue(labels.stream().anyMatch(label -> "Jugador".equals(
                    label.getText())));
            assertTrue(labels.stream().anyMatch(label -> "Offline".equals(
                    label.getText())));
            assertEquals(3, card.getComponentCount());
        });
    }

    @Test
    void avatarUsesHeadAndOverlayNotTheFullSkinAndKeepsPixelsSharp() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PlayerAccountCard card = new PlayerAccountCard();
            BufferedImage skin = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            for (int y = 8; y < 16; y++) for (int x = 8; x < 16;
                    x++) skin.setRGB(x, y, 0xff3399dd);
            skin.setRGB(40, 8, 0xffcc1122);
            card.setSkin(skin);
            BufferedImage image = render(card);
            assertEquals(new Color(0x6cc349).getRGB(), image.getRGB(0, 2));
            assertEquals(0, image.getRGB(2, 4) >>> 24,
                    "One transparent pixel separates face and green border");
            assertEquals(0xffcc1122, image.getRGB(3, 5));
            assertEquals(0xffcc1122, image.getRGB(6, 8));
            assertEquals(0xff3399dd, image.getRGB(7, 5));
            assertEquals(0xff3399dd, image.getRGB(19, 21));
            assertEquals(0xffcc1122, skin.getRGB(40, 8));
            card.setSkin(skin.getSubimage(0, 0, 64, 32));
            assertEquals(0xffcc1122, render(card).getRGB(3, 5));
            assertThrows(IllegalArgumentException.class, () -> card.setSkin(null));
            card.setSkin(DefaultSkin.image());
            assertDoesNotThrow(() -> render(card));
        });
    }

    private static BufferedImage render(PlayerAccountCard card) {
        card.setSize(248, 42);
        card.doLayout();
        BufferedImage image = new BufferedImage(248, 42, BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();
        try {
            card.paint(g);
        } finally {
            g.dispose();
        }
        return image;
    }
}
