# Evals de extracción

Un sistema con LLM sin evals no está en producción: está en demo. Este documento define el dataset,
la métrica y cómo se publica el resultado.

## Dataset

- **Origen**: correos reales de confirmación de compra, **anonimizados** (sin remitente, sin números de
  tarjeta, sin direcciones completas).
- **Formato**: `evals/dataset/NNNN.json` con el correo de entrada y el gasto esperado:

  ```json
  {
    "id": "0001",
    "email": { "subject": "...", "body": "..." },
    "expected": { "amount": "15990", "currency": "CLP", "occurred_on": "2026-11-20", "category": "supermercado" }
  }
  ```

- **Tamaño objetivo**: 40 casos como mínimo para el primer umbral; cobertura de al menos cuatro
  formatos de correo distintos y dos casos ambiguos a propósito (monto sin moneda, dos montos en el
  mismo correo).

## Métrica

Por caso, se evalúan tres campos duros:

| Campo | Criterio | Peso |
|---|---|---|
| `amount` | igualdad exacta (normalizada a 2 decimales) | 0.5 |
| `currency` | igualdad exacta (ISO-4217) | 0.2 |
| `occurred_on` | igualdad exacta, tolerancia 0 días | 0.3 |

**Score del caso** = suma de pesos de los campos correctos. **Score global** = promedio simple.

`category` se reporta pero **no puntúa**: es clasificación abierta y castigarla escondería errores que
sí importan.

## Umbral

- Línea base publicada: **score ≥ 0.80** y el workflow falla por debajo (gate en el PR, ver abajo).
- Un cambio de prompt o de modelo que baje el score no entra, aunque "se vea mejor en un caso".
- Los casos con score < 1.0 se listan en el reporte: la revisión es de los fallos, no del promedio.

## Cómo se ejecuta

- Script de evals contra el dataset, usando el extractor real con el proveedor configurado.
- **Gate en el PR con path filter**: el workflow corre solo cuando cambian los archivos que pueden
  mover el score —`extractor/`, `prompts/`, el modelo configurado o el propio dataset—. Ahí sí bloquea
  el merge. En un push que no toca nada de eso, no gasta tokens.
- **Costo del gate**: ~40 casos por corrida, unos pocos miles de tokens; entra en el presupuesto.
  Si algún día no entra, se reduce a un subconjunto de 12 casos de regresión, no se apaga el gate.
- El resultado se guarda en `evals/results/YYYY-MM-DD-<commit>.json` y se resume acá abajo.

## Dónde vive el dataset

- **Commiteado en el repo**, en `evals/dataset/`: es la única forma de que el umbral sea reproducible y
  de que un revisor vea contra qué se mide.
- **Sin PII, y verificado antes de cada commit**: sin remitente, sin nombre del titular, sin últimos
  dígitos de tarjeta, sin dirección completa, sin RUT. Los montos y las fechas se mantienen: son el
  objeto de la medición.
- Un caso nuevo entra por PR con la misma revisión que el código. Si un caso parece real, se anonimiza
  hasta que no lo sea.

## Resultados

| Fecha | Commit | Modelo | Score | Costo | Notas |
|---|---|---|---|---|---|
| — | — | — | — | — | pendiente: implementación del extractor |

> Regla: un número sin fecha y sin commit no es un resultado, es una anécdota.
