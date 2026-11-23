# 0003 — Correo crudo antes de extraer; webhooks idempotentes

**Estado:** aceptado · **Fecha:** 2026-11-23

## Contexto

Las notificaciones push de Gmail llegan vía Pub/Sub, que entrega **al menos una vez**: el mismo
mensaje puede llegar repetido y el endpoint puede recibir un push de un correo ya procesado. Por
separado, la extracción con LLM puede fallar, devolver basura o cambiar cuando cambia el prompt o el
modelo.

## Decisión

1. Guardar cada push en `raw_emails`, con **clave única `(account_id, gmail_message)`**, antes de
   cualquier procesamiento.
2. La extracción lee de `raw_emails`, no del payload del webhook. Reprocesar es una consulta, no un
   viaje a Google.
3. El endpoint responde `200` apenas guarda el correo crudo; `5xx` si no pudo guardarlo. La extracción
   ocurre fuera de banda: Pub/Sub reintenta todo lo que no sea `2xx`, así que un LLM dentro del request
   es una garantía de timeouts y trabajo duplicado.

## Consecuencias

- Los duplicados los absorbe la clave única: el insert se vuelve un no-op (`on conflict do nothing`).
- Un prompt nuevo se puede reprocesar contra los correos guardados para medir la mejora.
- El almacenamiento crece con cada correo: hace falta una purga por retención (ADR 0007).
- Un fallo de extracción nunca pierde datos: la fila cruda queda con `processed_at = null`.

## Alternativas rechazadas

- **Procesar dentro del webhook**: lo más simple de escribir y lo más seguro de romper ante el primer
  reintento.
- **Deduplicar en memoria o en caché**: pierde la garantía al reiniciar, que es justo cuando llegan los
  duplicados.
