# ADR 0005 — Transições de estado controladas pelo domínio e lock otimista

- **Status:** aceita

## Decisão

- `ActivationRequest` não tem setters de status. Mudanças acontecem por métodos de negócio
  (`startProcessing`, `complete`, `fail`, `scheduleRetry`, `release`) que validam a transição em
  `RequestStatus.canTransitionTo` e lançam `InvalidStatusTransitionException` quando inválida.
- Transições permitidas:

```
PENDING ──► PROCESSING ──► COMPLETED
                │  └─────► FAILED
                └────────► PENDING   (falha temporária / reconciliação)
```

`COMPLETED` e `FAILED` são terminais. `PENDING → COMPLETED/FAILED` não existe: toda solicitação passa por
`PROCESSING`, que representa "alguém está com ela".

- **`@Version` (lock otimista):** se duas instâncias (ou o reconciliador e o worker) tentarem atualizar a mesma
  linha, a segunda recebe `ObjectOptimisticLockingFailureException` e descarta o resultado, em vez de sobrescrever.
  Além disso, o resultado de uma chamada só é aplicado se a linha ainda está `PROCESSING` na mesma tentativa enviada.
- **`SELECT ... FOR UPDATE SKIP LOCKED`** na reserva evita que duas instâncias peguem a mesma linha.

## Padrões de projeto usados (e por quê)

| Padrão | Onde | Motivo |
|---|---|---|
| **State** (leve, no enum) | `RequestStatus` | Regras de transição em um só lugar, testáveis sem Spring. |
| **Factory method** | `ActivationRequest.create`, `ProviderRequest.from` | Garante o estado inicial válido e evita construtores públicos soltos. |
| **Ports & Adapters** | `ActivationProvider` (porta) / `HttpActivationProvider` (adaptador) | O núcleo não conhece HTTP; trocar de provedor ou testar com stub não afeta a lógica. |
| **Strategy** | `BackoffPolicy` / `ExponentialBackoffPolicy` | Política de espera substituível e testável. |
| **Repository** | `ActivationRequestRepository` | Acesso a dados atrás de uma interface. |
| **Observer** | `ActivationFinished` + `ActivationFinishedListener` | Ponto de extensão (métricas hoje; fila/tópico amanhã) sem acoplar o processador. |
| **Chain of Responsibility** | filtros (correlação, tamanho, rate limit, segurança) | Preocupações transversais isoladas da regra de negócio. |
| **Sealed types + pattern matching** | `ProviderOutcome` | O compilador garante que todo resultado do provedor é tratado. |

Nada além disso foi introduzido: sem camadas de "service de service", sem interfaces com uma única implementação
sem motivo (a exceção é a porta do provedor, que tem fronteira real).
