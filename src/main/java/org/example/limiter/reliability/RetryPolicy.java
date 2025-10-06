package org.example.limiter.reliability;

import java.time.Duration;

/**
 * Simple retry policy configuration and helper.
 */
public class RetryPolicy {
    private final int maxAttempts;
    private final Duration backoff;

    public RetryPolicy(int maxAttempts, Duration backoff) {
        this.maxAttempts = maxAttempts;
        this.backoff = backoff;
    }

    public int getMaxAttempts() { return maxAttempts; }
    public Duration getBackoff() { return backoff; }

    public static RetryPolicy of(int attempts, Duration backoff) { return new RetryPolicy(attempts, backoff); }
}
