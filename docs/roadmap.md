# Roadmap por fases

El proyecto crece por **incrementos que funcionan de punta a punta**, no por capas a medio terminar. Cada
fase tiene un objetivo, lo que agrega al modelo, el diseño que hay que escribir **antes** de codear esa
fase, y un **gate de salida**: un número o un hecho verificable. Una fase no se abre sin cerrar el gate de
la anterior.

Regla de diseño por fases: **se diseña la fase que se va a construir, no todas juntas.** Un ADR se
escribe cuando la decisión está cerca de implementarse; adelantarlo convierte el diseño en especulación.

## Resumen

| Fase | Incremento | Fuente | Estado |
|---|---|---|---|
| 1 | Ingesta: webhook idempotente | push de Gmail | **hecha** (9 tests + p95 medido) |
| 2 | Extracción: plantillas, fallback y guard | push de Gmail | a construir |
| 3 | Producto: categorías, alias y resumen por categoría | push de Gmail | a construir |
| 4 | Documentos y boletas con ítems | + boletas online | diseñado (ADR 0016, propuesto) |
| 5 | Histórico y conciliación | + cartolas PDF | diseñado (ADR 0016, propuesto) |
| 6 | Boletas físicas | + fotos | sin diseñar |

---

## Fase 1 — Ingesta (v0)

**Objetivo:** que un correo que llega a Gmail termine guardado, crudo y una sola vez.

**Alcance:** webhook con verificación OIDC, inserción idempotente en `raw_documents` (`kind =
'notification'`), respuesta `200`/`5xx`/`400` según corresponda. Sin extracción, sin LLM, sin UI.

**Hecho:** 9 tests (uno por garantía del contrato: guardado, duplicado por reintento, duplicado por
`historyId`, falta de token, audiencia ajena, token vencido, payload malformado, caída de la base, y el
gate de latencia), Postgres real con Testcontainers y CI en GitHub Actions.

**Diseño requerido antes de codear:** ninguno nuevo — lo cubren los ADR 0003, 0005, 0007 y 0008.

**Gate de salida:**
- El mismo `messageId` enviado dos veces deja **una** fila.
- Un fallo de escritura devuelve `5xx` y Pub/Sub reintenta.
- p95 del webhook medido y publicado en el README.

**Riesgo principal:** creer que el webhook está bien porque responde rápido. El gate es la idempotencia,
no la latencia.

---

## Fase 2 — Extracción (v0)

**Objetivo:** convertir el correo crudo en un gasto con monto confiable.

**Alcance:** `TemplateRegistry` con la primera plantilla real, `LlmFallbackExtractor` activo, `MoneyGuard`,
estado `needs_review`, `origin` y `parser_id`.

**Diseño requerido antes de codear:** ninguno nuevo — ADR 0014 y 0015 ya lo definen. Sí hay que escribir
el **primer fixture** (un correo real anonimizado) antes que la plantilla.

**Gate de salida:**
- Un correo real de cada banco propio resuelto con coincidencia exacta de monto, moneda y fecha.
- Ningún gasto creado con un monto que no esté literalmente en el cuerpo (test de propiedad del guard).
- Evals del fallback corriendo en CI con el gate de `is_expense` al 100 %.

**Riesgo principal:** aflojar el guard "porque el modelo acierta casi siempre". El guard no es un
porcentaje.

---

## Fase 3 — Producto (v0)

**Objetivo:** que el sistema sirva para mirar el gasto del mes por categoría.

**Alcance:** categorías editables con semillas, alias de comercio, `GET /summary`, la home definida en
`docs/ui.md`, y la pantalla de "correos que no se pudieron leer".

**Diseño requerido antes de codear:** ninguno nuevo — ADR 0013, 0015 y `docs/ui.md`.

**Gate de salida:**
- Un mes completo de correos reales cargado y usable.
- Reparto por camino publicado: % plantilla / % fallback / % revisión.
- Cero montos incorrectos detectados al comparar contra la cartola del mes (la comparación es manual, y
  es justamente la que motiva la Fase 5).

**Riesgo principal:** construir la UI antes de tener datos reales; con datos de juguete la pantalla se
diseña para un usuario que no existe.

---

## Fase 4 — Documentos y boletas con ítems (v1)

**Objetivo:** que la compra tenga **detalle**, y que el contenedor deje de guardar solo avisos.

**Alcance:** cargar `kind = 'email'` en el contenedor que ya es `raw_documents`; correos de comercios con
ítems; tabla `expense_items`; `expenses.source`; la primera versión de la conciliación (una boleta y su
notificación bancaria se reconocen y no se duplican).

**Diseño requerido antes de codear:** **ADR 0016 aceptado** (documentos y conciliación) + un ADR de
ítems (`expense_items`: cantidades, precio unitario, descuentos, propina, total que no cuadra con la suma).

**Gate de salida:**
- 20 boletas reales con ítems extraídos, con precisión medida y publicada.
- Cero duplicados cuando llegan la boleta y la notificación del mismo pago.
- El renombre a `raw_documents` con la base migrada y los tests verdes.

**Riesgo principal:** que la conciliación se construya sin datos reales de dos fuentes. Por eso esta fase
llega **después** de tener un mes de push funcionando.

---

## Fase 5 — Histórico con cartolas (v1.5)

**Objetivo:** cargar meses de historia y conciliar todo lo que ya vino por push.

**Alcance:** ingesta de PDF multipágina, extracción de transacciones, conciliación completa con reglas de
precedencia y fusión manual, y reporte de duplicados evitados.

**Diseño requerido antes de codear:** un ADR de lectura de cartolas (formato, paginación, cifras que
aparecen dos veces) y la extensión del 0016 con la precedencia por campo.

**Gate de salida:**
- Tres meses importados.
- **Cero gastos duplicados** verificado por conteo manual contra el total de la cartola.
- El reporte "X transacciones conciliadas, Y nuevas, Z sugeridas" publicado.

**Riesgo principal:** duplicar todo el histórico. Es el modo de falla más caro del proyecto, y el único
que hace que el usuario deje de confiar en el total del mes.

---

## Fase 6 — Boletas físicas (v2)

**Objetivo:** sacar una foto y que el gasto exista.

**Alcance:** modelo de visión, manejo de fotos malas, cola de baja confianza, presupuesto por imagen.

**Diseño requerido antes de codear:** ADR de visión con **costo por imagen** declarado y tope duro; y la
extensión del 0016 para el caso "foto y boleta del mismo comercio".

**Gate de salida:**
- Tasa de acierto medida sobre 30 fotos reales, separando buenas y malas.
- Costo por imagen publicado y dentro del presupuesto.
- Las fotos ilegibles caen a revisión, nunca a un gasto inventado.

**Riesgo principal:** que sea una demo y no una feature. Sin tasa de fallo medida y publicada, es un
truco de feria.

---

## Reglas del roadmap

1. **No se abre una fase sin cerrar el gate de la anterior.** Es la única defensa contra el portfolio a
   medio hacer.
2. **Cada fase termina con números en el README**, no con una lista de features.
3. **El diseño de una fase se escribe antes del código de esa fase**, no antes del de todas.
4. **Una fuente nueva no entra hasta que la anterior funcione de punta a punta**, porque el conciliador
   necesita datos reales para probarse.
5. **Cada fase agrega un ADR como máximo por decisión real.** Si una fase necesita seis ADRs, la fase es
   demasiado grande.
