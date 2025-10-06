package com.codurance.limiter.metrics;

import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal CloudWatch MetricPublisher. This implementation sends individual
 * PutMetricData requests. For production use, consider batching and
 * rate-limiting metric calls to avoid throttling and cost.
 */
public class CloudWatchMetricPublisher implements MetricPublisher {
  private final CloudWatchClient client;
  private final String namespace;
  private static final Logger logger = LoggerFactory.getLogger(CloudWatchMetricPublisher.class);

  public CloudWatchMetricPublisher(CloudWatchClient client, String namespace) {
    this.client = client;
    this.namespace = namespace == null ? "RateLimiter" : namespace;
  }

  @Override
  public void incrementCounter(String name, long delta) {
    // For simplicity, publish a single datum with the delta value.
    publishMetric(name, (double) delta, StandardUnit.COUNT);
  }

  @Override
  public void gauge(String name, double value) {
    publishMetric(name, value, StandardUnit.NONE);
  }

  private void publishMetric(String name, double value, StandardUnit unit) {
    try {
      MetricDatum datum = MetricDatum.builder()
          .metricName(name)
          .value(value)
          .unit(unit)
          .build();
      List<MetricDatum> data = new ArrayList<>();
      data.add(datum);
      PutMetricDataRequest req = PutMetricDataRequest.builder()
          .namespace(this.namespace)
          .metricData(data)
          .build();
      client.putMetricData(req);
    } catch (Throwable t) {
      // best-effort; do not throw from metrics
      logger.warn("Failed to publish CloudWatch metric {}", name, t);
    }
  }
}
