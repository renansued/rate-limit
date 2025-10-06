package com.codurance.limiter.metrics;

import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Prometheus-backed MetricPublisher. Uses Prometheus client to expose counters/gauges.
 * This publisher is intended for environments with a Prometheus scrape.
 */
public class PrometheusMetricPublisher implements MetricPublisher {
  private static final Logger logger = LoggerFactory.getLogger(PrometheusMetricPublisher.class);
  private final CollectorRegistry registry;
  private final Counter flushedBatches;
  private final Gauge pendingBufferSize;
  private final Counter storeFailures;
  private final Gauge circuitBreakerOpen;
  private final AtomicLong bufferedFlushed = new AtomicLong(0);
  private final AtomicLong bufferedStoreFailures = new AtomicLong(0);
  private final ScheduledExecutorService scheduler;

  public void close() {
    try {
      scheduler.shutdown();
      scheduler.awaitTermination(1, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    // flush remaining
    long f = bufferedFlushed.getAndSet(0);
    if (f > 0) flushedBatches.inc(f);
    long s = bufferedStoreFailures.getAndSet(0);
    if (s > 0) storeFailures.inc(s);
  }

  public PrometheusMetricPublisher(CollectorRegistry registry, String namespace) {
    this.registry = registry == null ? CollectorRegistry.defaultRegistry : registry;
    this.flushedBatches = Counter.build()
        .namespace(namespace)
        .name("flushed_batches_total")
        .help("Number of flushed batches sent to store")
        .register(this.registry);

    this.pendingBufferSize = Gauge.build()
        .namespace(namespace)
        .name("pending_buffer_size")
        .help("Local pending buffer size (approx)")
        .register(this.registry);

    this.storeFailures = Counter.build()
        .namespace(namespace)
        .name("store_failures_total")
        .help("Number of store failures observed")
        .register(this.registry);

    this.circuitBreakerOpen = Gauge.build()
        .namespace(namespace)
        .name("circuit_breaker_open")
        .help("1 if circuit breaker is open, 0 otherwise")
        .register(this.registry);
    this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "prometheus-metric-flusher");
      t.setDaemon(true);
      return t;
    });
    // periodic flush from buffers to actual Prometheus counters (1s)
    this.scheduler.scheduleAtFixedRate(this::flushBuffers, 1, 1, TimeUnit.SECONDS);
  }

  @Override
  public void incrementCounter(String name, long delta) {
    switch (name) {
      case "FlushedBatches":
        bufferedFlushed.addAndGet(delta);
        break;
      case "StoreFailures":
        bufferedStoreFailures.addAndGet(delta);
        break;
      default:
        // no-op for unknown counters
    }
  }

  @Override
  public void gauge(String name, double value) {
    switch (name) {
      case "PendingBufferSize":
        pendingBufferSize.set(value);
        break;
      case "CircuitBreakerOpen":
        circuitBreakerOpen.set(value);
        break;
      default:
        // no-op
    }
  }

  private void flushBuffers() {
    try {
      long f = bufferedFlushed.getAndSet(0);
      if (f > 0) flushedBatches.inc(f);
      long s = bufferedStoreFailures.getAndSet(0);
      if (s > 0) storeFailures.inc(s);
    } catch (Throwable t) {
      logger.warn("Error flushing Prometheus buffers", t);
    }
  }
}
