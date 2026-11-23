# 0014 — Plantillas primero, LLM como fallback, y el monto validado contra el texto

**Estado:** aceptado · **Fecha:** 2026-11-23

## Contexto

Los correos de banco y tarjeta traen **monto, comercio y fecha**, no detalle de compra. Son pocos
emisores (cinco o seis) y cada uno tiene una plantilla estable que cambia de vez en cuando.

El ADR 0004 dejó la extracción detrás de una interfaz, pero el diseño asumía que el LLM extraía todo.
Eso pone al modelo en el único campo donde un error es caro e invisible: un monto inventado es
indistinguible de uno real hasta que el total del mes no cuadra.

## Decisión

1. **Camino principal determinístico.** Un `TemplateRegistry` identifica el emisor por dominio del
   remitente y firma del cuerpo. Si hay plantilla, monto, moneda y fecha salen de un parser
   determinístico y se guarda `parser_id` en el gasto.
2. **El LLM es el fallback**, no el camino principal: se invoca cuando **ninguna plantilla matchea**
   (banco nuevo, plantilla cambiada, correo de una tienda). Responde con salida estructurada
   (JSON Schema) y **está activo desde el v0**, sin flag: sin tráfico real no hay números que defender.
3. **`MoneyGuard`, una regla y no un prompt.** El monto que propone el modelo tiene que aparecer
   **literalmente en el cuerpo del correo**, comparando solo dígitos y normalizando separadores de miles
   y decimales. La fecha tiene que corresponder a una fecha presente en el texto. Si no coincide:
4. **No se crea el gasto.** El crudo queda en `needs_review` con el motivo, y el usuario lo ve en una
   pantalla concreta. Es preferible un gasto que falta —visible— a un gasto con monto falso —invisible—.
5. La confianza alta del camino plantilla y la del fallback se registran distinto: `origin`
   `template` o `llm` (ADR 0015).

## Consecuencias

- Alrededor del 90 % del volumen no toca el modelo: costo por correo de centavos y latencia de
  milisegundos en el camino común.
- Si un banco cambia la plantilla, el correo cae al fallback, que probablemente lo resuelva; si no,
  queda en revisión. **Nunca se pierde en silencio**, que es el modo de falla que el ADR 0007 quiere
  evitar.
- Los rechazos del `MoneyGuard` se cuentan y se exponen: es la métrica de alucinaciones atajadas, y es
  más honesta que cualquier porcentaje de confianza.
- Tests determinísticos por plantilla (fixture por emisor, coincidencia exacta de monto, moneda y
  fecha) cubren el camino principal. Las evals cubren solo lo que el modelo decide.
- El modelo puede usar una ventana de contexto chica: un correo, una lista de categorías y un schema.

## Alternativas rechazadas

- **Solo LLM para todo**: pone el dinero en manos de una probabilidad; el error es plausible y silencioso.
- **Solo reglas**: pierde el formato nuevo y la normalización del descriptor de comercio, y la cola de
  revisión se llenaría de casos que el modelo resolvería bien.
- **Confianza del modelo como garantía** ("si dice 0.95, lo acepto"): una probabilidad no es una
  validación. La garantía tiene que ser una regla verificable contra el texto.
- **Regex global sin plantilla por emisor**: un patrón único para bancos distintos produce falsos
  positivos (saldo, límite disponible, comisión) que pasan como gasto.
