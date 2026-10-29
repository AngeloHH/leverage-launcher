package dev.leverage.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import dev.leverage.client.LauncherPreferences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.accessibility.AccessibleRole;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static dev.leverage.ui.JourneyPreviewSupport.*;
import static org.junit.jupiter.api.Assertions.*;

// Windowless interaction and real-paint checks for the approved settings / editorial
// About views.
class JourneyPanelsTest {
    @TempDir Path isolated;

    @Test
    void languageSelectionNotifiesOnlyActualChanges() throws Exception {
        var options=onEdt(()-> {
            var panel=new JourneySettingsPanel(new ProfileSettingsPanel(),
                    new ServerSettingsPanel(), ()-> { });
            var language=named(panel, "settings-language", PixelDropdown.class);
            assertNotNull(language);
            assertEquals(4, language.getItemCount());
            assertEquals(4, language.getMaximumRowCount());
            assertEquals("Español", language.getItemAt(0));
            assertEquals("Inglés", language.getItemAt(1));
            assertEquals("Ruso", language.getItemAt(2));
            assertEquals("Chino simplificado", language.getItemAt(3));
            assertEquals("Español", language.getSelectedItem());
            var changes=new AtomicInteger();
            panel.onLanguageChange(nextLanguage->changes.incrementAndGet());
            language.setSelectedIndex(0);
            assertEquals(0, changes.get());
            language.setSelectedIndex(1);
            assertEquals("Inglés", language.getSelectedItem());
            assertEquals(1, changes.get());
            language.setSelectedIndex(1);
            assertEquals(1, changes.get());
            language.setSelectedIndex(2);
            language.setSelectedIndex(3);
            language.setSelectedIndex(0);
            assertEquals(4, changes.get());
            assertTrue(descendants(panel).stream().anyMatch(child->
                    child instanceof JLabel label && "AJUSTES".equals(label.getText())));
            for (int[] size:SIZES) {
                render(panel, size[0], panel.getPreferredSize().height);
                assertControlsFit(panel);
                var about=named(panel, "settings-about", JButton.class);
                assertSame(language.getParent(), about.getParent());
                assertTrue(language.getY()+language.getHeight()<about.getY());
            }
            language.setSelectedIndex(3);
            return language.createOptionsPreview(4);
        });
        write(outputDirectory(), "languages-four-options",
                onEdt(()->render(options, 350, 192)));
    }

    @Test
    void settingsPreservesControlFontsAndStyles() throws Exception {
        var page=onEdt(()-> {
            FlatDarkLaf.setup();
            var panel=new JourneySettingsPanel(new ProfileSettingsPanel(),
                    new ServerSettingsPanel(), ()-> { });
            assertFalse(descendants(panel).stream().anyMatch(
                    child->child instanceof JLabel label
                    && "TU LAUNCHER, A TU MANERA.".equals(label.getText())));
            for (String name:new String[] { "profile-save-username",
                    "profile-save-email", "profile-save-password", "profile-refresh",
                    "settings-open-folder", "test-server-connection",
                        "save-download-sources" }) {
                JButton button=named(panel, name, JButton.class);
                assertEquals(LauncherFonts.minecraftTen(button.getFont().getSize2D()),
                        button.getFont(), name);
            }
            for (String name:new String[] { "profile-save-username", "profile-save-email",
                    "profile-refresh", "profile-save-password" }) {
                assertEquals(named(panel, "save-download-sources",
                        JButton.class).getFont(), named(panel, name,
                        JButton.class).getFont(), name);
            }
            for (String name:new String[] { "profile-password-toggle",
                    "settings-disconnect", "settings-about" }) {
                JButton link=named(panel, name, JButton.class);
                assertEquals(LauncherFonts.minecraftSeven(link.getFont().getSize2D()),
                        link.getFont(), name);
            }
            assertEquals(PixelInputStyle.font(), named(panel,
                    "profile-username", JTextField.class).getFont());
            assertEquals(LauncherFonts.minecraftSeven(14f), named(panel,
                    "settings-remember-device", PixelCheckBox.class).getFont());
            named(panel, "profile-password-toggle", JButton.class).doClick(0);
            panel.setInstanceDirectory(isolated.resolve("instance"));
            return panel;
        });
        for (int[] size:SIZES) {
            var image=onEdt(()-> {
                var result=render(page, size[0], page.getPreferredSize().height);
                assertControlsFit(page);
                return result;
            });
            write(outputDirectory(), "settings-typography-"+size[0], image);
        }
    }

    @Test
    void settingsKeepsRememberDisconnectAndFolderActionsDistinct() throws Exception {
        onEdt(()-> {
            var about=new AtomicInteger();
            var disconnected=new AtomicInteger();
            var opened=new AtomicInteger();
            var changes=new AtomicInteger();
            var value=new AtomicReference<Boolean>();
            var panel=new JourneySettingsPanel(new ProfileSettingsPanel(),
                    new ServerSettingsPanel(), about::incrementAndGet);
            panel.onDisconnect(disconnected::incrementAndGet);
            panel.onOpenFolder(opened::incrementAndGet);
            panel.onRememberDevice(selected-> {
                changes.incrementAndGet();
                value.set(selected);
            });
            assertNotNull(panel.profile());
            assertNotNull(panel.serverSettings());
            panel.setRememberDevice(true);
            assertTrue(panel.isRememberDevice());
            assertEquals(0, changes.get(),
                    "Restoring a preference must not write it back");
            named(panel, "settings-remember-device", PixelCheckBox.class).doClick(0);
            assertFalse(panel.isRememberDevice());
            assertEquals(Boolean.FALSE, value.get());
            assertEquals(1, changes.get());
            panel.setRememberDevice(true);
            assertEquals(1, changes.get());
            named(panel, "settings-disconnect", JButton.class).doClick(0);
            assertEquals(1, disconnected.get());
            PixelButton folder=named(panel, "settings-open-folder", PixelButton.class);
            assertFalse(folder.isEnabled());
            Path instance=isolated.resolve("instance");
            panel.setInstanceDirectory(instance);
            assertEquals(instance, panel.instanceDirectory());
            JTextField directory=named(panel, "settings-instance-directory",
                    JTextField.class);
            assertFalse(directory.isEditable());
            assertEquals(instance.resolve("mods").toString(), directory.getText());
            folder.doClick(0);
            assertEquals(1, opened.get());
            named(panel, "settings-about", JButton.class).doClick(0);
            assertEquals(1, about.get());
            panel.setDisconnectEnabled(false);
            named(panel, "settings-disconnect", JButton.class).doClick(0);
            assertEquals(1, disconnected.get());
            panel.setInstanceDirectory(null);
            assertFalse(folder.isEnabled());
            return null;
        });
    }

    @Test
    void settingsUsesTwoAlignedColumnsAndFullWidthFolderAtEverySupportedWindowSize()
            throws Exception {
        onEdt(()-> {
            FlatDarkLaf.setup();
            var panel=new JourneySettingsPanel(new ProfileSettingsPanel(),
                    new ServerSettingsPanel(), ()-> { });
            panel.setInstanceDirectory(isolated.resolve("instance"));
            for (int[] size:SIZES) {
                JScrollPane scroll=scroller(panel);
                render(scroll, size[0], size[1]);
                assertControlsFit(panel);
                JPanel left=named(panel, "settings-profile-column", JPanel.class),
                        right=named(panel, "settings-server-column", JPanel.class);
                assertEquals(left.getY(), right.getY());
                assertTrue(right.getX()>left.getX()+left.getWidth());
                PixelButton folder=named(panel, "settings-open-folder",
                        PixelButton.class);
                assertEquals(left.getWidth(), folder.getWidth(),
                        "The folder action must fill its own profile column");
                assertEquals(48, folder.getHeight());
                assertTrue(folder.getWidth()>250);
            }
            return null;
        });
    }

    @Test
    void aboutContainsOnlyThreeEditorialCardsWithWorkshopAndWorkingStoryActions()
            throws Exception {
        var back=new AtomicInteger(); AboutPanel panel=onEdt(()-> {
            var page=new AboutPanel(back::incrementAndGet);
            render(page, 1040, page.getPreferredSize().height);
            assertEquals(3, descendants(page).stream().filter(child->child.getName()!=null
                    &&child.getName().startsWith("about-card-")).count());
            assertNull(find(page, PlayerSkinView.class),
                    "The final About is editorial, not the discarded team portrait ex"
                    + "ploration");
            assertTrue(page.isWorkshopLit());
            named(page, "about-light-switch", JButton.class).doClick(0);
            assertFalse(page.isWorkshopLit());
            page.setWorkshopLit(true);
            assertTrue(page.isWorkshopLit());
            named(page, "about-back", JButton.class).doClick(0);
            assertEquals(1, back.get());
            named(page, "about-read-from-start", PixelButton.class).doClick(0);
            assertEquals("origins", page.selectedStory());
            assertTrue(named(page, "about-reader", JPanel.class).isVisible());
            assertEquals("La primera piedra", named(page,
                    "about-reader-title", JLabel.class).getText());
            named(page, "about-close-story", JButton.class).doClick(0);
            assertEquals("", page.selectedStory());
            assertFalse(named(page, "about-reader", JPanel.class).isVisible());
            return page;
        });
        drainEdt();
        for (String id:new String[] { "origins", "relay", "today" }) {
            onEdt(()-> {
                named(panel, "about-story-"+id, JButton.class).doClick(0);
                return null;
            });
            drainEdt();
            onEdt(()-> {
                assertEquals(id, panel.selectedStory());
                assertTrue(named(panel, "about-reader", JPanel.class).isVisible());
                panel.closeStory();
                return null;
            });
            drainEdt();
        }
    }

    @Test
    void deferredStoryRevealsCannotReopenClosedReadersOrRestoreOlderStories()
            throws Exception {
        AboutPanel panel=onEdt(()-> {
            var page=new AboutPanel(()-> { });
            page.setSize(1040, page.getPreferredSize().height);
            layout(page);
            page.openStory("origins");
            page.closeStory();
            return page;
        });
        drainEdt();
        onEdt(()-> {
            assertEquals("", panel.selectedStory());
            assertFalse(named(panel, "about-reader", JPanel.class).isVisible());
            panel.openStory("origins");
            panel.openStory("relay");
            return null;
        });
        drainEdt(); onEdt(()-> {
            assertEquals("relay", panel.selectedStory());
            assertEquals("El taller crece", named(panel,
                    "about-reader-title", JLabel.class).getText());
            String text=accessibleText(named(panel, "about-reader", JPanel.class));
            assertTrue(text.contains("RK2 encontraba usos inesperados"));
            assertTrue(text.contains("Angelo y Weenda empezaron como Newbies"));
            assertFalse(text.contains(
                    "Antes de los relevos y de las nuevas generaciones"),
                    "Replacing a story must remove its old paragraphs");
            assertThrows(IllegalArgumentException.class,
                    ()->panel.openStory("unknown-story"));
            return null;
        });
    }

    @Test
    void editorialParagraphsExposeTheirActualNarrativeToAssistiveTechnology()
            throws Exception {
        onEdt(()-> {
            var panel=new AboutPanel(()-> { });
            panel.openStory("origins");
            JPanel reader=named(panel, "about-reader", JPanel.class);
            var paragraphs=descendants(reader).stream().filter(
                    child->child.getAccessibleContext()!=null&&AccessibleRole.TEXT.equals(
                    child.getAccessibleContext().getAccessibleRole())).toList();
            assertEquals(3, paragraphs.size());
            for (Component paragraph:paragraphs) {
                assertNotNull(paragraph.getAccessibleContext().getAccessibleName());
                assertFalse(
                        paragraph.getAccessibleContext().getAccessibleName().isBlank());
            }
            String text=accessibleText(reader);
            assertTrue(text.contains("MacKey fue el cerebro y el maestro"));
            assertTrue(text.contains("Ghost Warrior aportaba mods"));
            assertTrue(text.contains("De los tres aprendió Angelo"));
            return null;
        });
        drainEdt();
    }

    @Test
    void exportsFullEditorialCardsAndBothLongReadersAtAllSupportedWidths()
            throws Exception {
        Path output=outputDirectory();
        int before=Window.getWindows().length;
        for (int[] size:SIZES) {
            int width=size[0];
            AboutPanel panel=onEdt(()-> {
                FlatDarkLaf.setup();
                return new AboutPanel(()-> { });
            });
            BufferedImage closed=onEdt(()->fullPage(panel, width));
            write(output, "about-full-"+width, closed);
            Rectangle cardBounds=onEdt(()-> {
                Rectangle union=null;
                for (String id:new String[] { "origins", "relay", "today" }) {
                    JPanel card=named(panel, "about-card-"+id, JPanel.class);
                    union=union==null?new Rectangle(card.getBounds())
                            :union.union(card.getBounds());
                }
                return union;
            });
            write(output, "about-cards-"+width, closed.getSubimage(cardBounds.x,
                    cardBounds.y, cardBounds.width, cardBounds.height));
            for (String id:new String[] { "origins", "relay" }) {
                onEdt(()-> {
                    panel.openStory(id);
                    return null;
                });
                drainEdt();
                BufferedImage opened=onEdt(()->fullPage(panel, width));
                write(output, "about-reader-"+id+"-full-"+width, opened);
                onEdt(()-> {
                    panel.closeStory();
                    return null;
                });
                drainEdt();
            }
        }
        assertEquals(before, Window.getWindows().length,
                "Editorial exports must never create a JFrame");
    }

    private static BufferedImage fullPage(AboutPanel panel, int width) {
        panel.setSize(width, 1);
        int height=panel.getPreferredSize().height;
        BufferedImage image=render(panel, width, height);
        assertControlsFit(panel);
        return image;
    }
    private static JScrollPane scroller(JPanel panel) {
        var scroll=new JScrollPane(panel, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        return scroll;
    }
    private static String accessibleText(Container root) {
        return descendants(root).stream().map(Component::getAccessibleContext).filter(
                java.util.Objects::nonNull).map(context->context.getAccessibleName()==null
                ?"":context.getAccessibleName()).collect(Collectors.joining("\n"));
    }
}
