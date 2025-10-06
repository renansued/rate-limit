package com.codurance.limiter.metrics;

/**
 * Minimal metrics publishing interface used by the limiter.
 */
public interface MetricPublisher {

  void incrementCounter(String name, long delta);

  void gauge(String name, double value);

  default void flush() {}
}
