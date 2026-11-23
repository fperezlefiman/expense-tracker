# 0006 — `account_id` desde la primera migración

**Estado:** aceptado · **Fecha:** 2026-11-23

## Contexto

El sistema arranca como tracker personal, pero el objetivo es que pueda operar como producto: varias
cuentas, cuotas por cuenta y, eventualmente, cobro. Agregar multi-tenencia después obliga a migrar
todas las tablas, reescribir las claves únicas y revisar cada consulta.

## Decisión

Todas las tablas llevan `account_id` desde la migración **V1**, aunque hoy exista una sola fila:

- `accounts` con `email`, `plan` y `monthly_email_quota`.
- Claves únicas **compuestas por cuenta**, empezando por `(account_id, gmail_message)`.
- Las credenciales de Gmail van en tabla propia por cuenta, con el refresh token **cifrado**
  (envelope encryption con KMS), nunca en `application.yml`.

## Consecuencias

- El costo hoy es una columna y un `where`; el costo de retrofitearlo después es una migración de
  riesgo alto con datos de clientes encima.
- Las cuotas y el futuro modelo de cobro se construyen sobre `monthly_email_quota` y el conteo por
  cuenta, sin cambiar el esquema.
- El aislamiento entre cuentas se puede testear desde el primer día (un `account_id` no ve datos de
  otro).
- Un poco más de fricción en las consultas y en los tests: cada caso necesita una cuenta.

## Alternativas rechazadas

- **Single-tenant y "después lo vemos"**: deuda barata de escribir y carísima de pagar.
- **Base o esquema por cuenta**: aislamiento fuerte, pero complica migraciones y pooling desde el día
  uno. Para decenas o cientos de cuentas, `account_id` alcanza.
