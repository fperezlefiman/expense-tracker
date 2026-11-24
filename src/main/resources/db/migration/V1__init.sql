-- Fase 1: solo lo que la ingesta necesita. El resto entra con su fase.

create table accounts (
    id         uuid primary key,
    email      text not null unique,
    currency   char(3) not null default 'CLP',
    locale     text not null default 'es-CL',
    created_at timestamptz not null default now()
);

-- Contenedor genérico desde la V1: el correo es solo un tipo de documento (ADR 0016).
create table raw_documents (
    id           uuid primary key,
    account_id   uuid not null references accounts (id),
    kind         text not null,
    source_ref   text not null,
    payload      jsonb not null,
    received_at  timestamptz not null default now(),
    processed_at timestamptz,
    attempts     int not null default 0,
    unique (account_id, kind, source_ref)
);

-- Cola de trabajo: índice parcial, solo lo pendiente (ADR 0009).
create index raw_documents_pending_idx on raw_documents (received_at)
    where processed_at is null;
