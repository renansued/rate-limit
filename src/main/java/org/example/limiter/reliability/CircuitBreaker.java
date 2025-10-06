package org.example.limiter.reliability;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple circuit breaker: counts consecutive failures and opens after threshold.
 */
public class CircuitBreaker {
    private final int failureThreshold;
    private final Duration openDuration;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong openUntil = new AtomicLong(0);

    public CircuitBreaker(int failureThreshold, Duration openDuration) {
        this.failureThreshold = failureThreshold;
        this.openDuration = openDuration;
    }

    public boolean allowRequest() {
        long now = System.currentTimeMillis();
        long until = openUntil.get();
        if (now < until) return false;
        return true;
    }

    public void recordSuccess() { consecutiveFailures.set(0); }

    public void recordFailure() {
        int f = consecutiveFailures.incrementAndGet();
        if (f >= failureThreshold) {
            long until = System.currentTimeMillis() + openDuration.toMillis();
            openUntil.set(until);
            consecutiveFailures.set(0);
        }
    }
}
