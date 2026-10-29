package dev.leverage.ui;

// The two independent CSS transitions: left .5s, width .25s, default cubic-bezier ease.
final class TabIndicatorMotion {
    record Bounds(double left, double width) { }
    private TabIndicatorMotion() { }
    static Bounds sample(Bounds start, Bounds target, double milliseconds) {
        return new Bounds(start.left() + (target.left() - start.left()) * ease(
                milliseconds / 500), start.width() + (target.width()
                - start.width()) * ease(milliseconds / 250));
    }
    static double ease(double value) {
        if (value <= 0) return 0;
        if (value >= 1) return 1;
        double low = 0, high = 1;
        for (int i = 0; i < 30; i++) {
            double t = (low + high) / 2, u = 1 - t;
            double x = 3 * u * u * t * .25 + 3 * u * t * t * .25 + t * t * t;
            if (x < value) low = t;
            else high = t;
        }
        double t = (low + high) / 2, u = 1 - t;
        return 3 * u * u * t * .1 + 3 * u * t * t + t * t * t;
    }
}
