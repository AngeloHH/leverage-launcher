package dev.leverage.http;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {
    @Test
    void blocksAfterLimitAndReopensNextWindow() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        RateLimiter limiter = new RateLimiter(clock);

        assertTrue(limiter.allow("login:127.0.0.1", 2, Duration.ofMinutes(1)));
        assertTrue(limiter.allow("login:127.0.0.1", 2, Duration.ofMinutes(1)));
        assertFalse(limiter.allow("login:127.0.0.1", 2, Duration.ofMinutes(1)));

        clock.advance(Duration.ofMinutes(1));
        assertTrue(limiter.allow("login:127.0.0.1", 2, Duration.ofMinutes(1)));
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
