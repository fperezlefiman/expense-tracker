# Arquitectura

Este documento describe **cómo está armado** el sistema. Las decisiones y su justificación viven en
[`decisions/`](decisions/); acá solo se describe el resultado.

## Contexto

```mermaid
flowchart TB
    subgraph Google
        GM[Gmail]
        PS[Pub/Sub]
    end
    subgraph GCP["Google Cloud Run"]
        WH["webhook<br/>sin ingress público"]
        WK["worker<br/>sin ingress"]
        API["API de resumen<br/>pública"]
    end
    subgraph Datos
        PG[("PostgreSQL")]
    end
    CF1["Cloudflare<br/>DNS · WAF · rate limit"]
    U["Usuario / cliente"]

    GM -->|"watch"| PS
    PS -->|"push OIDC"| WH
    WH --> PG
    WH -->|"publica job"| PS
    PS -->|"push OIDC"| WK
    WK --> PG
    WK -->|"LLM"| EXT["Spring AI"]
    CF1 --> API
    U --> CF1
    API --> PG
```

## Flujo de un correo

```mermaid
sequenceDiagram
    autonumber
    participant P as Pub/Sub
    participant W as Webhook
    participant DB as PostgreSQL
    participant Q as Worker
    participant AI as LLM

    P->>W: POST /gmail/notifications
    W->>DB: insert raw_emails (on conflict do nothing)
    W-->>P: 200 (sin extracción)
    Q->>DB: tomar pendientes (processed_at null, lock con expiración)
    Q->>AI: correo + schema esperado
    AI-->>Q: JSON del gasto
    alt válido
        Q->>DB: insert expenses + processed_at
    else inválido o timeout
        Q->>DB: attempts++ (backoff, hasta 5 → dead letter)
    end
```

## Modelo de datos

```mermaid
erDiagram
    ACCOUNTS ||--o{ RAW_EMAILS : owns
    RAW_EMAILS ||--o| EXPENSES : produces

    ACCOUNTS {
        uuid id PK
        text email UK
        text plan
        int monthly_email_quota
        timestamptz created_at
    }
    RAW_EMAILS {
        uuid id PK
        uuid account_id FK
        text gmail_message "unique por cuenta: idempotencia"
        jsonb payload
        timestamptz received_at
        timestamptz processed_at "null = pendiente"
        int attempts
        timestamptz locked_at
        text locked_by
    }
    EXPENSES {
        uuid id PK
        uuid account_id FK
        uuid raw_email_id FK
        numeric amount
        char currency "ISO-4217"
        date occurred_on
        text description
        text category
    }
```

Notas que importan más que las columnas:

- **La clave de idempotencia es por cuenta**: `(account_id, gmail_message)` único.
- `attempts`, `locked_at` y `locked_by` existen para que un worker que muere no bloquee la fila.
- `payload` en `jsonb` guarda el correo completo: es el insumo de la reprocesabilidad y de las evals.

## Límites del sistema

- **Ingesta**: un correo cada pocos segundos por cuenta; picos de ráfaga al abrir el correo, no
  tráfico masivo.
- **Extracción**: 1 llamada al LLM por correo, con timeout de 20 s y circuit breaker.
- **Retención**: `raw_emails` se purga a los 90 días (ver ADR 0007).
- **Consistencia**: eventual entre ingesta y gasto visible; el usuario no necesita lectura inmediata.
