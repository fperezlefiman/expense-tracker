# Decisiones de arquitectura (ADR)

Un ADR registra **una** decisión, con el contexto en el que se tomó y sus consecuencias. Son
inmutables: una decisión no se edita, se reemplaza por un ADR nuevo que la supersede.

## Índice

Agrupado por tema: quien llega busca el área, no el número.

### Stack

| # | Decisión | Estado | Dónde se aplica |
|---|---|---|---|
| [0001](0001-java-25-spring-boot-4.md) | Java 25 LTS + Spring Boot 4.2 | aceptado | `pom.xml`, `docs/operations.md` |
| [0002](0002-flyway-not-liquibase.md) | Flyway, Hibernate en `validate` | aceptado | `docs/operations.md` (pipeline) |

### Datos

| # | Decisión | Estado | Dónde se aplica |
|---|---|---|---|
| [0003](0003-raw-emails-and-idempotency.md) | Correo crudo antes de extraer, webhooks idempotentes | aceptado | `docs/architecture.md` |
| [0006](0006-multi-tenant-from-v1.md) | `account_id` desde la primera migración | aceptado | `docs/architecture.md` |
| [0009](0009-indexes-and-partitioning.md) | Índices parciales ahora, particiones diferidas | aceptado | `docs/architecture.md` (índices y retención) |
| [0011](0011-dedupe-survives-purge.md) | La deduplicación de gastos sobrevive a la purga | aceptado | `docs/architecture.md` (ER) |
| [0013](0013-categories-and-locale.md) | Categorías del usuario, moneda e idioma por cuenta | aceptado | `docs/architecture.md` (ER, índices), `docs/ui.md` |

### Ingesta, extracción y cuotas

| # | Decisión | Estado | Dónde se aplica |
|---|---|---|---|
| [0004](0004-llm-behind-expense-extractor.md) | El LLM detrás de `ExpenseExtractor` | aceptado | `docs/evals.md` |
| [0007](0007-ingestion-resilience.md) | Resiliencia de la ingesta: watch, reintentos, backfill | aceptado | `docs/operations.md` (runbooks) |
| [0008](0008-worker-triggered-by-scheduler.md) | Pub/Sub es transporte de entrada, no cola de trabajos | aceptado | `docs/architecture.md` (secuencia) |
| [0012](0012-quota-enforcement.md) | Punto de aplicación de la cuota | aceptado | `docs/operations.md` (alertas) |
| [0014](0014-templates-first-llm-fallback.md) | Plantillas primero, LLM como fallback, monto validado | aceptado | `docs/architecture.md` (flujo), `docs/evals.md` |
| [0015](0015-auditable-enrichment.md) | Enriquecimiento semántico auditable (`origin`) | aceptado | `docs/architecture.md` (ER), `docs/ui.md` |

### API, deploy y acceso

| # | Decisión | Estado | Dónde se aplica |
|---|---|---|---|
| [0005](0005-deploy-cloud-run.md) | Deploy en Cloud Run + Pub/Sub con OIDC | aceptado | `docs/operations.md` (pipeline) |
| [0010](0010-api-authentication.md) | Autenticación de la API: API key por cuenta | aceptado | `docs/architecture.md`, `docs/operations.md` |

## Convenciones

- Nombre: `NNNN-titulo-corto.md`, numeración incremental, sin volver a usar un número.
- Secciones fijas: **Contexto**, **Decisión**, **Consecuencias**, **Alternativas rechazadas**.
  Opcional al final: **Relación con otros ADR**, cuando la decisión precisa el alcance de otra sin
  reemplazarla.
- Una decisión por archivo. Si el título necesita un "y", son dos ADRs.
- **Umbral para escribir un ADR**: al menos dos alternativas que un ingeniero tomaría en serio y una
  elección que cuesta revertir. Si no cumple eso, es una línea en el documento que corresponda, no un
  archivo nuevo.
- Cambiar de opinión = ADR nuevo con `Reemplaza a NNNN` en el encabezado, y el viejo pasa a
  `reemplazado por NNNN`.
