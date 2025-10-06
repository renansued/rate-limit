# Distributed High Throughput Rate Limiter

Projeto exemplo que implementa um rate limiter distribuído em Java, focado em alta vazão e
escala (projeto educativo). A implementação usa uma estratégia baseada em contadores shardados
com batching local para reduzir chamadas de rede ao armazenamento distribuído.

## Resumo da solução

- Classe principal: `DistributedHighThroughputRateLimiter`.
- Interface de armazenamento: `DistributedKeyValueStore` (assumida como fornecida externamente).
- Mock para testes: `MockDistributedKeyValueStore`.
- Estratégia chave: shard de contadores + flush em lote (batch) de deltas locais.

Objetivo: suportar altíssima taxa de requisições por cliente lógico (ex.: 100M/min ou mais) sem
chamar o armazenamento distribuído em cada requisição e mantendo uma precisão eventual.

## Principais características e decisões de arquitetura

1. Sharding de chaves
   - Para evitar hot partitions no backend (por exemplo um DynamoDB/Redis/ElastiCache shard),
     o contador lógico `clientId` é dividido em N shards (`clientId#shard#{0..N-1}`).
   - Cada requisição incrementa localmente um shard aleatório. Ao agregar, somamos todos os
     shards para obter o total aproximado.

2. Batching local e flush periódico
   - Requests incrementam contadores locais (em memória) e são agregados.
   - Um flusher periódico envia deltas ao `DistributedKeyValueStore` (método `incrementByAndExpire`).
   - Isso reduz chamadas de rede de O(RPS) para O(RPS * flushInterval / requestsPerFlush).

3. Tolerância a imprecisão
   - Para atender ao requisito (permitir pequenos excessos), o limite é verificado com base no
     último valor conhecido do store + deltas locais; podem ocorrer overshoots temporários.

4. Padrões de projeto
   - Builder: para configurar o limiter (shards, flush interval, expiration).
   - Strategy (extensível): a função de escolha de shard ou amostragem pode ser trocada/separada.

## Escalabilidade e como atingir 1 bilhão de requisições por minuto (design conceitual)

1. Distribuição de carga
   - Deploy multi-AZ com muitos servidores (stateless). Cada servidor mantém buffers locais
     e realiza flushs assíncronos.
   - A divisão de shards por cliente reduz a probabilidade de hot-keys (ex.: 256 shards para
     clientes muito calientes).

2. Backend recomendado na AWS
   - Para contadores com alto throughput e baixa latência: Amazon DynamoDB com chave particionada
     (PK = shardKey) e operações atômicas (`UpdateItem` com ADD) + TTL em atributo.
   - Alternativa: Amazon ElastiCache (Redis Cluster) usando INCRBY e expiração. Para persistência
     consistente, combine com DynamoDB Streams se necessário.

3. Estratégia de Sharding
   - Aumente dinamicamente o número de shards para chaves hot (requer mapeamento e rehashing
     externo, ou use técnicas como consistent hashing + token buckets).

4. Provisionamento
   - Para 1B req/min = ~16.7M req/s, cada servidor deve processar uma fração: por exemplo 1000
     servidores com 16.7k req/s cada. Ajuste com autoscaling baseado em métricas (CPU, latência,
     tamanho dos buffers, filas de flush).

5. Rede
   - Use endpoints locais (VPC endpoints) para reduzir latência entre app servers e DynamoDB/ElastiCache.

6. Persistência e consistência
   - A solução é eventual-consistent para decisões instantâneas. Para limites absolutamente rígidos,
     precisaria de uma coordenação forte (ex.: tokens centralizados ou leaky bucket com consenso),
     que sacrifica latência e throughput.

## Segurança

- Rede: use VPC, subnets privadas, security groups para limitar acesso aos backends (DynamoDB/Redis).
- Criptografia: at-rest e in-transit para os dados do backend.
- Autenticação e autorização: roles IAM para limitar permissões de escrita apenas às operações necessárias.

## Testes incluídos

- Unit tests com JUnit 5 (`src/test/java/...`) cobrindo
  - comportamento sob limite
  - local pending bloqueando
  - flush batched
  - concorrência básica
  - testes de stress-simulado (pequeno) e edge cases

## Como rodar localmente

Requisitos: Java 11, Maven

No diretório do projeto execute:

```bash
mvn test
```

## Testes de extremidade e stress

- Testes de unidade estão no repositório e simulam o comportamento do store.
- Para testar em escala real (stress):
  - Crie um ambiente AWS com muitos app servers (EC2/ECS/Fargate).
  - Use uma carga de ferramentas como Gatling ou k6 para gerar tráfego.
  - Meça latências, throughput, tamanho dos buffers e taxa de falhas do backend.

### Integração com CloudWatch

- Publique métricas customizadas (CloudWatch) a partir dos app servers:
  - `PendingBufferSize` por host
  - `FlushedBatches` por minuto
  - `StoreFailures` e `StoreRetries`
  - `CircuitBreakerOpen` (flag)
- Use CloudWatch Alarms para acionar autoscaling ou alertas se latência/erro subir.

## Resiliência: Retry e Circuit Breaker

- Implementado um `RetryPolicy` configurável (max attempts e backoff) para falhas transitórias ao enviar deltas.
- Implementado um `CircuitBreaker` simples que abre após N falhas consecutivas e bloqueia chamadas por um período.

## Como testar bilhões de requisições sem custo

Gerar 1B req/min no mundo real é caro. Use estas estratégias para validar comportamento em escala com baixo custo:

1. Testes de unidade e integração local (baixo custo)
  - Simule load com muitos threads e reduza latência artificialmente no mock. Isso valida lógica de batching, retry e circuit-breaker.

2. Testes de amostragem (scale-down)
  - Execute um teste em menor escala (por exemplo 1M req/min) e extrapole os resultados linearmente.
  - Meça latência por operação, CPU e uso de rede. Se a latência por operação for constante e os recursos escalam linearmente, você pode estimar o número de servidores necessários.

3. Modelagem e simulação
  - Use ferramentas de simulação (por exemplo, escrever um gerador de tráfego que apenas contabiliza eventos localmente e modela latência esperada) para estimar throughput e pontos de contenção.

4. Testes em nuvem com recursos spot e curta duração
  - Se precisar validar no ambiente real, use instâncias spot por curtos períodos para reduzir custo e execute testes pontuais em menor janela. Combine com DynamoDB on-demand e limites controlados.

5. Benchmarking por componente
  - Meça latência do backend (DynamoDB/Redis) separadamente para determinar o quanto cada flush custa.

6. Observability + extrapolação
  - Colete métricas (via CloudWatch) enquanto escala um pequeno número de servidores e extrapole até o alvo de 1B/min.


## Limitações

- A contagem é apenas aproximada. Picadas temporárias acima do limite são permitidas.
- Hot keys: a estratégia de shard ajuda, mas demanda planejamento de número de shards e
  possivelmente re-sharding dinâmico para clientes muito quentes.
- O mock store não replica latência e falhas do mundo real — recomenda-se um teste com
  um DynamoDB/Redis real para validar comportamento sob carga.

## Próximos passos / extensões possíveis

- Implementar resharding/hot-key detection automático.
- Melhorar a garantia de flush durante shutdown (flush síncrono final ).
- Instrumentação (Prometheus + Grafana) para métricas: pending size, flush rate, failures.
- Mecanismo de fallback quando o backend estiver indisponível (circuit breaker + retry com backoff).

---

Este README resume o projeto, escolhas e caminhos para escala/segurança. Se desejar, posso
gerar um pacote zip com somente o código fonte pronto para submissão ou expandir os testes
de stress com scripts de carga. 
