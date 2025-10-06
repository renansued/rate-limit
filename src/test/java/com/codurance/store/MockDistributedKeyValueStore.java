package com.codurance.store;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple in-memory mock of DistributedKeyValueStore for unit tests.
 */
public class MockDistributedKeyValueStore implements DistributedKeyValueStore {
    private final ConcurrentHashMap<String, AtomicInteger> map = new ConcurrentHashMap<>();
    private final ScheduledExecutorService ttlScheduler = Executors.newScheduledThreadPool(1);
    // failure simulation
    private volatile int failNextN = 0;

    public void setFailNextN(int n) {
        this.failNextN = n;
    }

    @Override
    public CompletableFuture<Integer> incrementByAndExpire(String key, int delta, int expirationSeconds)
            throws Exception {
        // Return future to simulate networked async call
        CompletableFuture<Integer> fut = new CompletableFuture<>();

        AtomicInteger ai = map.computeIfAbsent(key, k -> {
            AtomicInteger newly = new AtomicInteger(0);
            ttlScheduler.schedule(() -> map.remove(k), expirationSeconds, TimeUnit.SECONDS);
            return newly;
        });

        int result = ai.addAndGet(delta);

        // simulate a transient failure if configured
        if (failNextN > 0) {
            failNextN--;
            CompletableFuture.delayedExecutor(5, TimeUnit.MILLISECONDS)
                    .execute(() -> fut.completeExceptionally(new RuntimeException("simulated transient failure")));
            return fut;
        }

        // simulate async completion with a tiny delay
        CompletableFuture.delayedExecutor(5, TimeUnit.MILLISECONDS).execute(() -> fut.complete(result));
        return fut;
    }

    public int get(String key) {
        AtomicInteger ai = map.get(key);
        return ai == null ? 0 : ai.get();
    }

    /**
     * Sum multiple shard keys (helper for tests)
     */
    public int sumShardKeys(String logicalKey, int shards) {
        int sum = 0;
        for (int i = 0; i < shards; i++) {
            String k = logicalKey + "#shard#" + i;
            sum += get(k);
        }
        return sum;
    }

    public void shutdown() {
        ttlScheduler.shutdownNow();
    }
}
