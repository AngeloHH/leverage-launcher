package dev.leverage.ui;

import java.awt.*;
import java.util.*;
import java.util.List;

// Keep every cell on the same 32 px grid, including internal openings and islands.
final class NewsBlockEdge {
    static final int UNIT = 32;
    private static final String[][] LAYOUTS = {
        { "101011", "110100", "000010" }, { "101011", "010110", "100000" },
        { "101011", "010110", "000001" }, { "101101", "110010", "001000" },
        { "101101", "110010", "000100" }, { "101101", "110010", "000001" },
        { "101101", "011010", "100000" }, { "101101", "011010", "000001" },
        { "101101", "010110", "100000" }, { "101101", "010110", "000001" },
        { "101101", "010011", "100000" }, { "101101", "010011", "001000" },
        { "101101", "010011", "000100" }, { "110101", "011010", "100000" },
        { "110101", "011010", "000001" }, { "110101", "001011", "010000" }
    };
    private static final List<boolean[][]> TEMPLATES = templates();

    private static boolean valid(boolean[] a, boolean[] b) {
        return !(a[0] && b[0] && a[1] && b[1]) && !(a[1] == b[1]
                && a[1] == a[2] && a[1] == b[2]);
    }

    private static boolean[] column(boolean[][] rows, int at) {
        return new boolean[] { rows[0][at], rows[1][at], rows[2][at] };
    }

    private static List<boolean[][]> templates() {
        var bases = new LinkedHashSet<String>();
        var middles = new LinkedHashSet<String>();
        var islands = new LinkedHashSet<String>();
        for (var layout : LAYOUTS) {
            bases.add(layout[0]);
            middles.add(layout[1]);
            islands.add(layout[2]);
        }
        var result = new ArrayList<boolean[][]>();
        var seen = new HashSet<String>();
        for (String base : bases) for (String middle : middles)
        for (String island : islands) for (int shift = 0; shift < 12; shift += 2) {
            String doubled = "";
            for (char bit : base.toCharArray()) doubled += "" + bit + bit;
            String[] strings = { island.repeat(2), middle.repeat(2), doubled };
            boolean[][] rows = new boolean[3][12];
            for (int r = 0; r < 3; r++) for (int c = 0; c < 12; c++)
                rows[r][c] = strings[r].charAt((c + shift) % 12) == '1';
            boolean good = true;
            for (int c = 0; c < 12; c++) if ((rows[0][c] && rows[1][c])
                    || !valid(column(rows, c), column(rows, (c + 1) % 12))) {
                good = false;
                break;
            }
            String key = Arrays.deepToString(rows);
            if (good && seen.add(key)) result.add(rows);
        }
        return List.copyOf(result);
    }

    static boolean[][] build(int count, int seed) {
        boolean[][] rows = new boolean[3][Math.max(0, count)];
        for (int length = 0, patch = 0; length < count; length += 12, patch++) {
            var eligible = new ArrayList<boolean[][]>();
            for (var template : TEMPLATES) {
                boolean good = true;
                if (length > 0) {
                    var previous = column(rows, length - 1);
                    good = valid(previous, column(template, 0))
                            && (previous[2] || template[2][0])
                            && !(previous[0] && template[0][0]);
                    int tail = 0, head = 0;
                    for (int at = length - 1; at >= 0 && rows[1][at]; at--) tail++;
                    for (int at = 0; at < 12 && template[1][at]; at++) head++;
                    good &= tail + head <= 2;
                }
                if (good) eligible.add(template);
            }
            int hash = seed + (patch + 1) * 0x9e3779b9;
            hash = (hash ^ (hash >>> 16)) * 0x45d9f3b;
            hash ^= hash >>> 16;
            var template = eligible.get(
                    (int)(Integer.toUnsignedLong(hash) % eligible.size()));
            for (int r = 0; r < 3; r++) System.arraycopy(template[r], 0,
                    rows[r], length, Math.min(12, count - length));
        }
        return rows;
    }

    static void paint(Graphics2D g, int x, int y, int width, int seed) {
        int count = (width + UNIT - 1) / UNIT;
        var rows = build(count, seed);
        Graphics2D copy = (Graphics2D) g.create();
        copy.clipRect(x, y, width, 3 * UNIT);
        try {
            copy.setColor(JourneyStyle.PAPER);
            for (int r = 0; r < 3; r++) for (int c = 0; c < count; c++)
                if (rows[r][c]) copy.fillRect(x + c * UNIT, y + r * UNIT, UNIT, UNIT);
            copy.setColor(new Color(40, 38, 35, 56));
            for (int r = 0; r < 2; r++) for (int c = 0; c < count; c++)
                if (rows[r][c] && !rows[r + 1][c])
                    copy.fillRect(x + c * UNIT, y + (r + 1) * UNIT - 3, UNIT, 3);
        } finally { copy.dispose(); }
    }
}
