# 0004 — El LLM vive detrás de `ExpenseExtractor`

**Estado:** aceptado · **Fecha:** 2026-11-23

## Contexto

La extracción es no determinística: el mismo correo puede producir JSON distinto entre corridas,
proveedores y versiones de modelo. Meter el SDK del proveedor dentro de la capa de servicio dejaría
todo el pipeline sin posibilidad de test de regresión y atado a un proveedor.

## Decisión

- Un puerto de dominio, `ExpenseExtractor`, con un solo método:
  `Optional<DraftExpense> extract(RawEmail email)`.
- Las implementaciones viven en `infrastructure`: una sobre **Spring AI 2.1** (por defecto) y otra
  sobre el SDK del proveedor directamente. La configuración elige; los tests usan un fake.
- El resultado se valida contra los tipos de Java (`Expense` y sus invariantes) y se rechaza si no
  parsea. Una extracción rechazada deja el correo pendiente, nunca un gasto a medio escribir.

## Consecuencias

- El pipeline se prueba sin red y sin gastar tokens.
- Cambiar de proveedor es configuración, no refactor.
- Las **evals** pasan a ser la única forma honesta de saber si un cambio de prompt o de modelo mejora:
  mismo dataset, misma métrica, umbral en CI (ver `docs/evals.md`).
- Una capa de indirección extra para un proyecto chico; se justifica porque el LLM es la parte riesgosa.

## Alternativas rechazadas

- **Llamar al SDK desde el servicio**: el camino más corto y la razón por la que la mayoría de los
  repos "con IA" no se pueden testear.
- **Solo Spring AI**: cómodo, pero esconde qué parte es opinión del framework y qué parte es mía. La
  segunda implementación prueba que el límite existe.
