# 0012 — Punto de aplicación de la cuota

**Estado:** aceptado · **Fecha:** 2026-11-23

## Contexto

El ADR 0006 define `accounts.monthly_email_quota` y una tabla `account_usage` por período, pero ningún
documento decía **dónde** se verifica la cuota ni qué pasa al excederla. Una cuota sin punto de
aplicación es una columna decorativa: el modelo multi-tenant queda a medias y el cobro futuro sin freno.

## Decisión

- El **webhook siempre guarda** el correo crudo y actualiza `account_usage`, aunque la cuenta esté sobre
  su cuota. Nunca se descarta un dato que ya llegó.
- El **worker no extrae** si la cuenta superó su cuota mensual: la fila queda pendiente, sin consumir
  intentos, marcada con `blocked_by_quota = true`.
- Al cambiar el período se reanuda el procesamiento del backlog automáticamente.
- Aviso al 80 % de la cuota en `account_usage`, antes de que empiece a bloquear.

## Consecuencias

- El correo nunca se pierde por cuota: se posterga. Subir de plan desbloquea todo lo pendiente.
- El costo del LLM queda acotado por cuenta, que es exactamente lo que el presupuesto necesita.
- Agrega un `update` en el webhook (contador) y un chequeo en el worker: baratos y sin dependencias.
- Una cuenta puede acumular backlog grande si queda meses sobre la cuota; la alerta del 80 % y la
  revisión mensual existen para que eso no sorprenda.

## Alternativas rechazadas

- **Rechazar el push en el webhook**: con `2xx` se confirma la recepción; con `4xx` Pub/Sub reintenta
  hasta agotar la retención y después pierde el mensaje. Se pierden datos por diseño.
- **Extraer igual y cobrar el exceso**: contradice la regla de no gastar sin control.
- **Bloquear la cuenta**: pierde correos que el usuario ya recibió.
- **Cuota sin aplicación** (lo que había): la peor opción, porque parece resuelta.
