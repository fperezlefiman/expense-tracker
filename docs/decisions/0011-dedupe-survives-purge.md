# 0011 — La deduplicación de gastos sobrevive a la purga

**Estado:** aceptado · **Fecha:** 2026-11-23

## Contexto

El ADR 0009 garantiza "una sola extracción por correo" con un índice único sobre
`expenses (raw_email_id)`. El ADR 0007 purga `raw_emails` a los 90 días y el ADR 0009 define
`on delete set null` para no arrastrar los gastos.

Ese par de decisiones deja un agujero: cuando el crudo se borra, el vínculo desaparece. Si un backfill
vuelve a traer ese correo, entra un `raw_emails` nuevo, se extrae de nuevo y **el gasto se duplica**,
porque la clave única ya no tiene contra qué chocar. El bug aparece meses después y nadie lo relaciona
con la purga.

## Decisión

`expenses` guarda también el identificador del correo de origen:

```sql
alter table expenses add column gmail_message text not null;
create unique index expenses_message_uk on expenses (account_id, gmail_message);
```

La extracción inserta con `on conflict (account_id, gmail_message) do nothing`. `raw_email_id` se
mantiene como enlace mientras el crudo exista, pero **la garantía de unicidad ya no depende de él**.

## Consecuencias

- La idempotencia sobrevive a la purga, a un backfill y a una reingesta del mismo correo.
- El `insert` de la extracción es idempotente por sí mismo: un reintento del worker no puede duplicar.
- Cuesta una columna `text` por gasto (el id de Gmail ronda los 16 caracteres) y un índice único más.
- El crudo sigue siendo desechable, que es lo que el ADR 0007 necesita para poder purgar.

## Alternativas rechazadas

- **No purgar `raw_emails`**: resuelve la deduplicación y rompe la retención de datos personales.
- **Confiar solo en `raw_email_id`**: es lo que hoy está roto después de 90 días.
- **Deduplicar con el `historyId` de Gmail**: es un puntero móvil, no una identidad del mensaje; sirve
  para recuperar, no para identificar.
