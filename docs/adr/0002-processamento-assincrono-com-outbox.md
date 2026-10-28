# ADR 0002 — Processamento assíncrono com outbox na própria tabela

- **Status:** aceita

## Contexto

Após criar a solicitação é preciso chamar o provedor externo. Duas perguntas do enunciado:

1. O `POST` espera o provedor ou responde logo?
2. Como garantir que uma solicitação gravada seja enviada mesmo se a aplicação cair entre o commit e a chamada HTTP?

## Decisão

**O `POST` só grava (`PENDING`) e responde `201`.** O envio é feito por um worker interno.

- **Tempo de resposta:** a latência da API fica independente do provedor. Se ele ficar lento (400 ms → 3 s no cenário
  do item 10), isso não consome threads de requisição nem aumenta o tempo de resposta ao cliente.
- **Outbox na própria linha:** a tabela `activation_request` é a fila. `PENDING` com `next_attempt_at <= agora`
  significa "falta enviar". Não existe um segundo sistema (broker) nem gravação dupla (banco + fila), logo não há o
  problema de "gravei no banco mas não publiquei". Se a aplicação cair depois do commit, a linha continua `PENDING`
  e o próximo ciclo a envia.
- **Sem HTTP dentro de transação:** o ciclo é (1) transação curta que reserva as linhas
  (`SELECT ... FOR UPDATE SKIP LOCKED` → `PROCESSING`), (2) chamada ao provedor **sem** transação, (3) transação curta que
  grava o resultado. Conexões do pool nunca ficam presas esperando o provedor.
- **Várias instâncias:** `SKIP LOCKED` faz cada instância pegar linhas diferentes, sem fila externa e sem lock de
  aplicação.
- **Reconciliação:** se a instância cair entre (1) e (3), a linha fica em `PROCESSING`. `StalledRequestReconciler`
  devolve para `PENDING` o que está em `PROCESSING` há mais de `stale-after` (ou marca `FAILED` se as tentativas
  acabaram). Reenviar é seguro por causa da chave de idempotência (ADR 0003).
- **Concorrência de escrita:** `@Version` (lock otimista) protege a atualização de status; o resultado de uma chamada
  só é aplicado se a linha ainda está em `PROCESSING` **na mesma tentativa** que foi enviada.

## Alternativas descartadas

- **Síncrono:** mais simples, mas o tempo de resposta e a disponibilidade da API passam a depender do provedor, e
  uma queda entre a chamada e o commit deixa o provedor ativado sem registro local.
- **Tabela outbox separada + broker:** correto e mais escalável, porém excessivo para este escopo. A evolução natural
  está descrita no README (publicar `ActivationFinished` em fila/tópico).

## Consequências

- Consistência eventual: o cliente consulta `GET /api/v1/requests/{id}` (ou, no futuro, recebe um evento).
- Latência mínima de envio = intervalo de polling (`activation.worker.poll-interval`, padrão 1 s).
