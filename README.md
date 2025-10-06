# Distributed High Throughput Rate Limiter

This repository contains a Java implementation of a distributed, high-throughput
rate limiter designed to run across a fleet of servers and to use an external
`DistributedKeyValueStore` (provided as an interface) to keep global counts.

# Distributed High Throughput Rate Limiter

This repository contains a Java implementation of a distributed, high-throughput
rate limiter suitable for running across a fleet of servers. It uses a
`DistributedKeyValueStore` abstraction to maintain global counters and applies
sharding + local batching to support very high request rates while keeping per-
request latency low.

This README is English-only and documents implementation details, how to enable
metrics export (Prometheus and CloudWatch), usage examples, and operational
notes.

## Quick summary

- Main class: `com.codurance.limiter.DistributedHighThroughputRateLimiter`
- Store interface: `com.codurance.store.DistributedKeyValueStore` (method:
  `CompletableFuture<Integer> incrementByAndExpire(String key, int delta, int expirationSeconds) throws Exception`)
- Test mock: `com.codurance.store.MockDistributedKeyValueStore`
- Metric publishers: `com.codurance.limiter.metrics.PrometheusMetricPublisher`,
  `com.codurance.limiter.metrics.CloudWatchMetricPublisher` and
  `com.codurance.limiter.metrics.NoOpMetricPublisher` (default)

## What the code implements

- Sharded counters per logical key (`{logicalKey}#shard#{id}`) to distribute
  writes and reduce hot partitions on the backing store.
- Local batching: increments are accumulated locally and flushed periodically
  to the `DistributedKeyValueStore` to avoid a network call per request.
- Non-blocking local decision in `isAllowed(String key, int limit)` using last
  known global counts + pending local deltas (eventual consistency).
- Resilience: `RetryPolicy` and a simple `CircuitBreaker` to handle transient
  and persistent failures.
- Metrics abstraction with two concrete publishers (Prometheus and CloudWatch).

## Metrics export

The code includes a `MetricPublisher` abstraction and two publishers:

1. Prometheus
   - Class: `com.codurance.limiter.metrics.PrometheusMetricPublisher`
   - Uses the Prometheus Java simpleclient to register Counters and Gauges in a
     `CollectorRegistry`.
   - Typical usage: provide the publisher via the limiter builder. Prometheus
     must scrape the process (or an HTTP exporter must be used) to collect
     metrics.

   Example (create and wire Prometheus publisher):

```java
import io.prometheus.client.CollectorRegistry;
import com.codurance.limiter.metrics.PrometheusMetricPublisher;

CollectorRegistry registry = CollectorRegistry.defaultRegistry;
PrometheusMetricPublisher publisher = new PrometheusMetricPublisher(registry, "RateLimiter");

DistributedHighThroughputRateLimiter limiter = DistributedHighThroughputRateLimiter
    .newBuilder(store)
    .metricPublisher(publisher)
    .build();
```

   Optional: if you need a simple HTTP endpoint to expose metrics locally for
   Prometheus to scrape, add the `simpleclient_httpserver` dependency and start
   a `HTTPServer` in your process. I can add that helper if you want.

2. CloudWatch
   - Class: `com.codurance.limiter.metrics.CloudWatchMetricPublisher`
   - Uses AWS SDK v2 `CloudWatchClient.putMetricData` to send metric data. The
     provided implementation is minimal and sends each datum directly. For a
     production setup you should batch data and control send frequency to
     avoid throttling/cost.

   Example (create and wire CloudWatch publisher):

```java
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import com.codurance.limiter.metrics.CloudWatchMetricPublisher;

CloudWatchClient cw = CloudWatchClient.create(); // or configure region/credentials
CloudWatchMetricPublisher publisher = new CloudWatchMetricPublisher(cw, "MyApp/RateLimiter");

DistributedHighThroughputRateLimiter limiter = DistributedHighThroughputRateLimiter
    .newBuilder(store)
    .metricPublisher(publisher)
    .build();
```

   Operational notes:
   - PutMetricData can be rate-limited and has costs. Aggregate and batch where
     possible (e.g., send one PutMetricData with multiple MetricDatum once per
     second or minute).
   - Provide proper AWS credentials (IAM role or environment variables) and
     region configuration when running in production.

Default behavior: if you do not set a `MetricPublisher` in the builder, a
`NoOpMetricPublisher` is used and no metrics are exported.

## Metrics emitted by the limiter

- Counter: `FlushedBatches` — increments when a batch is successfully flushed
  to the store.
- Counter: `StoreFailures` — increments on write failures to the store.
- Gauge: `PendingBufferSize` — current approximate size of local pending
  buffer (sum of per-shard pending counters).
- Gauge: `CircuitBreakerOpen` — 1 when circuit breaker is open, 0 otherwise.

These metrics are intentionally minimal; they provide a starting point for
observability and can be extended as needed (latencies, per-shard metrics,
host labels, etc.).

## Usage example

```java
DistributedKeyValueStore store = ...; // provided by infra
PrometheusMetricPublisher prom = new PrometheusMetricPublisher(CollectorRegistry.defaultRegistry, "RateLimiter");

try (DistributedHighThroughputRateLimiter limiter = DistributedHighThroughputRateLimiter
    .newBuilder(store)
    .flushIntervalMs(100)
    .shards(8)
    .expirationSeconds(60)
    .retryPolicy(RetryPolicy.of(3, java.time.Duration.ofMillis(50)))
    .circuitBreaker(new CircuitBreaker(5, java.time.Duration.ofSeconds(2)))
    .metricPublisher(prom)
    .build()) {

  boolean allowed = limiter.isAllowed("client-xyz", 500).get();
}
```

## Build and tests

Requirements: JDK (21+ recommended) and Maven.

Run the unit tests:

```bash
mvn test
```

## Operational recommendations

- Prometheus is preferable for low-cost, high-cardinality observability. Use
  CloudWatch to forward aggregated metrics or alarms.
- Batch CloudWatch calls instead of calling `PutMetricData` for every metric.
- Tune `shards` and `flushIntervalMs` depending on expected load and desired
  accuracy. More shards increase write throughput but increase global read
  cost (need to sum more shard keys).
- Use IAM roles for production deployments to provide secure CloudWatch access.

