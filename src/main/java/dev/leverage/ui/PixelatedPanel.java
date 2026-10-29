package dev.leverage.ui;

import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.List;
import java.util.Objects;

// Reusable transparent Swing container with the approved, single-grid pixel silhouette.
// Bounds include room for the two outer cell rows. Add ordinary Swing children normally;
// its insets keep them clear of holes and decorative cells. Configure it on the Swing
// EDT.
public final class PixelatedPanel extends JPanel {
    public enum Side { TOP, RIGHT, BOTTOM, LEFT }
    public enum Placement {
        NONE, BOTTOM_LEFT, BOTTOM_CORNERS, OPPOSITE_CORNERS, RIGHT, SIDES, ALL, CUSTOM
    }

    // A fraction of an edge, rounded outwards to whole cells; end is exclusive.
    public record EdgeRange(Side side, double start, double end, boolean reversed) {
        public EdgeRange {
            Objects.requireNonNull(side, "side");
            if (!Double.isFinite(start) || !Double.isFinite(end)
                    || start < 0 || end > 1 || start > end) {
                throw new IllegalArgumentException(
                        "El rango debe estar entre 0 y 1, con inicio <= fin.");
            }
        }
        public static EdgeRange whole(Side side) {
            return new EdgeRange(side, 0, 1, false);
        }
    }

    private int blockSize = 28;
    private long seed = 8;
    private Placement placement = Placement.BOTTOM_LEFT;
    private List<EdgeRange> edgeRanges = List.of();
    private Insets contentPadding = new Insets(16, 20, 16, 20);
    private Color shadeColor = new Color(0x616161);
    private transient PixelatedPanelGeometry.Geometry cached;
    private transient int cachedWidth = -1, cachedHeight = -1;

    public PixelatedPanel() { this(Placement.BOTTOM_LEFT); }

    public PixelatedPanel(Placement placement) {
        super(new BorderLayout());
        this.placement = Objects.requireNonNull(placement);
        setOpaque(false);
        setBackground(new Color(0x171615));
        setForeground(Color.WHITE);
        getAccessibleContext().setAccessibleName("Contenedor pixelado");
    }

    public int getBlockSize() { return blockSize; }
    public void setBlockSize(int size) {
        if (size < 4 || size > 128) throw new IllegalArgumentException(
                "El cuadrado debe medir entre 4 y 128 px.");
        if (blockSize != size) {
            blockSize = size;
            geometryChanged();
        }
    }
    public long getSeed() { return seed; }
    public void setSeed(long seed) {
        if (this.seed != seed) {
            this.seed = seed;
            geometryChanged();
        }
    }
    public Placement getPlacement() { return placement; }
    public void setPlacement(Placement placement) {
        placement = Objects.requireNonNull(placement);
        if (this.placement != placement) {
            this.placement = placement;
            geometryChanged();
        }
    }
    public List<EdgeRange> getEdgeRanges() { return edgeRanges; }
    public void setEdgeRanges(List<EdgeRange> ranges) {
        edgeRanges = List.copyOf(ranges);
        placement = Placement.CUSTOM;
        geometryChanged();
    }
    public Color getShadeColor() { return shadeColor; }
    public void setShadeColor(Color color) {
        shadeColor = Objects.requireNonNull(color);
        repaint();
    }
    public Insets getContentPadding() { return (Insets) contentPadding.clone(); }
    public void setContentPadding(Insets padding) {
        Objects.requireNonNull(padding);
        if (padding.top < 0 || padding.left < 0 || padding.bottom < 0
                || padding.right < 0) {
            throw new IllegalArgumentException(
                    "El espacio interior no puede ser negativo.");
        }
        contentPadding = (Insets) padding.clone();
        revalidate();
        repaint();
    }
    public Rectangle getBodyBounds() { return new Rectangle(geometry().body()); }
    public Rectangle getContentBounds() {
        return PixelatedPanelGeometry.content(geometry(), blockSize, contentPadding);
    }

    @Override public Insets getInsets() {
        // The superclass can ask for insets before our field initializers have run.
        if (blockSize == 0 || contentPadding == null) return new Insets(0, 0, 0, 0);
        if (getWidth() == 0 || getHeight() == 0) {
            var sides = PixelatedPanelGeometry.affected(placement, edgeRanges);
            return new Insets(2 * blockSize + contentPadding.top
                    + (sides.contains(Side.TOP) ? blockSize : 0), 2 * blockSize
                    + contentPadding.left + (sides.contains(Side.LEFT) ? blockSize : 0),
                    2 * blockSize + contentPadding.bottom + (sides.contains(Side.BOTTOM)
                    ? blockSize : 0), 2 * blockSize + contentPadding.right
                    + (sides.contains(Side.RIGHT) ? blockSize : 0));
        }
        Rectangle content = getContentBounds();
        return new Insets(content.y, content.x, getHeight() - content.y - content.height,
                getWidth() - content.x - content.width);
    }

    @Override public Insets getInsets(Insets target) {
        Insets source = getInsets();
        if (target == null) return source;
        target.set(source.top, source.left, source.bottom, source.right);
        return target;
    }

    @Override public Dimension getPreferredSize() {
        Dimension size = super.getPreferredSize();
        return isPreferredSizeSet() ? size : new Dimension(Math.max(16 * blockSize,
                size.width), Math.max(12 * blockSize, size.height));
    }

    @Override public boolean contains(int x, int y) {
        return x >= 0 && y >= 0 && x < getWidth() && y < getHeight()
                && geometry().hit().contains(x, y);
    }

    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_OFF);
            var geometry = geometry();
            g.setColor(getBackground());
            g.fill(geometry.fill());
            g.setColor(shadeColor);
            for (Rectangle face : geometry.lowerFaces()) g.fill(face);
        } finally { g.dispose(); }
    }

    @Override protected void paintChildren(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.clip(getContentBounds());
            super.paintChildren(g);
        } finally { g.dispose(); }
    }

    private PixelatedPanelGeometry.Geometry geometry() {
        if (cached == null || cachedWidth != getWidth() || cachedHeight != getHeight()) {
            cached = PixelatedPanelGeometry.build(getWidth(), getHeight(),
                    blockSize, seed, placement, edgeRanges);
            cachedWidth = getWidth();
            cachedHeight = getHeight();
        }
        return cached;
    }

    private void geometryChanged() {
        cached = null;
        revalidate();
        repaint();
    }
}
