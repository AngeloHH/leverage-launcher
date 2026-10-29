package dev.leverage.ui;

import dev.leverage.client.LauncherAuthClient.AccountProfile;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ProfileSettingsPanelTest {
    @Test void requiresLoginValidatesRepeatedPasswordAndClearsHandedOffSecrets(
            ) throws Exception {
        SwingUtilities.invokeAndWait(()-> {
            var panel=new ProfileSettingsPanel();
            var submitted=new AtomicReference<ProfileSettingsPanel.Edit>();
            var error=new AtomicReference<String>();
            panel.onEdit(submitted::set);
            panel.onNotice((text, failed)->error.set(text));
            var save=named(panel, "profile-save-password", PixelButton.class);
            assertFalse(save.isEnabled());
            panel.setIdentity("Example", true);
            assertFalse(save.isEnabled());
            panel.load(new AccountProfile(UUID.randomUUID(),
                    "Example", "example@example.test"));
            assertTrue(save.isEnabled());
            var current=named(panel, "profile-current-password", JPasswordField.class);
            var password=named(panel, "profile-new-password", JPasswordField.class);
            var repeat=named(panel, "profile-repeat-password", JPasswordField.class);
            current.setText("current-long-password");
            password.setText("new-long-password");
            repeat.setText("different-long-password");
            save.doClick(0);
            assertNull(submitted.get());
            assertEquals("Las contraseñas no coinciden.", error.get());
            repeat.setText("new-long-password");
            save.doClick(0);
            var edit=submitted.get();
            assertEquals(ProfileSettingsPanel.Kind.PASSWORD, edit.kind());
            assertArrayEquals("current-long-password".toCharArray(),
                    edit.currentPassword());
            assertEquals(0, current.getPassword().length);
            assertEquals(0, password.getPassword().length);
            assertEquals(0, repeat.getPassword().length);
            edit.clear();
            assertArrayEquals(new char["new-long-password".length()], edit.newPassword());
            panel.setBusy(true);
            assertFalse(save.isEnabled());
            assertFalse(current.isEnabled());
            panel.setBusy(false);
            assertTrue(save.isEnabled());
            panel.setIdentity("Jugador", false);
            assertFalse(save.isEnabled());
            assertEquals("", named(panel, "profile-email", JTextField.class).getText());
        });
    }

    @Test void keepsTheExistingSquareInputsAndFitsTheScrollableProfile(
            ) throws Exception {
        SwingUtilities.invokeAndWait(()-> {
            var home=new LauncherHomePanel((text, error)-> { });
            home.setAccount("Ejemplo", true);
            home.profile().load(new AccountProfile(UUID.randomUUID(),
                    "Ejemplo", "ejemplo@example.test"));
            home.showSection(LauncherHomePanel.Section.PROFILE);
            for (int width:new int[] { 820, 1040, 1280 }) {
                home.setSize(width, 680);
                layout(home);
                var field=named(home.profile(), "profile-username", JTextField.class);
                assertEquals(48, field.getHeight());
                assertTrue(field.getWidth()>100);
                var border= (javax.swing.border.LineBorder)field.getBorder();
                assertEquals(2, border.getThickness());
                assertFalse(border.getRoundedCorners());
                assertEquals(PixelInputStyle.FACE, field.getBackground());
                for (Component child:home.profile().getComponents())assertTrue(
                        child.getX()+child.getWidth()<=home.profile().getWidth());
            }
            String output=System.getenv("LEVERAGE_PLAY_PREVIEW");
            if (output!=null) {
                home.setSize(1040, 680);
                layout(home);
                var image=new BufferedImage(1040, 680, BufferedImage.TYPE_INT_ARGB);
                var g=image.createGraphics();
                try {
                    home.paint(g);
                } finally {
                    g.dispose();
                }
                try {
                    javax.imageio.ImageIO.write(image, "PNG", Path.of(output,
                            "leverage-profile-editor-preview.png").toFile());
                }
                catch (Exception failure) { throw new AssertionError(failure); } }
        });
    }

    private static <T> T named(Container root, String name, Class<T> type) {
        for (Component child:root.getComponents()) {
            if (name.equals(child.getName()))return type.cast(child);
            if (child instanceof Container container) {
                T found=named(container, name, type);
                if (found!=null)return found;
            }
        } return null; }
    private static void layout(Container root) {
        root.doLayout();
        for (Component child:root.getComponents())if (
                child instanceof Container container)layout(container);
    }
}
