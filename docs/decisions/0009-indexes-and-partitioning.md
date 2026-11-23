# 0009 — Índices parciales ahora, particiones diferidas

**Estado:** aceptado · **Fecha:** 2026-11-23

## Contexto

El modelo tiene las entidades y la clave de idempotencia, pero no tenía definidos los índices que el
flujo realmente usa, ni una decisión sobre particionado y retención. Tres cosas se cruzan:

1. El worker consulta **pendientes** cada minuto; esa consulta tiene que ser barata cuando la tabla
   tenga millones de filas.
2. La **purga por retención** (90 días) tiene que poder borrar sin bloquear ni inflar la tabla.
3. La regla de Postgres: **en una tabla particionada, toda restricción única debe incluir la columna de
   partición** (documentado en Postgres 18).

Ese último punto choca de frente con `unique (account_id, gmail_message)`: si se particiona por
`received_at`, la unicidad pasa a ser *por mes* y el mismo correo llegando en dos meses distintos —o en
el límite del corte— se duplicaría. **Particionar de entrada rompería el ADR 0003.**

## Decisión

1. **Índices parciales para el trabajo pendiente**, no índices completos:

   ```sql
   create index raw_emails_pending_idx on raw_emails (next_attempt_at)
     where processed_at is null and attempts < 5;
   ```

   Un índice sobre `processed_at` se degrada porque casi todas las filas terminan procesadas; el
   parcial indexa solo lo vivo y se mantiene en kilobytes.

2. **BRIN para la purga**:

   ```sql
   create index raw_emails_received_brin on raw_emails using brin (received_at);
   ```

   La tabla es append-only y `received_at` correlaciona con el orden físico: BRIN ocupa una fracción de
   un B-tree y alcanza para un `delete ... where received_at < now() - interval '90 days'`.

3. **Sin particiones en el v0**, con umbral explícito de revisión: **~5–10 millones de filas en
   `raw_emails`** o una purga que tarde más de un par de segundos.

4. **La purga no puede llevarse los gastos**: `expenses.raw_email_id` es `null` y
   `references raw_emails(id) on delete set null`. El gasto es el dato de valor; el crudo es el insumo y
   es desechable.

5. **Contadores de cuota en tabla propia** (`account_usage` por cuenta y período), no `count(*)` sobre
   `raw_emails`: es la lectura barata para cuotas y la base del cobro futuro.

## Consecuencias

- La consulta del worker sigue siendo un index scan sobre cientos de filas aunque `raw_emails` crezca.
- La purga es un `delete` por lotes con índice BRIN; hay que ejecutarla por tramos para no bloquear, pero
  el costo de mantenimiento es bajo.
- Cuando se llegue al umbral, la salida no es relajar la unicidad sino **tabla de deduplicación aparte**:
  una fila por `(account_id, gmail_message)` fuera de la tabla particionada, con el insert del crudo
  validando contra ella. Cuesta dos escrituras; relajar la unicidad cuesta datos duplicados.
- El esquema queda preparado para particionar sin cambios de tipo: `received_at` ya es la columna de
  corte natural.

## Alternativas rechazadas

- **Particionar por mes desde V1**: rompe la unicidad global de `gmail_message` (regla de Postgres), o
  exige la tabla de deduplicación desde el día uno: complejidad sin volumen que la justifique.
- **Particionar y aceptar unicidad mensual**: viola el ADR 0003. La idempotencia es la garantía central,
  no un detalle.
- **Purga con `CASCADE`**: borraría los gastos junto con el crudo. El dato de valor se queda.
- **`delete` sin índice**: con retención mensual acumulada, cada purga recorrería la tabla completa.
- **Contar la cuota con `count(*)`**: escanea por cada request y crece con el histórico.
