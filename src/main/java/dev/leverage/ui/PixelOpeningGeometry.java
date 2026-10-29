package dev.leverage.ui;

import java.awt.Rectangle;
import java.awt.geom.Area;

// Uniform-cell opening used by version stories and the workshop window.
public final class PixelOpeningGeometry {
    public record Geometry(Area area, int cellSize, int columns, int rows) {
        public Geometry { area = new Area(area); }
        @Override public Area area() { return new Area(area); }
    }
    private PixelOpeningGeometry() { }
    public static long seed(String id, long variation) {
        int hash = 0x811c9dc5;
        for (int i = 0; i < id.length(); i++) hash = (hash ^ id.charAt(i)) * 0x01000193;
        return Integer.toUnsignedLong(hash ^ (int) variation);
    }
    public static Geometry build(int width, int height, int requestedCell, long seed) {
        if (width <= 0 || height <= 0) return new Geometry(new Area(), 1, 0, 0);
        int unit = Math.max(1, Math.min(Math.max(12, Math.min(80,
                requestedCell)), Math.min(width / 8, height / 6)));
        int cols = width / unit, rows = height / unit;
        boolean[][] cells = new boolean[rows][cols];
        fill(cells, 1, 1, cols - 2, rows - 2);
        int topStart = Math.min(2 + (int)(seed % 3), cols - 5);
        fill(cells, topStart, 0, cols - 2 - topStart, 1);
        fill(cells, cols - 1, 2, 1, rows - 3);
        int bottomStart = 2 + (int)((seed >>> 12) % 2);
        fill(cells, bottomStart, rows - 1, Math.max(2, cols - 4 - bottomStart), 1);
        fill(cells, 0, rows - 2, 2, 1);
        if (cols >= 8 && rows >= 6) {
            int min = Math.max(2, topStart), max = cols - 4;
            int x = min + (int)((seed >>> 3) % (max - min + 1));
            cells[1][x] = false;
            cells[1][x + 1] = false;
        }
        if (cols >= 10 && rows >= 8) {
            int y = rows - 4 - (int)((seed >>> 5) % 2);
            cells[y][2] = false;
            cells[y + 1][2] = false;
        }
        int ox = (width - cols * unit) / 2, oy = (height - rows * unit) / 2;
        Area area = new Area();
        for (int y = 0; y < rows; y++) for (int x = 0; x < cols;) {
            if (!cells[y][x]) {
                x++;
                continue;
            }
            int start = x;
            while (x < cols && cells[y][x]) x++;
            area.add(new Area(new Rectangle(ox + start * unit,
                    oy + y * unit, (x - start) * unit, unit)));
        }
        return new Geometry(area, unit, cols, rows);
    }
    private static void fill(boolean[][] cells, int x, int y, int width, int height) {
        for (int row = Math.max(0, y); row<Math.min(cells.length, y + height);
                row++) for (int col = Math.max(0, x); col<Math.min(cells[row].length,
                x + width); col++) cells[row][col] = true;
    }
}
