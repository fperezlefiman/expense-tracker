# 0007 — Resiliencia de la ingesta: watch, reintentos y backfill

**Estado:** aceptado · **Fecha:** 2026-11-23

## Contexto

La ingesta depende de dos servicios de Google con modos de falla propios: el `watch` de Gmail y la
suscripción push de Pub/Sub. El peor caso no es un error visible, es **dejar de recibir correos sin
que nada falle**: el `watch` expira a los 7 días y, vencido, el sistema sigue respondiendo `200` a
todo y no procesa nada.

## Decisión

1. **Renovación del watch** cada 24 h (con jitter), guardando la fecha de expiración en base.
2. **Semántica de códigos explícita**: `2xx` solo tras insert durable; `5xx` para que Pub/Sub
   reintente; `4xx` para payload inválido, sin reintento infinito.
3. **Reintentos con backoff** y tope de `attempts` (5) en el worker; al agotarse, la fila queda en
   dead letter con estado `failed`, sin borrarse.
4. **Lock con expiración** (`locked_at`, `locked_by`) para que un worker que muere no bloquee trabajo.
5. **Backfill** con el último `historyId` (`users.history.list`) para recuperar lo ocurrido durante una
   caída, más un poll por consulta como último recurso.
6. **Purga de `raw_emails`** a los 90 días por retención de datos personales.

## Consecuencias

- Un fin de semana caído no pierde correos: Pub/Sub reintenta y, si superó la retención, el backfill
  recupera por `historyId`.
- Las alertas se pueden definir sobre síntomas reales (silencio de ingesta, edad del pendiente más
  viejo) en vez de sobre CPU. Ver `docs/operations.md`.
- Se agrega complejidad al worker (locks, backoff, estados) que no existiría en un sistema de juguete.
- La purga obliga a decidir desde el principio qué se guarda y cuánto: el crudo no es eterno.

## Alternativas rechazadas

- **Confiar en los reintentos de Pub/Sub solamente**: cubren minutos, no una caída de días ni un watch
  vencido.
- **Polling periódico como mecanismo principal**: más simple de razonar, pero gasta cuota y agrega
  latencia; se usa solo como red de seguridad.
