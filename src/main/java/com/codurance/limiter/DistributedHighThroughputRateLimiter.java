package com.codurance.limiter;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.codurance.store.DistributedKeyValueStore;
import com.codurance.limiter.reliability.RetryPolicy;
import com.codurance.limiter.reliability.CircuitBreaker;
import com.codurance.limiter.metrics.MetricPublisher;
import com.codurance.limiter.metrics.NoOpMetricPublisher;

/**
 * Distributed high-throughput rate limiter (sharded counters + batching).
 *
 * Improvements over the simple design:
 * - Sharded counters per logical key to distribute load across the backing
 * store and
 * avoid hot-partition issues. Each logical key is split into N shard keys
 * ("{logicalKey}#shard#{id}") and requests are distributed across shards.
 * - Builder pattern for configuration and clear defaults.
 * - Single background flusher that batches local deltas to the store to reduce
 * network calls.
 * - Non-blocking isAllowed that makes a local decision using last-known global
 * shard counts
 * plus local pending deltas. This is eventually consistent and allows slight
 * temporary
 * exceedances (as required by the spec).
 *
 * Design pattern: Strategy / Builder - the limiter can be extended with
 * different
 * sharding or sampling strategies by replacing the ShardStrategy
 * implementation.
 */
public class DistributedHighThroughputRateLimiter implements AutoCloseable {
  private final DistributedKeyValueStore store;
  private final ScheduledExecutorService scheduler;
  private final long flushIntervalMs;
  private final int shards;
  private final int expirationSeconds;

  // Pending increments per store key (logicalKey#shard#id)
  private final ConcurrentHashMap<String, AtomicInteger> pending = new ConcurrentHashMap<>();

  // Last known global value per store key as returned by the store
  private final ConcurrentHashMap<String, AtomicInteger> lastKnownGlobal = new ConcurrentHashMap<>();

  private final AtomicLong flushedBatches = new AtomicLong(0);
  private final RetryPolicy retryPolicy;
  private final CircuitBreaker circuitBreaker;
  private final ExecutorService workerPool;
  private final MetricPublisher metricPublisher;

  private DistributedHighThroughputRateLimiter(Builder builder) {
    this.store = Objects.requireNonNull(builder.store, "store");
    this.flushIntervalMs = builder.flushIntervalMs;
    this.shards = builder.shards;
    this.expirationSeconds = builder.expirationSeconds;
  this.retryPolicy = builder.retryPolicy;
  this.circuitBreaker = builder.circuitBreaker;
  this.metricPublisher = builder.metricPublisher == null ? new NoOpMetricPublisher() : builder.metricPublisher;

    this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "rate-limiter-flusher");
      t.setDaemon(true);
      return t;
    });
    // Worker pool for async flush tasks; bounded pool helps control resource usage
    this.workerPool = Executors.newFixedThreadPool(Math.max(1, builder.workerPoolSize), r -> {
      Thread t = new Thread(r, "rate-limiter-worker");
      t.setDaemon(true);
      return t;
    });
    this.scheduler.scheduleAtFixedRate(this::flushAllSafely, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
  }

  public static Builder newBuilder(DistributedKeyValueStore store) {
    return new Builder(store);
  }

  /**
   * Check whether a request is allowed under limit for the given logical key.
   * This method is non-blocking and returns immediately with a CompletableFuture
   * containing
   * the decision. It increments a local pending counter for a randomly chosen
   * shard to
   * distribute load.
   */
  public CompletableFuture<Boolean> isAllowed(String key, int limit) {
    Objects.requireNonNull(key, "logicalKey");
    if (limit <= 0)
      return CompletableFuture.completedFuture(false);

    // Choose a shard for this request to spread load
    int shardId = ThreadLocalRandom.current().nextInt(shards);
    String shardKey = toShardKey(key, shardId);

    // Increment local pending for this shard
    AtomicInteger shardPending = pending.computeIfAbsent(shardKey, k -> new AtomicInteger(0));
    shardPending.incrementAndGet();

    // Compute estimated total: sum lastKnownGlobal across shards + sum pending
    // across shards
    int known = 0;
    int localPending = 0;
    for (int i = 0; i < shards; i++) {
      String sk = toShardKey(key, i);
      AtomicInteger lg = lastKnownGlobal.get(sk);
      if (lg != null)
        known += lg.get();
      AtomicInteger p = pending.get(sk);
      if (p != null)
        localPending += p.get();
    }

    boolean allowed = (known + localPending) <= limit;
    return CompletableFuture.completedFuture(allowed);
  }

  private String toShardKey(String logicalKey, int shardId) {
    return logicalKey + "#shard#" + shardId;
  }

  private void flushAllSafely() {
    try {
      flushAll();
    } catch (Throwable t) {
      t.printStackTrace();
    }
  }

  private void flushAll() {
    for (var entry : pending.entrySet()) {
      String shardKey = entry.getKey();
      AtomicInteger counter = entry.getValue();
      int delta = counter.getAndSet(0);
      if (delta <= 0)
        continue;

      final int deltaFinal = delta;

      // If circuit breaker present and not allowing requests, requeue the delta
      if (circuitBreaker != null && !circuitBreaker.allowRequest()) {
        counter.addAndGet(deltaFinal);
        continue;
      }

      final RetryPolicy rp = this.retryPolicy;
      final AtomicInteger counterRef = counter;

      // Perform the store call asynchronously on a bounded worker pool with retries
      workerPool.submit(() -> {
        int attempts = 0;
        while (true) {
          attempts++;
          try {
            CompletableFuture<Integer> fut = store.incrementByAndExpire(shardKey, deltaFinal, expirationSeconds);
            int val = fut.get();
            if (circuitBreaker != null)
              circuitBreaker.recordSuccess();
            lastKnownGlobal.compute(shardKey, (k, ai) -> {
              if (ai == null)
                return new AtomicInteger(val);
              ai.set(val);
              return ai;
            });
            // metrics: flushed batch
            flushedBatches.incrementAndGet();
            metricPublisher.incrementCounter("FlushedBatches", 1);
            break;
          } catch (Throwable ex) {
            if (circuitBreaker != null)
              circuitBreaker.recordFailure();
            metricPublisher.incrementCounter("StoreFailures", 1);
            // reflect circuit breaker state
            if (circuitBreaker != null)
              metricPublisher.gauge("CircuitBreakerOpen", circuitBreaker.allowRequest() ? 0 : 1);

            if (rp != null && attempts < rp.getMaxAttempts()) {
              try {
                Thread.sleep(rp.getBackoff().toMillis());
              } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                // requeue and stop
                counterRef.addAndGet(deltaFinal);
                break;
              }
              continue;
            } else {
              // Give up and requeue delta
              counterRef.addAndGet(deltaFinal);
              break;
            }
          }
        }
      });

    }
    // emit pending buffer size metric (approx)
    try {
      int totalPending = pending.values().stream().mapToInt(AtomicInteger::get).sum();
      metricPublisher.gauge("PendingBufferSize", totalPending);
    } catch (Throwable t) {
      // ignore metrics failures
    }
  }

  /**
   * Final synchronous flush used during shutdown to reduce lost counters.
   * This method will attempt the same retry logic but in the calling thread
   * so we can block until deltas are sent or requeued.
   */
  private void flushAllSync() {
    for (var entry : pending.entrySet()) {
      String shardKey = entry.getKey();
      AtomicInteger counter = entry.getValue();
      int delta = counter.getAndSet(0);
      if (delta <= 0)
        continue;

      final int deltaFinal = delta;
      if (circuitBreaker != null && !circuitBreaker.allowRequest()) {
        counter.addAndGet(deltaFinal);
        continue;
      }

      int attempts = 0;
      while (true) {
        attempts++;
        try {
          CompletableFuture<Integer> fut = store.incrementByAndExpire(shardKey, deltaFinal, expirationSeconds);
          int val = fut.get();
          if (circuitBreaker != null)
            circuitBreaker.recordSuccess();
          lastKnownGlobal.compute(shardKey, (k, ai) -> {
            if (ai == null)
              return new AtomicInteger(val);
            ai.set(val);
            return ai;
          });
          break;
        } catch (Throwable ex) {
          if (circuitBreaker != null)
            circuitBreaker.recordFailure();
          if (retryPolicy != null && attempts < retryPolicy.getMaxAttempts()) {
            try {
              Thread.sleep(retryPolicy.getBackoff().toMillis());
            } catch (InterruptedException ie) {
              Thread.currentThread().interrupt();
              counter.addAndGet(deltaFinal);
              break;
            }
            continue;
          } else {
            counter.addAndGet(deltaFinal);
            break;
          }
        }
      }

      flushedBatches.incrementAndGet();
    }
  }

  /**
   * Approximate current count across shards for a logical key (last known +
   * pending).
   */
  public int getEstimatedCount(String logicalKey) {
    int known = 0, pendingSum = 0;
    for (int i = 0; i < shards; i++) {
      String sk = toShardKey(logicalKey, i);
      AtomicInteger lg = lastKnownGlobal.get(sk);
      if (lg != null)
        known += lg.get();
      AtomicInteger p = pending.get(sk);
      if (p != null)
        pendingSum += p.get();
    }
    return known + pendingSum;
  }

  public long getFlushedBatches() {
    return flushedBatches.get();
  }

  @Override
  public void close() throws Exception {
    scheduler.shutdown();
    // Do a final synchronous flush to reduce lost counters
    try {
      flushAllSync();
    } catch (Throwable t) {
      // best-effort
      t.printStackTrace();
    }

    scheduler.awaitTermination(1, TimeUnit.SECONDS);
    workerPool.shutdown();
    workerPool.awaitTermination(5, TimeUnit.SECONDS);
  }

  public static class Builder {
    private final DistributedKeyValueStore store;
    private long flushIntervalMs = 100;
    private int shards = 8;
    private int expirationSeconds = 60;
    private RetryPolicy retryPolicy = null;
    private CircuitBreaker circuitBreaker = null;
    private int workerPoolSize = 8;
  private MetricPublisher metricPublisher = null;

    public Builder(DistributedKeyValueStore store) {
      this.store = store;
    }

    public Builder flushIntervalMs(long ms) {
      this.flushIntervalMs = ms;
      return this;
    }

    public Builder shards(int s) {
      this.shards = s;
      return this;
    }

    public Builder expirationSeconds(int s) {
      this.expirationSeconds = s;
      return this;
    }

    public Builder retryPolicy(RetryPolicy rp) {
      this.retryPolicy = rp;
      return this;
    }

    public Builder circuitBreaker(CircuitBreaker cb) {
      this.circuitBreaker = cb;
      return this;
    }

    public DistributedHighThroughputRateLimiter build() {
      return new DistributedHighThroughputRateLimiter(this);
    }

    public Builder metricPublisher(MetricPublisher mp) {
      this.metricPublisher = mp;
      return this;
    }

    public Builder workerPoolSize(int size) {
      this.workerPoolSize = Math.max(1, size);
      return this;
    }
  }
}
