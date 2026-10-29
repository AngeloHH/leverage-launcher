package dev.leverage.http;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

public final class RateLimiter {
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public RateLimiter(Clock clock) {
        this.clock = clock;
    }

    public boolean allow(String key, int maximum, Duration duration) {
        Instant now = clock.instant();
        Window window = windows.compute(key, (ignored, current) -> {
            if (current == null || !current.endsAt().isAfter(now)) {
                return new Window(1, now.plus(duration));
            }
            return new Window(current.count() + 1, current.endsAt());
        });
        return window.count() <= maximum;
    }

    private record Window(int count, Instant endsAt) { }
}
