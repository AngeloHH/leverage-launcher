package dev.leverage.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.List;
import static dev.leverage.ui.JourneyPreviewSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class SkinsOverlayTest {
    @Test void exportsTheRealDropdownPopupListWithPixelBevels() throws Exception {
        var image=onEdt(()-> {
            FlatDarkLaf.setup();
            var dropdown=new PixelDropdown<>(List.of("Clásico", "Slim"));
            var popup= (javax.swing.plaf.basic.BasicComboPopup)dropdown.getUI(
                    ).getAccessibleChild(dropdown, 0);
            var scroll= (JScrollPane)popup.getComponent(0);
            scroll.setPreferredSize(new Dimension(340, 96));
            var panel=new JPanel(new java.awt.BorderLayout());
            panel.add(dropdown, java.awt.BorderLayout.NORTH);
            panel.add(scroll, java.awt.BorderLayout.CENTER);
            popup.getList().setSelectedIndex(0);
            return render(panel, 340, 144);
        });
        write(outputDirectory(), "skins-dropdown-real", image);
    }
    @Test void hoverActionsAreOverTheModelAndLeaveWithoutChangingCardHeight(
            ) throws Exception {
        var gallery=onEdt(()-> {
            var panel=new SkinGalleryPanel();
            render(panel, 940, 560);
            return panel;
        });
        var card=onEdt(()-> (SkinGalleryPanel.SkinCard)gallery.getComponent(0));
        onEdt(()-> {
            assertFalse(card.overlay.isVisible());
            card.dispatchEvent(new MouseEvent(card, MouseEvent.MOUSE_ENTERED,
                    0, 0, 10, 10, 0, false));
            assertTrue(card.overlay.isVisible());
            assertTrue(card.apply.isVisible());
            Rectangle overlay=card.overlay.getBounds(), model=card.model.getBounds();
            assertTrue(model.intersects(overlay));
            assertTrue(overlay.y+overlay.height<=model.y+model.height);
            assertEquals(0, card.getComponentZOrder(card.overlay));
            assertEquals(260, card.getHeight());
            card.dispatchEvent(new MouseEvent(card, MouseEvent.MOUSE_EXITED,
                    0, 0, -1, -1, 0, false));
            return null;
        });
        onEdt(()-> {
            assertFalse(card.overlay.isVisible());
            assertEquals(260, card.getHeight());
            return null;
        });
    }

    @Test void exportsRealInventoryBannerAndHoverAtSupportedSizes() throws Exception {
        var home=onEdt(()-> {
            FlatDarkLaf.setup();
            var panel=new LauncherHomePanel();
            panel.setAnimationEnabled(false);
            find(panel, InstanceSelectionPanel.class).setPreviewVersions(
                    List.of("1.21.1", "1.20.4"), "1.21.1");
            panel.showSection(LauncherHomePanel.Section.SKINS);
            return panel;
        });
        for (int[] size:SIZES) {
            var image=onEdt(()-> {
                home.skins().select("noor");
                var result=render(home, size[0], size[1]);
                assertEquals(new Color(0x262423), named(home, "skins-launch-band",
                        JPanel.class).getBackground());
                assertControlsFit(home);
                return result;
            });
                write(outputDirectory(), "skins-hover-"+size[0], image);
        }
    }
}
