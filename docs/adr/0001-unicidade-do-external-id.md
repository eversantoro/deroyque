# ADR 0001 — Unicidade do `externalId`

- **Status:** aceita

## Contexto

Sistemas de origem fazem retry, e duas requisições idênticas podem chegar quase ao mesmo tempo, inclusive em
instâncias diferentes da aplicação. Nenhuma solução em memória (lock, `synchronized`, cache) funciona com várias
instâncias.

## Decisão

1. A garantia final fica **no banco**: `constraint uk_activation_request_external_id unique (external_id)`.
2. A aplicação **insere primeiro** e trata a violação de unicidade como o caso "já existe"
   (`ActivationRequestService.create`). A inserção roda em uma transação curta; depois do erro, o registro existente
   é lido **fora** dela (em PostgreSQL uma transação que sofreu erro fica abortada e não pode mais ler).
3. `externalId` é **globalmente** único (o enunciado diz "não deve criar duas solicitações com o mesmo externalId").

### Por que "consultar e depois inserir" não basta

```
T1: SELECT ... WHERE external_id = 'X'  -> vazio
T2: SELECT ... WHERE external_id = 'X'  -> vazio
T1: INSERT ... 'X'                      -> ok
T2: INSERT ... 'X'                      -> duplicado (sem o índice único)
```

A verificação e a escrita são dois passos; entre eles outra transação pode inserir. Só uma operação atômica no banco
(o índice único) fecha a janela. A consulta prévia seria apenas uma otimização, nunca a garantia.

## Semântica da resposta para repetições

| Cenário | Resposta | Justificativa |
|---|---|---|
| Primeira requisição | `201 Created` + `Location` | Recurso criado. |
| Mesmo `externalId`, mesmo consumidor, mesmo `customerId` e `productCode` (retry legítimo) | `200 OK` com o registro existente + `Location` | O POST é idempotente do ponto de vista do cliente: repetir produz o mesmo resultado, sem erro que o obrigue a tratar exceção. |
| Mesmo `externalId`, conteúdo diferente | `409 Conflict` (`duplicate-request`) | O cliente está tentando reutilizar um identificador para outra coisa: é um erro dele. |
| `externalId` de **outro consumidor** | `409 Conflict`, mesma mensagem | Não revela que o registro existe nem seu conteúdo (evita vazamento entre consumidores). |
| Duas requisições idênticas em paralelo | Uma recebe `201`, a outra `200` com o **mesmo** registro | A perdedora espera o commit da vencedora (o índice único bloqueia até lá) e então lê o registro. |

## Consequências

- Correto com N instâncias e sob concorrência (provado em `DuplicateRequestIT`, 25 threads, PostgreSQL real).
- O `409` não diferencia "conteúdo diferente" de "outro consumidor", de propósito.
