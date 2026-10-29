package dev.leverage.ui;

import javax.swing.*;
import java.awt.*;
import java.util.List;

// Both modes share a selection in this example, but remain independently reusable.
public final class PixelCarouselGallery extends JPanel {
    private final PixelCarousel images = new PixelCarousel(PixelCarousel.Mode.IMAGES);
    private final PixelCarousel data = new PixelCarousel(PixelCarousel.Mode.DATA);
    private final JComboBox<String> mode = new JComboBox<>(new String[] { "Solo imágenes",
            "Texto / datos", "Ambos (vinculados)" });
    private final JLabel count = new JLabel();
    private final Preview preview = new Preview();
    public PixelCarouselGallery() {
        super(new BorderLayout());
        setName("pixel-carousel-gallery");
        images.setName("pixel-carousel-images");
        data.setName("pixel-carousel-data");
        List<PixelCarousel.Item> items = sampleItems();
        images.setItems(items);
        data.setItems(items);
        images.addChangeListener(event -> {
            if (images.getSelectedIndex() >= 0) data.setSelectedIndex(
                    images.getSelectedIndex());
            updateCount();
        });
        data.addChangeListener(event -> {
            if (data.getSelectedIndex() >= 0) images.setSelectedIndex(
                    data.getSelectedIndex());
            updateCount();
        });
        data.addPropertyChangeListener("expanded", event -> {
            preview.revalidate();
            preview.repaint();
        });
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEADING, 12, 10));
        mode.setName("carousel-gallery-mode");
        mode.getAccessibleContext().setAccessibleName("Modo del carrusel");
        mode.addActionListener(event -> {
            preview.updateMode();
            preview.revalidate();
            preview.repaint();
        });
        JButton previous = new JButton("Anterior"), next = new JButton("Siguiente");
        previous.setName("carousel-gallery-previous");
        next.setName("carousel-gallery-next");
        previous.addActionListener(event -> images.setSelectedIndex(Math.floorMod(
                images.getSelectedIndex() - 1, items.size())));
        next.addActionListener(event -> images.setSelectedIndex((
                images.getSelectedIndex() + 1) % items.size()));
        count.setName("carousel-gallery-count");
        controls.add(mode);
        controls.add(previous);
        controls.add(next);
        controls.add(count);
        for (var child : controls.getComponents()) child.setFont(
                LauncherFonts.minecraftSeven(14));
        add(controls, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(preview,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setName("carousel-gallery-scroll");
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        add(scroll, BorderLayout.CENTER);
        JLabel note = new JLabel(
                "Click thumbnails · arrows / Home / End · mouse wheel"
                + " · launcher artwork");
        note.setFont(LauncherFonts.minecraftSeven(12));
        note.setBorder(BorderFactory.createEmptyBorder(8, 12, 10, 12));
        add(note, BorderLayout.SOUTH);
        preview.updateMode();
        updateCount();
    }
    private void updateCount() {
        count.setText((images.getSelectedIndex() + 1) + " / " + images.getItems().size());
        preview.revalidate();
        preview.repaint();
    }
    private final class Preview extends JPanel implements Scrollable {
        Preview() {
            super(null);
            setBackground(new Color(0x171615));
            add(images);
            add(data);
        }
        void updateMode() {
            images.setVisible(mode.getSelectedIndex() != 1);
            data.setVisible(mode.getSelectedIndex() != 0);
        }
        private int dataHeight(int width) {
            data.setSize(width, Math.max(120, data.getHeight()));
            return data.getPreferredSize().height;
        }
        @Override public Dimension getPreferredSize() {
            int width = getWidth() > 0 ? getWidth() : 980,
                    available = Math.max(1, width - 48), height = 48;
            if (images.isVisible()) height += imageHeight(Math.min(625, available));
            if (data.isVisible()) height += dataHeight(Math.min(876,
                    available)) + (images.isVisible() ? 16 : 0);
            return new Dimension(980, height);
        }
        private int imageHeight(int width) {
            return Math.max(160, (int) Math.round(width * 337d / 625));
        }
        @Override public void doLayout() {
            int width = Math.max(1, getWidth() - 48), imageWidth = Math.min(625,
                    width), dataWidth = Math.min(876, width);
            int height = getPreferredSize().height, y = Math.max(24,
                    (getHeight() - height) / 2 + 24);
            if (images.isVisible()) {
                int h = imageHeight(imageWidth);
                images.setBounds((getWidth() - imageWidth) / 2, y, imageWidth, h);
                y += h + 16;
            }
            if (data.isVisible()) data.setBounds((getWidth() - dataWidth) / 2,
                    y, dataWidth, dataHeight(dataWidth));
        }
        @Override public Dimension getPreferredScrollableViewportSize() {
            return new Dimension(980, 500);
        }
        @Override public int getScrollableUnitIncrement(Rectangle visibleRect,
                int orientation, int direction) {
            return 24;
        }
        @Override public int getScrollableBlockIncrement(Rectangle visibleRect,
                int orientation, int direction) {
            return Math.max(24, visibleRect.height - 24);
        }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() {
            return getParent() != null
                    && getPreferredSize().height <= getParent().getHeight();
        }
    }
    private static List<PixelCarousel.Item> sampleItems() {
        String description = "Explore the reef with Alex and Steve, discover colorful "
                + "coral and follow the dolphins through the underwater ruins. This "
                + "preview reuses the artwork already bundled with the launcher.\n\n"
                + "Select a thumbnail to keep the image and its details synchronized. "
                + "Long descriptions can expand without changing the selected image; "
                + "short descriptions remain compact. Both carousel modes use the "
                + "same items and work with the keyboard and mouse wheel.";
        return List.of(new PixelCarousel.Item("UNDERWATER EXPLORATION", "Preview",
                description, JourneyStyle.COPPER),
                new PixelCarousel.Item("CHERRY GROVE", "Preview",
                        "A quiet landscape at sunset.", JourneyStyle.image(
                        "/dev/leverage/ui/journey/cherry-grove-sunset.png")),
                new PixelCarousel.Item("SKY PARKOUR", "Preview",
                        "A new jump above the clouds.", JourneyStyle.image(
                        "/dev/leverage/ui/journey/sky-parkour.png")),
                new PixelCarousel.Item("FOREST EXPEDITION", "Preview",
                        "Follow the map into the autumn forest.", JourneyStyle.image(
                        "/dev/leverage/ui/journey/autumn-forest-exploration.png")),
                new PixelCarousel.Item("COPPER WORKSHOP", "Preview",
                        "Every project starts with curiosity.", JourneyStyle.image(
                        "/dev/leverage/ui/journey/copper-golem-workshop.png")));
    }
}
