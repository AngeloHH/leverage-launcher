package dev.leverage.ui;

import com.google.gson.JsonParser;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.geom.Area;
import java.awt.geom.PathIterator;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// Integer cell geometry shared by painting, hit testing and child layout. No raster
// assets.
final class PixelatedPanelGeometry {
    static final int COLUMNS = 6;
    static final List<List<String>> PATTERNS = loadPatterns();

    record Cell(int x, int y) { }
    record Patch(PixelatedPanel.Side side, int start, int length,
            long seed, boolean reversed) { }
    record Geometry(Rectangle body, Area fill, Area hit, List<Rectangle> lowerFaces,
            Set<Cell> additions, Set<Cell> cuts, List<Patch> patches,
            Set<PixelatedPanel.Side> affected, int columns, int rows) { }

    private PixelatedPanelGeometry() { }

    static List<String> pattern(long seed) {
        return PATTERNS.get((int) Math.floorMod(seed, (long) PATTERNS.size()));
    }

    static Geometry build(int width, int height, int unit, long seed,
            PixelatedPanel.Placement placement, List<PixelatedPanel.EdgeRange> ranges) {
        int columns = Math.max(0, (width - 24) / unit - 4);
        int rows = Math.max(0, (height - 24) / unit - 4);
        Rectangle body = new Rectangle((width - columns * unit) / 2,
                (height - rows * unit) / 2, columns * unit, rows * unit);
        Set<Cell> additions = new LinkedHashSet<>(), cuts = new LinkedHashSet<>();
        List<Patch> patches = new ArrayList<>();
        Set<PixelatedPanel.Side> affected = affected(placement, ranges);
        if (columns > 0 && rows > 0) {
            switch (placement) {
                case NONE -> { }
                case BOTTOM_LEFT -> patches.add(new Patch(PixelatedPanel.Side.BOTTOM,
                        0, Math.min(COLUMNS, columns), seed, false));
                case BOTTOM_CORNERS -> {
                    int length = Math.min(COLUMNS, columns / 2);
                    patches.add(new Patch(PixelatedPanel.Side.BOTTOM,
                            0, length, seed, false));
                    patches.add(new Patch(PixelatedPanel.Side.BOTTOM,
                            columns - 1, length, seed + 3, true));
                }
                case OPPOSITE_CORNERS -> {
                    patches.add(new Patch(PixelatedPanel.Side.BOTTOM, 0,
                            Math.min(COLUMNS, columns), seed, false));
                    patches.add(new Patch(PixelatedPanel.Side.TOP, columns - 1,
                            Math.min(COLUMNS, columns), seed + 3, true));
                }
                case RIGHT -> full(patches, PixelatedPanel.Side.RIGHT, rows, seed);
                case SIDES -> {
                    full(patches, PixelatedPanel.Side.LEFT, rows, seed + 3);
                    full(patches, PixelatedPanel.Side.RIGHT, rows, seed);
                }
                case ALL -> {
                    full(patches, PixelatedPanel.Side.TOP, columns, seed + 7);
                    full(patches, PixelatedPanel.Side.RIGHT, rows, seed);
                    full(patches, PixelatedPanel.Side.BOTTOM, columns, seed);
                    full(patches, PixelatedPanel.Side.LEFT, rows, seed + 3);
                }
                case CUSTOM -> {
                    for (var range : ranges) {
                        int count = horizontal(range.side()) ? columns : rows;
                        int from = (int) Math.floor(count * range.start());
                        int to = (int) Math.ceil(count * range.end());
                        if (range.start() == range.end()) continue;
                        for (int at = 0; at < to - from; at += COLUMNS) {
                            patches.add(new Patch(range.side(), range.reversed() ? to - 1
                                    - at : from + at, Math.min(COLUMNS, to - from - at),
                                    seed + (at / COLUMNS) * 5L, range.reversed()));
                        }
                    }
                }
            }
            for (var patch : patches) {
                List<String> data = pattern(patch.seed());
                for (int u = 0; u < patch.length(); u++) {
                    int tangent = patch.reversed() ? patch.start()
                            - u : patch.start() + u;
                    for (int v = -1; v <= 1; v++) {
                        Cell cell = switch (patch.side()) {
                            case BOTTOM -> new Cell(tangent, rows + v);
                            case TOP -> new Cell(tangent, -1 - v);
                            case RIGHT -> new Cell(columns + v, tangent);
                            case LEFT -> new Cell(-1 - v, tangent);
                        };
                        char bit = data.get(v + 1).charAt(u);
                        if (v == -1 && bit == '0') cuts.add(cell);
                        if (v >= 0 && bit == '1') additions.add(cell);
                    }
                }
            }
        }
        // Preserve the approved diagonal contacts; do not flood-fill away cell islands.
        Area fill = columns > 0 && rows > 0 ? new Area(body) : new Area();
        for (Cell cell : additions) fill.add(new Area(rectangle(cell, body, unit)));
        for (Cell cell : cuts) fill.subtract(new Area(rectangle(cell, body, unit)));
        List<Rectangle> faces = lowerFaces(fill);
        Area hit = new Area(fill);
        for (Rectangle face : faces) hit.add(new Area(face));
        return new Geometry(body, fill, hit, List.copyOf(faces),
                Set.copyOf(additions), Set.copyOf(cuts), List.copyOf(patches),
                Set.copyOf(affected), columns, rows);
    }

    private static Rectangle rectangle(Cell cell, Rectangle body, int unit) {
        return new Rectangle(body.x + cell.x() * unit,
                body.y + cell.y() * unit, unit, unit);
    }

    private static void full(List<Patch> result,
            PixelatedPanel.Side side, int count, long seed) {
        for (int at = 0; at < count; at += COLUMNS) {
            result.add(new Patch(side, at, Math.min(COLUMNS, count - at),
                    seed + (at / COLUMNS) * 5L, false));
        }
    }

    static boolean horizontal(PixelatedPanel.Side side) {
        return side == PixelatedPanel.Side.TOP || side == PixelatedPanel.Side.BOTTOM;
    }

    static Set<PixelatedPanel.Side> affected(PixelatedPanel.Placement placement,
            List<PixelatedPanel.EdgeRange> ranges) {
        return switch (placement) {
            case NONE -> EnumSet.noneOf(PixelatedPanel.Side.class);
            case BOTTOM_LEFT, BOTTOM_CORNERS -> EnumSet.of(PixelatedPanel.Side.BOTTOM);
            case OPPOSITE_CORNERS -> EnumSet.of(PixelatedPanel.Side.TOP,
                    PixelatedPanel.Side.BOTTOM);
            case RIGHT -> EnumSet.of(PixelatedPanel.Side.RIGHT);
            case SIDES -> EnumSet.of(PixelatedPanel.Side.LEFT, PixelatedPanel.Side.RIGHT);
            case ALL -> EnumSet.allOf(PixelatedPanel.Side.class);
            case CUSTOM -> {
                var sides = EnumSet.noneOf(PixelatedPanel.Side.class);
                for (var range : ranges) if (range.end() > range.start()) sides.add(
                        range.side());
                yield sides;
            }
        };
    }

    static Rectangle content(Geometry geometry, int unit, Insets padding) {
        Rectangle body = geometry.body();
        var sides = geometry.affected();
        int left = body.x + padding.left + (sides.contains(
                PixelatedPanel.Side.LEFT) ? unit : 0);
        int top = body.y + padding.top + (sides.contains(
                PixelatedPanel.Side.TOP) ? unit : 0);
        int right = body.x + body.width - padding.right - (sides.contains(
                PixelatedPanel.Side.RIGHT) ? unit : 0);
        int bottom = body.y + body.height - padding.bottom
                - (sides.contains(PixelatedPanel.Side.BOTTOM) ? unit : 0);
        if (left > right || top > bottom || geometry.fill().isEmpty()) {
            return new Rectangle(body.x + body.width / 2, body.y + body.height / 2, 0, 0);
        }
        return new Rectangle(left, top, right - left, bottom - top);
    }

    private static List<Rectangle> lowerFaces(Area shape) {
        List<Rectangle> result = new ArrayList<>();
        double[] coordinates = new double[6];
        double startX = 0, startY = 0, x = 0, y = 0;
        for (PathIterator path = shape.getPathIterator(null);
                !path.isDone(); path.next()) {
            int kind = path.currentSegment(coordinates);
            if (kind == PathIterator.SEG_MOVETO) {
                startX = x = coordinates[0];
                startY = y = coordinates[1];
            } else if (kind == PathIterator.SEG_LINETO
                    || kind == PathIterator.SEG_CLOSE) {
                double endX = kind == PathIterator.SEG_CLOSE ? startX : coordinates[0];
                double endY = kind == PathIterator.SEG_CLOSE ? startY : coordinates[1];
                if (y == endY && x != endX) {
                    double midX = (x + endX) / 2;
                    if (shape.contains(midX, y - .5) && !shape.contains(midX, y + .5)) {
                        result.add(new Rectangle((int) Math.min(x, endX),
                                (int) y - 1, (int) Math.abs(endX - x), 3));
                    }
                }
                x = endX;
                y = endY;
            }
        }
        return result;
    }

    private static List<List<String>> loadPatterns() {
        String path = "/dev/leverage/ui/pixelated-patterns.json";
        try (var stream = PixelatedPanelGeometry.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException(
                    "Faltan los datos del contenedor pixelado.");
            var root = JsonParser.parseReader(new InputStreamReader(stream,
                    StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("columns").getAsInt() != COLUMNS)
                    throw new IllegalStateException("Cuadrícula no válida.");
            List<List<String>> result = new ArrayList<>();
            for (var layout : root.getAsJsonArray("layouts")) {
                List<String> rows = new ArrayList<>();
                for (var row : layout.getAsJsonArray()) {
                    String bits = row.getAsString();
                    if (!bits.matches("[01]{6}")) throw new IllegalStateException(
                            "Celdas no válidas.");
                    rows.add(bits);
                }
                if (rows.size() != 3) throw new IllegalStateException(
                        "Número de filas no válido.");
                result.add(List.copyOf(rows));
            }
            if (result.size() != 16) throw new IllegalStateException(
                    "Faltan patrones aprobados.");
            return List.copyOf(result);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(
                    "No se pudieron cargar los patrones pixelados.", exception);
        }
    }
}
