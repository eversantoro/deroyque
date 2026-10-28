# Serviço de ativação de produtos

Serviço que registra solicitações de ativação de produtos de clientes, permite consultar o status e realiza a ativação
em um provedor externo, de forma assíncrona e resiliente.

- **Stack:** Java 21 · Spring Boot 3.4 (MVC, Data JPA/Hibernate, Security, Actuator) · PostgreSQL 16 · Flyway ·
  Resilience4j · Micrometer/OpenTelemetry · springdoc (Swagger) · Maven · Docker.
- **Documentação interativa:** `http://localhost:8080/swagger-ui.html` (OpenAPI em `/v3/api-docs`).
- **Decisões de arquitetura (ADRs):** [`docs/adr/`](docs/adr).

## Sumário

1. [Como executar](#1-como-executar)
2. [API](#2-api)
3. [Decisões técnicas](#3-decisões-técnicas)
4. [Estratégia de testes](#4-estratégia-de-testes)
5. [Observabilidade](#5-observabilidade)
6. [Cenário de análise (item 10)](#6-cenário-de-análise-item-10)
7. [Produção](#7-produção)
8. [Microsserviços e cloud](#8-microsserviços-e-cloud)
9. [Pontos pendentes](#9-pontos-pendentes)
10. [Melhorias futuras](#10-melhorias-futuras)
11. [Estrutura do projeto](#11-estrutura-do-projeto)

---

## 1. Como executar

### Pré-requisitos

- **JDK 21** (o projeto compila com `release 21`).
- **Docker** (Docker Desktop ou Engine) — necessário para o `docker-compose` e para os testes de integração
  (Testcontainers).
- Não é necessário instalar o Maven: use o wrapper (`mvnw` / `mvnw.cmd`).

> Se o `java` do seu PATH não for o 21, aponte `JAVA_HOME` para um JDK 21 antes de rodar o Maven.
> Windows (PowerShell): `$env:JAVA_HOME="C:\Program Files\Java\jdk-21"`.

### Build e todos os testes (um único comando)

```bash
./mvnw verify          # Windows: mvnw.cmd verify
```

Executa os testes unitários (surefire), sobe PostgreSQL via Testcontainers para os testes de integração (failsafe) e
gera o relatório de cobertura em `target/site/jacoco`.

Apenas testes unitários (rápidos, sem Docker): `./mvnw test`.

### Tudo com Docker Compose (aplicação + banco + mock do provedor)

```bash
docker compose up --build
```

| Serviço | Endereço | Observação |
|---|---|---|
| Aplicação | http://localhost:8080 | Swagger em `/swagger-ui.html` |
| Mock do provedor (WireMock) | http://localhost:9090 | regras em `docker/wiremock/mappings` |
| PostgreSQL | `localhost:5433` | usuário/senha/base: `activation` (porta configurável por `DB_HOST_PORT`) |

O compose usa o perfil `local`, que aceita tokens HS256 assinados com um segredo de desenvolvimento (não há servidor
de autenticação — ver [ADR 0004](docs/adr/0004-seguranca-oauth2-jwt.md)). Gere um token e use a API:

```bash
TOKEN=$(scripts/dev-token.sh sistema-a "requests:read requests:write")   # PowerShell: .\scripts\dev-token.ps1
curl -i -X POST localhost:8080/api/v1/requests \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"externalId":"EXT-12345","customerId":"CUST-10001","productCode":"INTERNET-500"}'

curl -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/requests/<id>   # PENDING -> COMPLETED em ~1 s
```

O mock do provedor reage ao `productCode`, o que permite demonstrar cada cenário:

| `productCode` | Resposta do provedor | Resultado esperado |
|---|---|---|
| qualquer outro | 200 | `COMPLETED` |
| `FAIL-400` | 400 | `FAILED` (sem retry) |
| `FAIL-500` | 500 | retries com backoff e, ao esgotar, `FAILED` |
| `RATE-LIMITED` | 429 + `Retry-After: 5` | retry respeitando o `Retry-After` |
| `UNAVAILABLE` | 503 + `Retry-After: 3` | retry respeitando o `Retry-After` |
| `SLOW` | 200 após 8 s | timeout (5 s) → retry (com a mesma chave de idempotência) |

### Pelo Eclipse

1. *File ▸ Import ▸ Maven ▸ Existing Maven Projects* e selecione a pasta do projeto.
2. *Window ▸ Preferences ▸ Java ▸ Installed JREs*: cadastre o JDK 21 e use-o no projeto
   (*Project ▸ Properties ▸ Java Build Path ▸ Libraries ▸ JRE System Library = JavaSE-21*).
3. Suba só o banco e o mock: `docker compose up -d db provider-mock`.
4. Execute `ActivationServiceApplication` (*Run As ▸ Java Application*) com, em *Run Configurations ▸ Environment*:
   `SPRING_PROFILES_ACTIVE=local` e `DB_URL=jdbc:postgresql://localhost:5433/activation`.
5. Testes: botão direito no projeto ▸ *Run As ▸ JUnit Test* (os `*IT` exigem Docker) ou *Run As ▸ Maven build…*
   com o goal `verify`.

### Configuração (variáveis de ambiente)

| Variável | Padrão | Descrição |
|---|---|---|
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/activation` / `activation` / `activation` | Banco |
| `PROVIDER_BASE_URL` | `http://localhost:9090` | URL do provedor |
| `PROVIDER_CONNECT_TIMEOUT` / `PROVIDER_READ_TIMEOUT` | `PT2S` / `PT5S` | Timeouts do cliente HTTP |
| `PROVIDER_API_KEY` | vazio | Credencial enviada ao provedor (`X-Api-Key`) |
| `ACTIVATION_MAX_ATTEMPTS` | `5` | Tentativas máximas por solicitação |
| `JWT_JWK_SET_URI` | **obrigatória** fora do perfil `local` | JWKS do servidor de autorização |
| `JWT_ISSUER` / `JWT_AUDIENCE` | `https://auth.exemplo.com` / `activation-service` | Validações do JWT |
| `RATE_LIMIT_PER_MINUTE` / `RATE_LIMIT_BURST` | `120` / `30` | Limite de taxa por consumidor |
| `TRACING_SAMPLING_PROBABILITY` | `0.0` | Amostragem OpenTelemetry (0 a 1) |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | `http://localhost:4318/v1/traces` | Coletor OTLP |

---

## 2. API

Todos os endpoints exigem `Authorization: Bearer <JWT>`. Erros seguem
[RFC 9457 (Problem Details)](https://www.rfc-editor.org/rfc/rfc9457) com `traceId` (= `X-Correlation-Id`).

| Método e rota | Escopo | Descrição |
|---|---|---|
| `POST /api/v1/requests` | `requests:write` | Cria a solicitação (`PENDING`). `201` + `Location`; `200` se for repetição legítima; `409` se `externalId` já existe com outro conteúdo. |
| `GET /api/v1/requests/{id}` | `requests:read` | Consulta a solicitação. `404` se não existir (ou for de outro consumidor). |
| `GET /api/v1/requests?status=&customerId=&page=&size=` | `requests:read` | Listagem paginada (mais recentes primeiro; `size` ≤ 100). |

**Validações** (documentadas também no OpenAPI): `externalId`, `customerId` e `productCode` são obrigatórios, têm no máximo
64 caracteres e formato `[A-Za-z0-9][A-Za-z0-9._:-]*`. Corpo máximo: 8 KB. Datas em UTC, ISO-8601.

**Resposta** (campos extras além do enunciado: `attempts` e, quando houver, `failureReason`):

```json
{
  "id": "0b7f3c1e-5d2a-4c4e-9a51-2f8d7b1e6c90",
  "externalId": "EXT-12345",
  "customerId": "CUST-10001",
  "productCode": "INTERNET-500",
  "status": "PENDING",
  "attempts": 0,
  "createdAt": "2026-10-26T14:03:11Z",
  "updatedAt": "2026-10-26T14:03:11Z"
}
```

**Erro de validação:**

```json
{
  "type": "https://api.exemplo.com/problems/validation-error",
  "title": "Dados inválidos",
  "status": 400,
  "detail": "Um ou mais campos não passaram na validação.",
  "instance": "/api/v1/requests",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "errors": [{ "field": "customerId", "message": "é obrigatório" }]
}
```

| Situação | Status | `type` (sufixo) |
|---|---|---|
| Dados inválidos / parâmetros fora do limite | 400 | `validation-error` |
| JSON ausente/malformado | 400 | `malformed-request` |
| Sem token / token inválido | 401 | `unauthorized` |
| Escopo insuficiente | 403 | `forbidden` |
| Solicitação inexistente | 404 | `not-found` |
| `externalId` duplicado com conteúdo diferente | 409 | `duplicate-request` |
| Corpo grande demais | 413 | `payload-too-large` |
| Limite de taxa | 429 (+`Retry-After`) | `rate-limited` |
| Banco indisponível | 503 | `service-unavailable` |
| Erro inesperado | 500 | `internal-error` (sem stack trace; o `traceId` leva ao log) |

Erros do **provedor** nunca chegam ao chamador do `POST` (o envio é assíncrono): aparecem como `status=FAILED` com
`failureReason` em uma consulta.

### Estados

```
PENDING ──► PROCESSING ──► COMPLETED
                │  └─────► FAILED
                └────────► PENDING   (falha temporária: nova tentativa)
```

Falha temporária **volta para `PENDING`** com `next_attempt_at` no futuro e o contador `attempts` preservado
(`PROCESSING` fica reservado a "uma instância está chamando o provedor"). Detalhes no [ADR 0005](docs/adr/0005-estados-e-concorrencia.md).

---

## 3. Decisões técnicas

Resumo; a justificativa completa está nos ADRs.

### Estratégia contra duplicidade ([ADR 0001](docs/adr/0001-unicidade-do-external-id.md))

Índice **único** em `external_id` no banco + *insert-first*: a aplicação tenta inserir e, se o banco recusar, lê o
registro existente. "Verificar e depois inserir" não basta porque duas requisições simultâneas podem ambas ver "não existe"
antes de qualquer inserção; só uma operação atômica do banco fecha essa janela (e funciona com várias instâncias).

| Cenário | Resposta |
|---|---|
| Retry legítimo (mesmo `externalId`, mesmo conteúdo) | `200` com o registro existente |
| Mesmo `externalId`, conteúdo diferente (ou de outro consumidor) | `409` |
| Duas requisições idênticas em paralelo | uma `201`, a outra `200` com o mesmo registro |

### Integração com o provedor ([ADR 0002](docs/adr/0002-processamento-assincrono-com-outbox.md), [ADR 0003](docs/adr/0003-integracao-com-o-provedor.md))

| Retorno do provedor | Novo status | Nova tentativa? | Justificativa |
|---|---|---|---|
| Sucesso (2xx) | `COMPLETED` | — | Ativação confirmada |
| HTTP 400 (4xx em geral) | `FAILED` | Não | Repetir o mesmo conteúdo dará o mesmo erro |
| HTTP 500 (5xx) | `PENDING` | Sim, com backoff | Falha provavelmente temporária |
| Timeout | `PENDING` | Sim, com backoff | Resultado ambíguo; seguro repetir graças à chave de idempotência |
| HTTP 429 / 503 + `Retry-After` | `PENDING` | Sim, respeitando `Retry-After` | O provedor informou quando voltar |
| Circuito aberto | `PENDING` | Sim, sem consumir tentativa | A chamada nem foi feita |
| Limite de tentativas | `FAILED` | Não | `Tentativas esgotadas (N): …` |

- **Assíncrono:** o `POST` só grava e responde; um worker envia. A latência da API não depende do provedor.
- **Transação × chamada remota:** nenhuma chamada HTTP ocorre dentro de transação. O ciclo é reservar (transação curta,
  `FOR UPDATE SKIP LOCKED`) → chamar → gravar resultado (transação curta).
- **Garantia de envio mesmo se a aplicação cair:** a própria tabela é a fila (*outbox*). Linha `PENDING` sobrevive ao
  crash; linha `PROCESSING` órfã é devolvida à fila pelo reconciliador após `stale-after` (2 min).
- **Idempotência com o provedor:** `Idempotency-Key = id da solicitação` em toda chamada.
- **Timeouts explícitos**, **backoff exponencial com jitter**, **limite de tentativas** e **circuit breaker**
  (Resilience4j) configuráveis.

### Segurança ([ADR 0004](docs/adr/0004-seguranca-oauth2-jwt.md)) — implementada

Resource Server com JWT (client credentials), validação de assinatura/`iss`/`aud`/`exp`, escopos separados de leitura e
escrita, isolamento entre consumidores (BOLA → 404), limite de corpo (8 KB) e de taxa (token bucket). Credencial do
provedor: variável de ambiente alimentada por um cofre de segredos, com rotação sem redeploy (detalhes no ADR).

### Padrões de projeto

State, Factory method, Ports & Adapters, Strategy, Repository, Observer, Chain of Responsibility e tipos selados
com *pattern matching* — todos com motivo concreto, listados no [ADR 0005](docs/adr/0005-estados-e-concorrencia.md).
As dependências entre camadas são verificadas por ArchUnit.

### Índices (listagem e fila)

| Índice | Atende a | Justificativa |
|---|---|---|
| `uk_activation_request_external_id` (único) | Duplicidade e busca por `externalId` | Garantia de unicidade |
| `ix_activation_request_due` — parcial `(next_attempt_at) WHERE status='PENDING'` | Worker | Só indexa o que falta enviar: pequeno e sempre quente |
| `ix_activation_request_stalled` — parcial `(processing_started_at) WHERE status='PROCESSING'` | Reconciliador | Idem |
| `(owner_id, created_at desc, id desc)` | Listagem sem filtro | Todas as consultas são por consumidor; já vem ordenado, sem *sort* |
| `(owner_id, status, created_at desc, id desc)` | Filtro por `status` | Igualdade + ordenação cobertas pelo índice |
| `(owner_id, customer_id, created_at desc, id desc)` | Filtro por `customerId` | Idem; filtro combinado usa este e filtra `status` em memória |

---

## 4. Estratégia de testes

| Tipo | Classes | O que cobre | Por quê |
|---|---|---|---|
| **Unitário (sem Spring)** | `ActivationRequestTest`, `RequestStatusTest`, `ExponentialBackoffPolicyTest`, `TokenBucketRateLimiterTest` | Regras de domínio, transições inválidas, backoff, rate limit | Rápidos; protegem as regras de negócio |
| **Unitário com Mockito** | `ActivationProcessorTest` | Reação a cada resultado do provedor (concluir, falhar, retry, `Retry-After`, tentativas esgotadas, resultado obsoleto) | Isola a lógica de decisão do banco e da rede |
| **Adaptador HTTP (WireMock)** | `HttpActivationProviderTest` | Tradução de 2xx/400/500/429/503, `Retry-After`, timeout, falha de conexão, circuit breaker, cabeçalhos | Contrato com o provedor sem rede real |
| **Camada web (`@WebMvcTest`)** | `ActivationRequestControllerTest` | Criação, validação, 200/201/404/409/500, Problem Details, 401/403 por escopo, correlação | Contrato HTTP e segurança sem banco |
| **Arquitetura (ArchUnit)** | `ArchitectureTest` | Direção das dependências entre camadas, sem injeção em campo | Impede erosão da estrutura |
| **Integração (Testcontainers + WireMock)** | `ActivationFlowIT`, `DuplicateRequestIT`, `SecurityIT` | Fluxo completo com PostgreSQL real e worker: sucesso, 400, 500 até esgotar, retry com sucesso, timeout, mesma chave de idempotência após timeout, reconciliação, listagem; **25 requisições simultâneas com o mesmo `externalId` → exatamente 1 registro e 1 chamada ao provedor**; JWTs reais (401/403, audiência, emissor, assinatura, expiração), BOLA, limite de corpo, actuator e OpenAPI | Só o banco real prova unicidade, `SKIP LOCKED`, migrações e mapeamento JPA |

Unitários rodam no surefire (`*Test`); integração no failsafe (`*IT`). `./mvnw verify` roda ambos.
Não há mocks de banco: onde o comportamento depende do PostgreSQL, o teste usa PostgreSQL.

---

## 5. Observabilidade

- **Actuator:** `/actuator/health/liveness` e `/actuator/health/readiness` (inclui banco), públicos; `/actuator/prometheus`
  e `/actuator/metrics` exigem o escopo `metrics:read`.
- **Correlação:** `X-Correlation-Id` (recebido ou gerado) vai ao MDC, aos logs, à resposta, ao `traceId` dos erros, é
  gravado na solicitação e **propagado ao provedor** pelo worker. Nenhum token ou credencial é logado.
- **Métricas (Micrometer/Prometheus):** `http.server.requests` (latência da API); `activation.provider.calls`
  (latência do provedor, com tag `outcome` — taxa de erro = tudo que não é `activated`); `activation.requests.created`;
  `activation.requests.finished{status}`; `activation.requests.backlog{status=PENDING|PROCESSING}`; métricas do circuit
  breaker e do pool Hikari.
- **Logs em JSON:** perfil `prod` (`logging.structured.format.console=logstash`).
- **Tracing:** OpenTelemetry via Micrometer Tracing; desligado por padrão (`TRACING_SAMPLING_PROBABILITY=0`), basta
  apontar `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` e aumentar a amostragem.

---

## 6. Cenário de análise (item 10)

Sintomas: API 300 ms → 2,5 s; consultas ao banco 50 ms → 1,2 s; provedor 400 ms → 3 s; CPU 45% e memória 60% estáveis.

**O que investigaria primeiro?** O que *não* mudou: CPU e memória normais descartam saturação da JVM, GC e laço de
CPU. Então o tempo está sendo gasto **esperando** (I/O, locks, filas). Começaria por um recorte do tempo de uma requisição
lenta: quanto foi em banco, quanto no provedor, quanto esperando recurso. Em seguida, verificaria se houve deploy,
mudança de volume de dados ou de tráfego no momento em que a degradação começou.

**Como saber se é aplicação, banco ou integração externa?**
- Traces (OpenTelemetry) ou logs com `traceId`: spans de banco e de cliente HTTP mostram onde está a demora.
- Métricas separadas por fronteira: `activation.provider.calls` (provedor), `hikaricp.connections.acquire` /
  `hikaricp.connections.pending` (espera por conexão), `http.server.requests` (total).
- No banco: `pg_stat_activity` (sessões `idle in transaction`, esperas por lock), `pg_stat_statements` (consultas mais
  lentas), `EXPLAIN (ANALYZE, BUFFERS)` da consulta lenta (índice sendo ignorado? *seq scan*?), `pg_locks`.
- Na aplicação: *thread dump* (threads em `HikariPool.getConnection`, em socket read do provedor, ou em lock).

**Logs e métricas úteis:** p50/p95/p99 da API e do provedor; taxa de erro e timeouts do provedor; estado do circuit
breaker; conexões ativas/ociosas/pendentes do Hikari; `activation.requests.backlog` (fila crescendo = worker não dá conta);
tentativas por solicitação; slow query log do PostgreSQL; contagem de linhas/tamanho da tabela.

**Melhorias possíveis:** timeouts e circuit breaker (já existentes) para o provedor não arrastar o resto; índice ou
reescrita da consulta lenta; paginação por *keyset* em vez de `OFFSET`; ajuste do pool; cache de leituras; reduzir
a frequência de polling; arquivar/particionar solicitações antigas; réplica de leitura para a listagem; escalar o worker.

**Os três sintomas podem ter uma causa comum? (hipótese)** Sim: **esgotamento do pool de conexões**. Se o provedor fica
lento (3 s) e a chamada é feita com uma transação/conexão aberta, as conexões ficam retidas esperando o provedor; as demais
consultas passam a esperar uma conexão livre, e o tempo de espera é medido como "consulta lenta" (50 ms → 1,2 s) e a API
herda o atraso. CPU e memória permanecem normais porque tudo está *bloqueado*, não trabalhando. Variações da mesma ideia:
uma transação longa/lock contention no banco que atrasa tudo, ou um problema de rede/infraestrutura compartilhada entre
aplicação, banco e provedor (mesmo *node*, mesmo gateway de saída).
*Como confirmar:* `hikaricp.connections.pending` > 0 e `acquire` alto no mesmo instante em que o provedor degrada;
*thread dump* com muitas threads em `getConnection`; `pg_stat_activity` com sessões `idle in transaction` associadas à
chamada ao provedor; correlação temporal entre as três curvas. *Nesta arquitetura* isso foi evitado por desenho (a chamada
ao provedor é feita **fora** de qualquer transação, ver ADR 0002), então a hipótese mais provável aqui seria
contenção no banco ou na rede.

**Como validar que a correção funcionou?** Comparar as mesmas métricas antes/depois (p95/p99 da API, `acquire` do Hikari,
latência das consultas, backlog) por um período equivalente de tráfego; confirmar nos traces que o span lento sumiu;
acompanhar alertas de SLO (latência e taxa de erro) e, se possível, fazer *canary*/deploy gradual com comparação entre
versões e um teste de carga reproduzindo o cenário.

---

## 7. Produção

**Imagem:** `Dockerfile` multi-estágio (build com JDK, runtime apenas JRE Alpine), usuário **não-root**, configuração
por variáveis de ambiente, `-XX:MaxRAMPercentage=75` (memória da JVM proporcional ao limite do contêiner),
`HEALTHCHECK` no liveness e desligamento gracioso (`server.shutdown=graceful`, 30 s).

```bash
docker build -t activation-service:1.0.0 .
docker run -d --name activation -p 8080:8080 -m 512m \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e DB_URL=jdbc:postgresql://<host>:5432/activation -e DB_USER=... -e DB_PASSWORD=... \
  -e PROVIDER_BASE_URL=https://provedor.exemplo.com -e PROVIDER_API_KEY=... \
  -e JWT_JWK_SET_URI=https://auth.exemplo.com/.well-known/jwks.json \
  -e JWT_ISSUER=https://auth.exemplo.com \
  activation-service:1.0.0
```

Em produção real: executar em um orquestrador (Kubernetes/ECS/Cloud Run) com 2+ réplicas atrás de um balanceador/API
gateway com TLS; *liveness* e *readiness* apontando para os endpoints do Actuator; segredos vindos de um cofre (não de
arquivo); banco gerenciado (PostgreSQL com backup e réplica); migrações Flyway aplicadas no *startup* (ou em um *job* de
pré-deploy); logs JSON coletados pela plataforma; métricas Prometheus; `PodDisruptionBudget`/*rolling update* para deploys
sem indisponibilidade. O perfil `prod` **não sobe** sem `JWT_JWK_SET_URI` (de propósito).

---

## 8. Microsserviços e cloud

- **Várias instâncias sem quebrar a duplicidade e o processamento:** a unicidade vive no índice único do banco (vale
  para qualquer número de instâncias); o processamento usa `FOR UPDATE SKIP LOCKED` (cada instância pega linhas
  diferentes), `@Version` e reconciliação de linhas órfãs.
- **Configuração e segredos:** configuração por variáveis de ambiente (12-factor); segredos (senha do banco, credencial
  do provedor) no Secrets Manager / Key Vault / Vault / Kubernetes Secrets, montados como variáveis na inicialização.
- **Avisar outros microsserviços:** o evento de domínio `ActivationFinished` já é publicado após o commit; basta um
  *listener* que o publique em uma fila/tópico (SQS/SNS, Kafka, Service Bus). Para garantia *at-least-once* sem
  gravação dupla, usaria uma tabela outbox de eventos lida por um *relay* (ou CDC com Debezium), e consumidores
  idempotentes.
- **CI/CD até o deploy:** o workflow [`.github/workflows/ci.yml`](.github/workflows/ci.yml) roda build + testes
  (unitários e de integração) e constrói a imagem. Evolução: publicar a imagem (tag = SHA) em um registry, escanear
  vulnerabilidades (Trivy), fazer deploy automático em homologação, *smoke test*, aprovação e promoção para produção
  (GitOps/Helm) com *rollback* automático por SLO.

---

## 9. Pontos pendentes

Itens que **não** foram implementados e como seriam feitos:

- **Rate limit global entre instâncias:** hoje é por instância (memória). Faria no API Gateway ou com contador
  compartilhado (Redis / bucket4j com JCache).
- **Publicação de eventos em fila/tópico:** só há o evento interno `ActivationFinished`. Faria a tabela de outbox de
  eventos + relay descritos acima.
- **Credencial do provedor com OAuth 2.0 e rotação automática:** hoje é uma chave estática por variável de ambiente.
  Trocaria por *client credentials* com cache e renovação do token.
- **Kubernetes/Terraform:** fora do escopo; ver a seção 7 para como seria executado.
- **Limpeza de dados antigos:** não há retenção; solicitações terminais antigas deveriam ser arquivadas/particionadas.
- **Teste de carga e de resiliência (caos):** os testes cobrem comportamento, não capacidade.

## 10. Melhorias futuras

- Paginação por *keyset* (cursor) na listagem para tabelas grandes.
- Endpoint administrativo para reprocessar solicitações `FAILED` (com auditoria).
- Webhook/callback configurável por consumidor ao finalizar a ativação.
- Dashboards e alertas (SLOs de latência, taxa de erro do provedor, backlog) e propagação completa de *traces*.
- Wakeup imediato do worker após o commit (hoje há polling de 1 s) para reduzir a latência de envio.
- Particionamento da tabela por data e política de retenção.
- Testes de contrato (Pact) com o provedor e testes de mutação.
- Hardening: rotação automática de chaves, mTLS entre serviços, WAF no gateway.

---

## 11. Estrutura do projeto

```
src/main/java/br/com/deroyque/activation
├── domain/         entidade, estados e repositório (sem dependência das demais camadas)
├── application/    casos de uso, processador/worker, reconciliação, porta do provedor, backoff
├── integration/    adaptador HTTP do provedor (RestClient + Resilience4j)
├── web/            controller, DTOs (records) e tratamento de exceções
├── security/       resource server, escopos, limite de corpo e de taxa
├── problem/        fábrica de Problem Details e slugs
├── observability/  correlação e métricas
└── config/         propriedades tipadas, OpenAPI, beans comuns
src/main/resources/db/migration   migrações Flyway
docs/adr                          registros de decisão
docker/wiremock                   mapeamentos do mock do provedor
scripts                           geração de token de desenvolvimento
```
