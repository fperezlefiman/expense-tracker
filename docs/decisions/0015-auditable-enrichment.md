# 0015 — Enriquecimiento semántico auditable

**Estado:** aceptado · **Fecha:** 2026-11-23

## Contexto

El descriptor de una compra con tarjeta es basura heredada del procesador: `MERCPAGO*UBER EATS`,
`SUC PROVIDENCIA LIDER`, `PAYU*NETFLIX`. Y la categoría la define el usuario (ADR 0013), en una lista
cerrada y editable.

Hacer esto siempre con el modelo funciona, pero no mejora con el uso, cuesta lo mismo el correo mil que
el primero, y no permite responder la pregunta que importa: **¿qué decidió el modelo y qué decidió una
regla?**

## Decisión

1. **Alias primero.** Tabla `merchant_aliases` por cuenta: patrón del descriptor → nombre limpio y
   categoría por defecto. Coincidencia determinística, cero costo.
2. **El modelo solo cuando el comercio es nuevo.** Propone nombre limpio y categoría de la lista del
   usuario. Al confirmarlo, el usuario genera un alias: el sistema **aprende sin fine-tuning**.
3. **`expenses.origin`**: `template` · `alias` · `llm` · `user`, más `confidence`. Cada gasto dice de
   dónde salió su clasificación.
4. **Precisión medida por origen.** La corrección del usuario se registra como un evento, no como una
   edición silenciosa.

## Consecuencias

- El costo del enriquecimiento **decrece con el uso**: cada comercio nuevo pasa a alias y deja de llamar
  al modelo.
- Se puede afirmar con números: categoría correcta por origen, cuántas correcciones hubo por camino, y
  cuánto bajó la tasa de llamadas al modelo en el mes.
- Renombrar un alias corrige el futuro, no reescribe el histórico: los gastos ya guardados conservan su
  categoría hasta que el usuario los cambie.
- Agrega una tabla chica y dos columnas; a cambio, la clasificación deja de ser una caja negra.

## Alternativas rechazadas

- **Solo LLM**: costo constante, sin aprendizaje y sin forma de auditar.
- **Solo alias**: arranca sin datos y no resuelve el caso desconocido, que es el que molesta.
- **Fine-tuning**: con ocho categorías y pocos comercios no hay volumen que lo justifique.
- **Preguntar la categoría siempre**: cada gasto convertido en una tarea; el usuario abandona.
