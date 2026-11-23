# 0010 — Autenticación de la API: API key por cuenta

**Estado:** aceptado · **Fecha:** 2026-11-23

## Contexto

El sistema es multi-tenant desde V1 (ADR 0006) y la API de resumen es el único servicio con ingress
público. Hasta ahora solo estaba definido el rate limiting: eso protege el cupo gratuito, **no los
datos**. Sin autenticación, cualquiera con la URL lee los gastos de todas las cuentas.

## Decisión

- Cada cuenta tiene una o más **API keys**. El cliente envía `Authorization: Bearer <key>`.
- Se guarda solo el **hash** de la key (Argon2 o bcrypt), más un `key_prefix` de 8 caracteres para
  identificarla en logs sin exponerla.
- Un filtro de Spring resuelve la key a un `account_id` y lo deja en el contexto del request.
- **El `account_id` nunca se acepta como parámetro del cliente**: se deriva siempre de la key.
- Las keys se pueden revocar (`revoked_at`) y rotar. El webhook de Gmail **no** usa API key: sigue
  autenticado por OIDC de Pub/Sub.

## Consecuencias

- Un request sin key válida devuelve `401`; con key de otra cuenta, `404` (no `403`: no se confirma la
  existencia del recurso).
- Toda consulta lleva `account_id` desde el contexto, así que el aislamiento entre cuentas se puede
  testear con un test que intente leer datos ajenos.
- El costo por cuenta se puede atribuir (métricas con `account_id` como dimensión).
- En el v0 el usuario gestiona su propia key por consola; no hay pantalla de registro ni de sesión.
  Eso queda fuera de alcance hasta que haya cobro.

## Alternativas rechazadas

- **Sin autenticación + rate limiting**: deja los datos abiertos; el rate limiting no es seguridad.
- **OAuth2 con login y sesiones**: correcto para un producto con UI, mucho trabajo para una API que
  consume un cliente propio.
- **JWT emitido por nosotros**: agrega emisión, rotación y revocación; una key opaca con hash hace lo
  mismo con la mitad del código.
- **mTLS**: fuerte y desproporcionado para este caso.
