# Distributed High Throughput Rate Limiter

This repository contains a Java implementation of a distributed, high-throughput
rate limiter designed to run across a fleet of servers and to use an external
`DistributedKeyValueStore` (provided as an interface) to keep global counts.

The README below is bilingual: Português (PT-BR) followed by English (EN).

## Português (PT-BR)

Resumo rápido
- Classe principal: `com.codurance.limiter.DistributedHighThroughputRateLimiter`.
- Abstração do storage: `com.codurance.store.DistributedKeyValueStore` (método esperado
  `CompletableFuture<Integer> incrementByAndExpire(String key, int delta, int expirationSeconds) throws Exception`).
- Mock de testes: `com.codurance.store.MockDistributedKeyValueStore`.
- Testes: `src/test/java` (JUnit5).

Soluções implementadas (mapping para os requisitos)
1. Uso do `DistributedKeyValueStore` — a escrita global é feita chamando
   `incrementByAndExpire` em chaves físicas que representam shards de uma chave
   lógica. Isso respeita o contrato do enunciado e permite o armazenamento da
   contagem agregada por shard.
2. Janela fixa de 60s — a expiração padrão (configurável) é 60 segundos. A
   implementação assume que a expiração só é aplicada quando a chave é
   inicializada, como especificado.
3. Exatidão relaxada — `isAllowed(String key, int limit)` é não-bloqueante e
   usa a soma do último valor global conhecido por shard + deltas pendentes
   locais. Isso garante alta performance e permite pequenas ultrapassagens
   temporárias.
4. Alto throughput — para evitar uma chamada ao `DistributedKeyValueStore` por
   requisição, usamos:
   - Sharding: dividir a chave lógica em N shards (`{key}#shard#{id}`) para
     reduzir probabilidade de hot-key em um único item de storage.
   - Batching: acumular incrementos localmente e enviar periodicamente (flush
     por `flushIntervalMs`) na forma de `incrementByAndExpire(shardKey, delta, expirationSeconds)`.
5. Concurrency: estruturas thread-safe (`ConcurrentHashMap`, `AtomicInteger`),
   execução assíncrona com pool de workers e flusher agendado.
6. Robustez: adicionamos `RetryPolicy` (tentativas com backoff) e um
   `CircuitBreaker` simples para lidar com falhas transitórias e persistentes.

Pontos arquiteturais e princípios de Clean Code / SOLID aplicados
- Single Responsibility (SRP): a classe `DistributedHighThroughputRateLimiter`
  concentra coordenação de shards, buffering e decisões locais. As políticas
  de retry e circuit breaker são separadas em classes distintas.
- Open/Closed: o limiter usa Builder e expõe pontos de extensão para trocar a
  estratégia de sharding, retry ou circuit-breaker sem modificar a lógica
  central.
- Encapsulamento: detalhes de armazenamento ficam atrás da interface
  `DistributedKeyValueStore`.
- Código limpo: nomes claros, builder para configuração, métodos pequenos e
  documentação inline nas partes críticas.

Decisões de design e trade-offs (por que e custo)
- Sharding por chave lógica
  - Por que: mitigar hot-partitions no backend (um único contador quente).
  - Trade-off: aumenta a cardinalidade de chaves e torna leituras globais uma
    soma de shards, impactando custo/latência para consultas precisas.
- Batching / Flush periódico
  - Por que: reduzir chamadas de rede e permitir throughput muito alto.
  - Trade-off: introduz atraso até que os deltas sejam persistidos no store e
    permite pequenas ultrapassagens entre requisição e flush.
- Decisão local não bloqueante para `isAllowed`
  - Por que: necessidade de latência baixa por requisição.
  - Trade-off: consistência eventual (aceitação de pequenas imprecisões).
- Retries e CircuitBreaker
  - Por que: evitar perda de contagem em falhas transitórias e proteger o
    sistema em falhas persistentes.
  - Trade-off: complexidade operacional aumentada e possibilidade de rebalance
    de deltas quando o circuito fecha.

Limitações conhecidas
- Resharding dinâmico não implementado: número de shards é fixo por instância.
- Possibilidade de perda de deltas se uma instância falhar antes do flush; o
  `close()` tenta um flush síncrono, mas não há garantia absoluta.
- A precisão global é aproximada; para garantias fortes (sem overshoot) seria
  necessária uma arquitetura com coordenação forte, que reduziria throughput.

Como usar (exemplo)
```java
DistributedKeyValueStore store = ...; // implementação externa
try (DistributedHighThroughputRateLimiter limiter = DistributedHighThroughputRateLimiter
        .newBuilder(store)
        .flushIntervalMs(100)        // ajustável
        .shards(8)                   // ajustável por carga
        .expirationSeconds(60)       // janela fixa de 60s
        .retryPolicy(RetryPolicy.of(3, java.time.Duration.ofMillis(50)))
        .circuitBreaker(new CircuitBreaker(5, java.time.Duration.ofSeconds(2)))
        .build()) {
    boolean allowed = limiter.isAllowed("client-xyz", 500).get();
}
```

Como rodar os testes
Requisitos: JDK e Maven (versão usada está definida em `pom.xml`). Note que o
`README` original mencionava Java 11; o `pom.xml` pode declarar outra release —
verifique `pom.xml` antes de executar.

Executar:
```bash
mvn test
```

Preparar ZIP para submissão
- Incluir: `src/`, `pom.xml`, `README.md`.
- Excluir: `target/` (binários compilados) — por exemplo:
```bash
zip -r submission.zip src pom.xml README.md -x "*/target/*"
```

Notas sobre validação de requisitos de performance
- Para provar 100M calls/min (ou mais), a estratégia é escalar horizontalmente
  + aumentar `shards` por chaves muito quentes e ajustar `flushIntervalMs` e
  tamanhos de worker pool.
- Recomenda-se benchmarking com um backend real (DynamoDB/Redis) e testes de
  carga progressiva.

## English (EN)

Quick summary
- Main class: `com.codurance.limiter.DistributedHighThroughputRateLimiter`.
- Storage abstraction: `com.codurance.store.DistributedKeyValueStore`
  (method: `CompletableFuture<Integer> incrementByAndExpire(String key, int delta, int expirationSeconds) throws Exception`).
- Test mock: `com.codurance.store.MockDistributedKeyValueStore`.
- Tests: JUnit 5 in `src/test/java`.

Implemented solutions (how they satisfy the requirements)
1. Use of `DistributedKeyValueStore` — global counts are written by calling
   `incrementByAndExpire` on per-shard keys derived from the logical key.
2. Fixed 60s window — default expiration is 60s (configurable). The implementation
   respects the store behaviour that expiration is only set at key initialization.
3. Relaxed accuracy — `isAllowed` returns immediately and computes an
   approximation from last-known global values and pending local increments.
4. High throughput — avoids a network call per request via sharding + batching
   and uses a configurable flush interval and a bounded worker pool to send
   batched deltas concurrently.
5. Concurrency-safe — thread-safe maps and atomics are used together with a
   background scheduled flusher and async workers.
6. Resilience — `RetryPolicy` and `CircuitBreaker` handle transient and
   persistent failures.

Clean Code & SOLID considerations
- SRP: limiter focuses on logic for buffering, sharding and local decision.
  Retry and circuit breaker are separate classes.
- OCP: behavior can be extended (different sharding or retry strategies) via
  builder configuration without changing core logic.
- Encapsulation and clear naming throughout the codebase.

Design choices and trade-offs
- Sharding improves write scalability but increases key cardinality and
  complicates accurate reads (need to sum shards).
- Batching reduces RPCs but increases propagation latency and allows temporary
  overshoot.
- Local non-blocking checks keep per-request latency low but make correctness
  eventual rather than immediate.
- Retries and circuit breaker reduce error-induced data loss but add runtime
  complexity.

Limitations
- No dynamic re-sharding; shard count is static per limiter instance.
- Possibility of losing some pending deltas on sudden process termination; a
  best-effort synchronous flush is attempted on `close()`.
- The mock store does not reproduce production latencies or failure modes —
  run integration tests against real backends to validate at scale.

Usage example (Java)
```java
DistributedKeyValueStore store = ...; // external implementation
try (DistributedHighThroughputRateLimiter limiter = DistributedHighThroughputRateLimiter
        .newBuilder(store)
        .flushIntervalMs(100)
        .shards(8)
        .expirationSeconds(60)
        .retryPolicy(RetryPolicy.of(3, java.time.Duration.ofMillis(50)))
        .circuitBreaker(new CircuitBreaker(5, java.time.Duration.ofSeconds(2)))
        .build()) {
    boolean allowed = limiter.isAllowed("client-xyz", 500).get();
}
```

Testing and verification
- Unit tests cover local pending blocking behavior, batched flush, concurrency,
  retries and circuit-breaker behavior. See `src/test/java/...`.
- For performance validation, run load tests in an environment with a real
  backend (DynamoDB/Redis). Monitor metrics like `pending buffer size`,
  `flush rate`, `store failures` and CPU/network usage.

Packaging for submission
- Ensure `target/` is excluded and include only `src/`, `pom.xml` and this
  `README.md` in the zip.

Next steps and optional improvements
- Automatic hot-key detection and dynamic re-sharding.
- Prometheus metrics export and Grafana dashboards for observability.
- Integration tests against real storage backends with scalable setups.
- Improved shutdown protocol to reliably persist pending deltas (draining
  strategy with handoff to a sibling instance).

---

If you want I can:
- Produce the submission zip with only source files (remove `target/`).
- Add a small load generator/scenario to help evaluate throughput locally.
- Add Prometheus metrics and a small dashboard example.
