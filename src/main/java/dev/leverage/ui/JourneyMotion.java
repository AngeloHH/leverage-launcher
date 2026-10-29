package dev.leverage.ui;

// Pure sampling shared by native scene transitions and deterministic rendering tests.
final class JourneyMotion {
    record Vertical(double outgoingY, double incomingY, double navigationY) { }
    record Horizontal(double outgoingX, double incomingX) { }
    private JourneyMotion() { }
    static double ease(double progress) {
        double p = Math.max(0, Math.min(1, progress));
        return p < .5 ? 4 * p * p * p : 1 - Math.pow(-2 * p + 2, 3) / 2;
    }
    static Vertical vertical(int pageHeight, int oldNav, int newNav,
            boolean reverse, double progress) {
        double e = ease(progress), direction = reverse ? -1 : 1;
        return new Vertical(-direction * pageHeight * e,
                direction * pageHeight * (1 - e), oldNav + (newNav - oldNav) * e);
    }
    static Horizontal horizontal(int width, int seamWidth,
            boolean reverse, double progress) {
        double e = ease(progress), direction = reverse ? -1 : 1,
                distance = width + seamWidth;
        return new Horizontal(-direction * distance * e, direction * distance * (1 - e));
    }
}
