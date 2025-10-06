package com.codurance.limiter.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.prometheus.client.CollectorRegistry;

public class PrometheusMetricPublisherTest {
  private PrometheusMetricPublisher publisher;

  @AfterEach
  public void tearDown() {
    if (publisher != null)
      publisher.close();
  }

  @Test
  public void bufferedCountersAreFlushed() throws Exception {
    CollectorRegistry registry = new CollectorRegistry();
    publisher = new PrometheusMetricPublisher(registry, "TestNS");

    // increment buffered counters
    publisher.incrementCounter("FlushedBatches", 5);
    publisher.incrementCounter("StoreFailures", 2);
    publisher.gauge("PendingBufferSize", 42);

    // wait for flush (flush runs every 1s)
    Thread.sleep(1200);

    // read values from registry
    Double flushed = registry.getSampleValue("TestNS_flushed_batches_total");
    Double failures = registry.getSampleValue("TestNS_store_failures_total");
    Double pending = registry.getSampleValue("TestNS_pending_buffer_size");

    assertEquals(5.0, flushed == null ? 0.0 : flushed, 0.0001);
    assertEquals(2.0, failures == null ? 0.0 : failures, 0.0001);
    assertEquals(42.0, pending == null ? 0.0 : pending, 0.0001);
  }
}
