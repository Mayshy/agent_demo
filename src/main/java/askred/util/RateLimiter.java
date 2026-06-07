package askred.util;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple token-bucket rate limiter for API calls.
 * Thread-safe, uses wall-clock time.
 */
public class RateLimiter {

    private final long intervalNanos;
    private final int maxBurst;
    private final AtomicLong nextAvailableNanos;

    /**
     * @param permitsPerMinute maximum permits per minute
     * @param maxBurst         maximum burst size (permits that can accumulate)
     */
    public RateLimiter(int permitsPerMinute, int maxBurst) {
        this.intervalNanos = TimeUnit.MINUTES.toNanos(1) / permitsPerMinute;
        this.maxBurst = maxBurst;
        this.nextAvailableNanos = new AtomicLong(System.nanoTime());
    }

    /**
     * Convenience: permitsPerMinute with burst = 1 (one-at-a-time, evenly spaced)
     */
    public RateLimiter(int permitsPerMinute) {
        this(permitsPerMinute, 1);
    }

    /**
     * Block until a permit is available, then acquire it.
     */
    public void acquire() {
        long now = System.nanoTime();
        long next;

        do {
            next = nextAvailableNanos.get();
            long sleepTime = next - now;
            if (sleepTime > 0) {
                try {
                    TimeUnit.NANOSECONDS.sleep(sleepTime);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Rate limiter interrupted", e);
                }
                now = System.nanoTime();
                // Re-read after sleep since time has advanced
                next = nextAvailableNanos.get();
            }
        } while (!nextAvailableNanos.compareAndSet(next, Math.max(now, next) + intervalNanos));
    }
}
