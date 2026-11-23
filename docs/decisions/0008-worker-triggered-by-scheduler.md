# 0008 — Pub/Sub es transporte de entrada, no cola de trabajos

**Estado:** aceptado · **Fecha:** 2026-11-23

## Contexto

El diseño arrastraba una ambigüedad: el diagrama mostraba `Pub/Sub: jobs` para la fase de extracción,
mientras el worker tomaba filas pendientes de la tabla. Dos fuentes de verdad para el mismo trabajo
—un mensaje en el topic y una fila en la base— se desincronizan, y cuando eso pasa no hay forma barata
de saber cuál manda.

Además hay que distinguir dos fallos que se confunden todo el tiempo: **no poder guardar el correo** y
**no poder extraer el gasto**. No se reintentan en el mismo lugar.

## Decisión

1. **Pub/Sub tiene un solo rol: transporte de entrada.** Gmail publica en el topic y la suscripción
   push entrega al webhook. Es el único camino que existe; no es opcional.
2. **La tabla `raw_emails` es la cola de trabajo y la fuente de verdad.** El worker no consume de
   Pub/Sub: consulta pendientes con `FOR UPDATE SKIP LOCKED`, `attempts < 5` y lock vencido.
3. **El worker se dispara con Cloud Scheduler**, cada minuto, contra un endpoint interno autenticado
   con OIDC. Cada corrida es un request corto que drena hasta N pendientes y responde.
4. **Los dos reintentos viven en lugares distintos:**

   | Falla | Quién reintenta | Dónde se ve |
   |---|---|---|
   | Guardar el crudo (base caída, timeout) | Pub/Sub, por la respuesta `5xx` | métricas de la suscripción, dead letter topic opcional |
   | Extraer el gasto (LLM, JSON inválido) | El worker, contra la tabla | `attempts`, `next_attempt_at`, estado `failed` |

## Consecuencias

- Una sola fuente de verdad: una fila, un estado, un contador de intentos. No hay reconciliación entre
  topic y base porque no hay dos registros del mismo trabajo.
- Un fallo de extracción **no** reingesta el correo: reintentar la ingesta sería trabajo inútil cuando el
  dato ya está guardado.
- Latencia de hasta 60 s entre la recepción y la extracción. Irrelevante para gastos; si algún día
  importara, el webhook emite una "señal" que despierta al worker, sin mover la verdad a una cola.
- Cloud Scheduler entra en su cupo gratuito de 3 jobs; el worker sigue escalando a cero entre corridas.
- Se evita `min-instances = 1` con CPU permanente, que sería el único costo fijo del sistema.

## Alternativas rechazadas

- **`@Scheduled` dentro del servicio**: en Cloud Run el CPU se congela fuera de los requests; exigiría
  CPU permanente y una instancia mínima, es decir, un costo fijo 24/7.
- **Segundo topic de Pub/Sub para los jobs**: reintentos y DLQ gratis, pero sin estado del trabajo
  (`attempts`, `next_attempt_at`, quién lo tomó). Para depurar, la fila vale más que el `messageId`.
- **Cloud Run Jobs**: pensado para procesos batch largos; acá cada corrida dura segundos.
- **Procesar dentro del webhook**: ya descartado en el ADR 0003.

## Relación con otros ADR

No reemplaza al 0003: lo precisa. El 0003 dice que el crudo se guarda antes de extraer y que los push
son idempotentes; este ADR define **con qué mecanismo** se procesa después y **dónde** se reintenta cada
fallo.
