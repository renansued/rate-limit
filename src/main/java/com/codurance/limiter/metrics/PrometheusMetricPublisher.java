package com.codurance.limiter.metrics;

import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;

/**
 * Prometheus-backed MetricPublisher. Uses Prometheus client to expose counters/gauges.
 * This publisher is intended for environments with a Prometheus scrape.
 */
public class PrometheusMetricPublisher implements MetricPublisher {
  private final CollectorRegistry registry;
  private final Counter flushedBatches;
  private final Gauge pendingBufferSize;
  private final Counter storeFailures;
  private final Gauge circuitBreakerOpen;

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
  }

  @Override
  public void incrementCounter(String name, long delta) {
    switch (name) {
      case "FlushedBatches":
        flushedBatches.inc(delta);
        break;
      case "StoreFailures":
        storeFailures.inc(delta);
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
}
