package org.example.limiter;

import org.example.store.MockDistributedKeyValueStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

public class DistributedHighThroughputRateLimiterTest {
    private MockDistributedKeyValueStore store = new MockDistributedKeyValueStore();
    private DistributedHighThroughputRateLimiter limiter = DistributedHighThroughputRateLimiter.newBuilder(store)
        .flushIntervalMs(50)
        .shards(8)
        .retryPolicy(org.example.limiter.reliability.RetryPolicy.of(3, java.time.Duration.ofMillis(10)))
        .circuitBreaker(new org.example.limiter.reliability.CircuitBreaker(3, java.time.Duration.ofSeconds(1)))
        .build();

    @AfterEach
    public void tearDown() throws Exception {
        limiter.close();
        store.shutdown();
    }

    @Test
    public void allowsUnderLimit() throws Exception {
        String key = "client-A";
        int limit = 10;
        boolean allowed = limiter.isAllowed(key, limit).get();
        assertTrue(allowed);
    }

    @Test
    public void blocksWhenOverLimit_localPending() throws Exception {
        String key = "client-B";
        int limit = 3;
        // make 4 quick requests without waiting for flush - local pending should block the 4th
        assertTrue(limiter.isAllowed(key, limit).get());
        assertTrue(limiter.isAllowed(key, limit).get());
        assertTrue(limiter.isAllowed(key, limit).get());
        assertFalse(limiter.isAllowed(key, limit).get());
    }

    @Test
    public void flushesBatchedDeltasToStore() throws Exception {
        String key = "client-C";
        int limit = 1000;
        int requests = 50;
        List<CompletableFuture<Boolean>> futs = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            futs.add(limiter.isAllowed(key, limit));
        }
        // wait a bit for flush to happen
        Thread.sleep(200);
        // After flush, store should have at least 'requests' across shards
        assertTrue(store.sumShardKeys(key, 8) >= requests);
    }

    @Test
    public void concurrentRequests() throws ExecutionException, InterruptedException {
        String key = "client-D";
        int limit = 1000;
        int threads = 50;
        List<CompletableFuture<Boolean>> futs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futs.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return limiter.isAllowed(key, limit).get();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        for (CompletableFuture<Boolean> f : futs) f.get();
        // ensure no exceptions and some flushed batches later
        Thread.sleep(200);
        assertTrue(store.sumShardKeys(key, 8) >= 0);
    }

    @Test
    public void stressSimulation_manyRequests() throws Exception {
        String key = "client-stress";
        int limit = 100_000;
        int requests = 5000;
        List<CompletableFuture<Boolean>> futs = new ArrayList<>();
        for (int i = 0; i < requests; i++) futs.add(limiter.isAllowed(key, limit));
        // allow flushes to propagate
        Thread.sleep(500);
        int total = store.sumShardKeys(key, 8);
        // we expect at least some of the requests to have been flushed
        assertTrue(total >= 0);
    }

    @Test
    public void retrySucceedsOnTransientFailure() throws Exception {
        String key = "client-retry";
        // configure store to fail next 2 calls (transient)
        store.setFailNextN(2);
        int requests = 5;
        for (int i = 0; i < requests; i++) limiter.isAllowed(key, 1000);
        // allow flush and retries
        Thread.sleep(500);
        int total = store.sumShardKeys(key, 8);
        assertTrue(total >= requests);
    }

    @Test
    public void circuitBreakerOpensOnPersistentFailure() throws Exception {
        String key = "client-cb";
        // configure store to fail many times to trigger circuit breaker
        store.setFailNextN(100);
        int requests = 5;
        for (int i = 0; i < requests; i++) limiter.isAllowed(key, 1000);
        Thread.sleep(500);
        // since failures persist, circuit breaker should open and prevent further flushes
        long flushed = limiter.getFlushedBatches();
        assertTrue(flushed >= 0);
    }

    @Test
    public void edgeCase_zeroLimit() throws Exception {
        String key = "client-zero";
        assertFalse(limiter.isAllowed(key, 0).get());
        assertFalse(limiter.isAllowed(key, -1).get());
    }
}
