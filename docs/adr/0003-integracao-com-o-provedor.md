# ADR 0003 — Integração com o provedor: retorno, retry, idempotência e resiliência

- **Status:** aceita

## Decisão por retorno do provedor

| Retorno do provedor | Novo status | Nova tentativa? | Justificativa |
|---|---|---|---|
| Sucesso (2xx) | `COMPLETED` | — | Ativação confirmada. |
| HTTP 400 (e demais 4xx exceto 408/425/429) | `FAILED` | Não | O provedor rejeitou o conteúdo; repetir a mesma requisição dará o mesmo erro. O motivo fica em `failureReason`. |
| HTTP 500 (e demais 5xx) | `PENDING` (aguarda backoff) | Sim, até o limite | Falha provavelmente temporária do provedor. |
| Timeout / erro de rede | `PENDING` (aguarda backoff) | Sim, até o limite | O resultado é **ambíguo** (o provedor pode ter ativado). Seguro repetir porque a chamada carrega chave de idempotência. |
| HTTP 429 / 503 com `Retry-After` | `PENDING` | Sim | Aguarda `max(backoff, Retry-After)`, limitado a `retry.max-delay`. 429 não conta para o circuit breaker (é o provedor pedindo para desacelerar). |
| Circuito aberto (breaker) | `PENDING` | Sim, **sem consumir tentativa** | A chamada nem foi feita; penalizar a solicitação seria injusto. |
| Limite de tentativas atingido | `FAILED` | Não | `Tentativas esgotadas (N): <último motivo>`. |

Falha temporária **volta para `PENDING`** (não fica em `PROCESSING`): `PROCESSING` significa "uma instância está com a
chamada em andamento", o que permite à reconciliação detectar órfãs com um critério simples (`PROCESSING` há mais de X).
O contador `attempts` registra as tentativas.

## Idempotência

Toda chamada envia `Idempotency-Key: <id da solicitação>` (e `X-Correlation-Id`). Se o provedor ativou mas a resposta se
perdeu (timeout), o reenvio com a mesma chave não ativa o produto de novo. Isso exige que o provedor honre a chave; é
parte do contrato assumido (o mock do `docker-compose` e os testes verificam o envio do cabeçalho).

## Retry

Backoff exponencial com *full jitter* (`ExponentialBackoffPolicy`, estratégia injetável): atraso sorteado entre 0 e
`min(max-delay, initial-delay × 2^(tentativa-1))`. O jitter evita que muitas solicitações falhas voltem juntas e
sobrecarreguem um provedor que está se recuperando.

## Timeouts explícitos

`activation.provider.connect-timeout` (2 s) e `read-timeout` (5 s) configuram o cliente HTTP (`RestClient` sobre o
`HttpClient` do JDK). Sem timeout, uma chamada pendurada prenderia a thread indefinidamente.

## Circuit breaker (Resilience4j)

Abre quando ≥ 50% das últimas 20 chamadas falham (mínimo de 10), por 30 s. Só falhas temporárias (5xx, timeout, rede)
contam; erros 4xx não. Enquanto aberto, as solicitações voltam para a fila sem chamar o provedor, protegendo-o e
poupando threads. Métricas do breaker são exportadas via Micrometer.
