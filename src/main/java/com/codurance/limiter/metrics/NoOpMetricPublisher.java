package com.codurance.limiter.metrics;

/**
 * Hack - No-op metric publisher (default) so limiter can run without CloudWatch dependency.
 */
public class NoOpMetricPublisher implements MetricPublisher {
    @Override public void incrementCounter(String name, long delta) {}
    @Override public void gauge(String name, double value) {}
}
