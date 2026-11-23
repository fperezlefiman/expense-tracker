# Decisiones de arquitectura (ADR)

Un ADR registra **una** decisión, con el contexto en el que se tomó y sus consecuencias. Son
inmutables: una decisión no se edita, se reemplaza por un ADR nuevo que la supersede.

## Índice

| # | Título | Estado | Fecha |
|---|---|---|---|
| [0001](0001-java-25-spring-boot-4.md) | Java 25 LTS + Spring Boot 4.2 | aceptado | 2026-11-23 |
| [0002](0002-flyway-not-liquibase.md) | Flyway, Hibernate en `validate` | aceptado | 2026-11-23 |
| [0003](0003-raw-emails-and-idempotency.md) | Correo crudo antes de extraer, webhooks idempotentes | aceptado | 2026-11-23 |
| [0004](0004-llm-behind-expense-extractor.md) | El LLM detrás de `ExpenseExtractor` | aceptado | 2026-11-23 |
| [0005](0005-deploy-cloud-run.md) | Deploy en Cloud Run + Pub/Sub con OIDC | propuesto | 2026-11-23 |
| [0006](0006-multi-tenant-from-v1.md) | `account_id` desde la primera migración | aceptado | 2026-11-23 |
| [0007](0007-ingestion-resilience.md) | Resiliencia de la ingesta: watch, reintentos, backfill | aceptado | 2026-11-23 |
| [0008](0008-worker-triggered-by-scheduler.md) | Pub/Sub es transporte de entrada, no cola de trabajos | aceptado | 2026-11-23 |

## Convenciones

- Nombre: `NNNN-titulo-corto.md`, numeración incremental, sin volver a usar un número.
- Secciones fijas: **Contexto**, **Decisión**, **Consecuencias**, **Alternativas rechazadas**.
- Una decisión por archivo. Si el título necesita un "y", son dos ADRs.
- Cambiar de opinión = ADR nuevo con `Reemplaza a NNNN` en el encabezado, y el viejo pasa a
  `reemplazado por NNNN`.
