# expense-tracker

Backend de un tracker de gastos que se alimenta solo: recibe los push de Gmail, extrae el gasto,
lo guarda normalizado en PostgreSQL y lo expone en una API de resumen.

Está diseñado como un sistema de producción, no como un proyecto personal: webhooks idempotentes,
extracción determinística por plantilla con un LLM como fallback, y un guard que impide que el modelo
invente un monto. Reintentos con dead letter, evals medidas sobre lo que el modelo decide, costo por
correo y alertas con runbook. Cada decisión tiene su ADR para poder defenderla en voz alta.

> **Estado:** Fase 1 implementada — webhook idempotente, 9 tests verdes y p95 medido. Las siguientes fases
> entran por incrementos según [`docs/roadmap.md`](docs/roadmap.md); cada una cierra su gate antes de abrir
> la próxima.

**Stack:** Java 25 (LTS) · Spring Boot 4.2 · PostgreSQL · Flyway · Spring AI 2.1 · Testcontainers ·
Pub/Sub · Cloud Run · GitHub Actions.

---

## Arquitectura

```mermaid
flowchart LR
    G[Gmail] -->|"push via Pub/Sub<br/>OIDC"| W["Webhook<br/>(Cloud Run, privado)"]
    W --> ID{"¿ya existe<br/>(cuenta, kind, historyId)?"}
    ID -->|"nuevo"| RAW[("raw_documents<br/>(jsonb)")]
    ID -->|"duplicado"| NOOP["200 · no-op"]
    RAW -.->|"pendientes: SKIP LOCKED"| WK["Worker<br/>(Cloud Run, privado)"]
    SCH["Cloud Scheduler<br/>cada 1 min (OIDC)"] -->|"POST /internal/extract"| WK
    WK -->|"consume"| EX["ExpenseExtractor<br/>(Spring AI)"]
    EX --> VAL{"¿pasa las<br/>invariantes?"}
    VAL -->|"sí"| EXP[("expenses")]
    VAL -->|"no"| RETRY["pendiente<br/>+ métrica"]
    EXP -->|"lee"| API["API de resumen"]
```

Tres ideas sostienen el diseño:

1. **El webhook no piensa.** Responde `200` solo después de guardar el correo crudo; `5xx` cuando no
   puede, para que Pub/Sub reintente. Un LLM dentro del request es una garantía de timeouts y
   duplicados.
2. **El crudo se guarda antes de interpretarlo.** Un cambio de prompt se reprocesa sobre lo
   guardado, sin volver a pedirle nada a Gmail.
3. **La idempotencia es una clave única** (`raw_documents`: `(account_id, kind, source_ref)`, con
   `source_ref = historyId`), no un `if` en memoria. Se llavea por `historyId` y no por el `messageId`
   de Pub/Sub: dos avisos distintos del mismo cambio del buzón tienen que colapsar en una fila.
4. **La tabla es la cola.** Pub/Sub solo trae el aviso; el trabajo pendiente vive en `raw_documents` y el
   worker lo toma con `SKIP LOCKED`. No hay dos registros del mismo trabajo que puedan desincronizarse
   (ADR 0008).
5. **El dinero no lo decide el modelo.** Las plantillas resuelven el formato conocido; el LLM entra solo
   cuando no hay plantilla, y un `MoneyGuard` exige que el monto aparezca literalmente en el correo. Si
   no aparece, el gasto no se crea: queda en revisión (ADR 0014).

## Decisiones

| # | Decisión | Por qué |
|---|---|---|
| [0001](docs/decisions/0001-java-25-spring-boot-4.md) | Java 25 + Spring Boot 4.2 | LTS vigente y el mayor volumen de ofertas backend en Chile |
| [0002](docs/decisions/0002-flyway-not-liquibase.md) | Flyway con `ddl-auto: validate` | El esquema es SQL revisable; Hibernate solo valida |
| [0003](docs/decisions/0003-raw-emails-and-idempotency.md) | Correo crudo antes de extraer | Reprocesable y sin pérdida de datos |
| [0004](docs/decisions/0004-llm-behind-expense-extractor.md) | LLM detrás de `ExpenseExtractor` | Sin lock-in, testeable con un fake |
| [0005](docs/decisions/0005-deploy-cloud-run.md) | Cloud Run + Pub/Sub con OIDC | El push ya vive en GCP; el endpoint queda privado |
| [0006](docs/decisions/0006-multi-tenant-from-v1.md) | `account_id` desde V1 | Multi-tenant retroactivo es una migración de terror |
| [0007](docs/decisions/0007-ingestion-resilience.md) | Ingesta resiliente | El watch vence a los 7 días y falla en silencio |
| [0008](docs/decisions/0008-worker-triggered-by-scheduler.md) | Pub/Sub solo como transporte de entrada | La tabla es la cola: una sola fuente de verdad |
| [0009](docs/decisions/0009-indexes-and-partitioning.md) | Índices parciales, particiones diferidas | Particionar rompería la unicidad de `(account_id, kind, source_ref)` |
| [0010](docs/decisions/0010-api-authentication.md) | API key por cuenta | Sin auth, el rate limiting protege el cupo, no los datos |
| [0011](docs/decisions/0011-dedupe-survives-purge.md) | El gasto guarda su `source_ref` | La unicidad no puede depender de una fila que se purga |
| [0012](docs/decisions/0012-quota-enforcement.md) | La cuota bloquea la extracción, no la ingesta | Un correo recibido no se descarta nunca |
| [0013](docs/decisions/0013-categories-and-locale.md) | Categorías del usuario, moneda e idioma por cuenta | La home es el gasto del mes por categoría: el texto libre la rompe |
| [0014](docs/decisions/0014-templates-first-llm-fallback.md) | Plantillas primero, LLM como fallback, monto validado | Un monto inventado es indistinguible de uno real |
| [0015](docs/decisions/0015-auditable-enrichment.md) | Enriquecimiento con `origin` auditable | Sin saber quién decidió, no hay forma de medir |

## Documentación

- [`docs/architecture.md`](docs/architecture.md) — contexto, secuencia de un correo, modelo de datos.
- [`docs/roadmap.md`](docs/roadmap.md) — incrementos por fase, con el gate de salida de cada una.
- [`docs/decisions/`](docs/decisions/) — ADRs, uno por decisión, con estado.
- [`docs/operations.md`](docs/operations.md) — deploy, alertas y runbooks, costos, límites de Google.
- [`docs/privacy.md`](docs/privacy.md) — qué se guarda, para qué, hasta cuándo y quién lo trata.
- [`docs/ui.md`](docs/ui.md) — qué se muestra: home por categoría, estados y contrato con el backend.
- [`docs/evals.md`](docs/evals.md) — dataset, métrica y resultados medidos.

## Cómo se corre

```bash
docker run -d --name expense-pg -p 5432:5432 \
  -e POSTGRES_DB=expense -e POSTGRES_USER=expense -e POSTGRES_PASSWORD=expense \
  postgres:18-alpine
mvn spring-boot:run          # Flyway aplica V1 al arrancar
mvn verify                   # tests + integración (Testcontainers, requiere Docker)
```

El webhook escucha en `POST /gmail/notifications` y **exige un token OIDC de Google** con la audiencia
`PUBSUB_AUDIENCE` y el email `PUBSUB_SERVICE_ACCOUNT`. El token tiene que estar vigente, venir del emisor
de Google y traer esa audiencia y ese email: si falla cualquiera de las cuatro cosas, devuelve `401`.
Las dos variables son **obligatorias**: sin ellas la aplicación no arranca (`PUBSUB_AUDIENCE=… mvn spring-boot:run`).

## Números medidos

| Qué | Valor (3 corridas) | Cómo |
|---|---|---|
| p50 del webhook | 10–18 ms | 10 concurrentes × 10 envíos + 50 de calentamiento, Postgres local |
| p95 del webhook | **44–58 ms** | mismo gate, p95 < 150 ms en `IngestLatencyTest` |

Medido en `IngestLatencyTest`, **después** del calentamiento: sin él el p95 lo dominaba el JIT y variaba
entre 190 y 460 ms con el mismo binario, así que no medía el webhook sino la máquina. **No es el número de
Cloud Run**: ahí se suma el salto de red, pero también hay pool gestionado y disco distinto. El valor de la
cifra es que es reproducible y que cualquier regresión aparece en CI.

## Fuera de alcance

- Sincronización bancaria y conciliación de cartolas (fases 4 y 5 del roadmap).
- UI propia: el entregable es la API; un front iría en otro repositorio.
- Facturación: el diseño deja lugar (`account_id`, cuotas por cuenta), pero no se implementa todavía.
