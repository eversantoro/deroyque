-- Solicitacoes de ativacao. A propria tabela e a fila de envio ao provedor (outbox).
create table activation_request (
    id                    uuid         primary key,
    external_id           varchar(64)  not null,
    customer_id           varchar(64)  not null,
    product_code          varchar(64)  not null,
    owner_id              varchar(128) not null,
    status                varchar(16)  not null,
    attempts              integer      not null default 0,
    next_attempt_at       timestamptz  not null,
    processing_started_at timestamptz,
    failure_reason        varchar(255),
    correlation_id        varchar(64),
    created_at            timestamptz  not null,
    updated_at            timestamptz  not null,
    version               bigint       not null,

    -- Garantia final contra duplicidade: vale mesmo com requisicoes simultaneas e varias instancias.
    constraint uk_activation_request_external_id unique (external_id),
    constraint ck_activation_request_status check (status in ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    constraint ck_activation_request_attempts check (attempts >= 0)
);

-- Fila de envio: so indexa o que ainda precisa ser processado (indice pequeno e quente).
create index ix_activation_request_due
    on activation_request (next_attempt_at)
    where status = 'PENDING';

-- Reconciliacao de solicitacoes presas em PROCESSING.
create index ix_activation_request_stalled
    on activation_request (processing_started_at)
    where status = 'PROCESSING';

-- Listagem paginada por consumidor, mais recentes primeiro, com filtros opcionais.
create index ix_activation_request_owner_created
    on activation_request (owner_id, created_at desc, id desc);

create index ix_activation_request_owner_status_created
    on activation_request (owner_id, status, created_at desc, id desc);

create index ix_activation_request_owner_customer_created
    on activation_request (owner_id, customer_id, created_at desc, id desc);
