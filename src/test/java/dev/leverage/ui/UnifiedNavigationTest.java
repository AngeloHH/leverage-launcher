package dev.leverage.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import static dev.leverage.ui.JourneyPreviewSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class UnifiedNavigationTest {
    @Test void identityNeverMovesTheWindowCenteredTabsAndLongNamesAreEllipsized(
            ) throws Exception {
        var output=outputDirectory();
        for (int[] size:SIZES) {
            var home=onEdt(()-> {
                FlatDarkLaf.setup();
                var panel=new LauncherHomePanel((message, error)-> { });
                panel.setAnimationEnabled(false);
                return panel;
            });
            for (var screen:LauncherHomePanel.Screen.values()) {
                var image=onEdt(()-> {
                    home.showScreen(screen);
                    home.setAccount("A", true);
                    render(home, size[0], size[1]);
                    var tabs=named(home, "journey-main-tabs", PixelTabs.class);
                    Rectangle before=tabs.getBounds();
                    var reference=new JourneyNavigation(ignored-> { });
                    reference.setSize(size[0], JourneyNavigation.HEIGHT);
                    reference.doLayout();
                    assertEquals(named(reference, "journey-main-tabs",
                            PixelTabs.class).getBounds(), before);
                    assertTrue(Math.abs(size[0]- (2*before.x+before.width))<=1);
                    String full="NombreDemasiadoLargoParaElEspacioDisponible".repeat(4);
                    home.setAccount(full, true);
                    var result=render(home, size[0], size[1]);
                    assertEquals(before, tabs.getBounds());
                    var account=find(home, PlayerAccountCard.class);
                    assertSame(tabs.getParent(), account.getParent());
                    assertTrue(account.getX()+account.getWidth()<=tabs.getX()-12);
                    JLabel name=named(account, "account-username", JLabel.class);
                    assertTrue(name.getText().endsWith("..."));
                    assertEquals(full, name.getToolTipText());
                    assertTrue(name.getFontMetrics(name.getFont()).stringWidth(
                            name.getText())<=name.getWidth());
                    var settings=named(home, "header-settings", JButton.class);
                    assertEquals(account.getX()+40, settings.getX());
                    assertEquals(account.getY()+18, settings.getY(),
                            "Keep Settings tucked under the name, six pixels closer t"
                            + "han the former spacing");
                    assertEquals(LauncherFonts.minecraftTen(16), name.getFont());
                    assertEquals(LauncherFonts.minecraftFive(settings.getFont(
                            ).getSize()), settings.getFont());
                    assertTrue(settings.getFontMetrics(settings.getFont()).stringWidth(
                            settings.getText())<=settings.getWidth());
                    assertFalse(descendants(home).stream().anyMatch(
                            c->"journey-account-header".equals(c.getName())));
                    if (screen!=LauncherHomePanel.Screen.HOME)assertEquals(0,
                            tabs.getParent().getY());
                    assertControlsFit(home);
                    return result;
                });
                if (screen==LauncherHomePanel.Screen.LIBRARY
                        ||screen==LauncherHomePanel.Screen.HOME)
                    write(output, "unified-navigation-"+screen.name().toLowerCase()
                        +"-"+size[0], image);
            }
        }
    }
}
