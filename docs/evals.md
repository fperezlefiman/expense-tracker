# Evals de la extracción

Un pipeline con LLM sin evals no está en producción: está en demo. Pero **no todo se mide con evals**:
lo determinístico se testea, y solo lo que decide el modelo se evalúa. Mezclarlos encarece la medición y
esconde los fallos detrás de un promedio.

## Qué se mide con qué

- **Monto, moneda y fecha por plantilla**: test unitario por emisor, coincidencia exacta. Es
  determinístico: un test lo cubre al 100 %.
- **Monto, moneda y fecha por fallback**: **invariante del `MoneyGuard`** — el número tiene que existir
  literalmente en el cuerpo del correo (ADR 0014). Es una regla, no una probabilidad.
- **`is_expense`**: eval, exactitud obligatoria.
- **`merchant_name`**: eval, coincidencia normalizada.
- **`category_id`**: eval, exacto contra la lista del usuario.

## Dataset

- **Origen**: correos reales de banco, tarjeta y tienda, **anonimizados** (sin remitente, sin nombre del
  titular, sin últimos dígitos de tarjeta, sin dirección, sin RUT). Los montos y las fechas se mantienen:
  son el objeto de la medición.
- **Ubicación**: `evals/dataset/NNNN.json`, **commiteado en el repo**. Es la única forma de que el
  umbral sea reproducible y de que un revisor vea contra qué se mide.
- **Tamaño**: 40 casos como mínimo — al menos uno por emisor conocido y **ocho casos que NO son gastos**
  (promoción, aviso de saldo, compra rechazada, transferencia recibida, recordatorio de cuota). El falso
  positivo es el error más caro, así que se mide explícitamente.

```json
{
  "id": "0007",
  "email": { "from_domain": "bancoejemplo.cl", "subject": "...", "body": "..." },
  "has_template": true,
  "expected": { "is_expense": true, "merchant_name": "Uber Eats", "category": "Restaurantes" }
}
```

## Métrica

- `is_expense`: exacto. **Un solo falso positivo invalida la corrida**; no se promedia con el resto.
- `merchant_name`: igualdad tras normalizar (minúsculas, sin acentos, sin sufijos como `SPA` o `LTDA`).
- `category_id`: exacto contra la lista del usuario del fixture.

**Score global** = promedio simple sobre los casos con `is_expense` correcto. Un caso mal clasificado en
`is_expense` cuenta 0 y además marca la corrida como fallida.

## Umbral

- **`is_expense` 100 %**: no es negociable, es corrupción de datos.
- **Score ≥ 0.85** en `merchant_name` + `category` para mergear.
- Los casos con score < 1.0 se listan en el reporte: se revisan los fallos, no el promedio.

## Cómo se ejecuta

- Script de evals contra el dataset, con el extractor real y el proveedor configurado.
- **Gate en el PR con path filter**: corre cuando cambian las plantillas, los prompts, el modelo
  configurado o el dataset. Ahí bloquea el merge; en un push que no toca nada de eso, no gasta tokens.
- **Costo del gate**: ~40 casos y el modelo solo corre en los que no tienen plantilla — unos pocos miles
  de tokens. Si algún día no entra en el presupuesto, se reduce a 12 casos de regresión; el gate no se
  apaga.
- El resultado se guarda en `evals/results/YYYY-MM-DD-<commit>.json` y se resume abajo.

## Métricas de producción (no bloquean un merge)

- **Reparto por camino**: % resuelto por plantilla, por fallback y por alias.
- **Tasa de `needs_review`**: si sube, un emisor cambió su plantilla.
- **Rechazos del `MoneyGuard`**: cuántas veces el modelo propuso un monto que no estaba en el texto. Es
  la métrica de alucinaciones atajadas, y más honesta que cualquier score de confianza.
- **Precisión de categoría por `origin`**: cuánto acierta el modelo y cuánto el alias (ADR 0015).

## Resultados

| Fecha | Commit | Modelo | Score semántico | `is_expense` | Costo | Notas |
|---|---|---|---|---|---|---|
| — | — | — | — | — | — | pendiente: implementación del extractor |

> Regla: un número sin fecha y sin commit no es un resultado, es una anécdota.
