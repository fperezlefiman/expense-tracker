# 0016 — Documentos genéricos y conciliación entre fuentes

**Estado:** propuesto · **Fecha:** 2026-11-23 · **Se acepta al implementar la Fase 4**

## Contexto

La misma compra puede llegar por cuatro caminos: la notificación del banco, la cartola del mes, la boleta
del comercio y la foto de una boleta física. Sin un mecanismo de identidad, **cada compra aparece hasta
cuatro veces** y el resumen del mes miente.

Un `unique (account_id, gmail_message)` no lo resuelve: no son el mismo mensaje, son **documentos
distintos del mismo hecho**. Tampoco sirve un `unique` sobre monto y fecha: dos cafés del mismo día por
el mismo monto son dos gastos legítimos.

## Decisión

1. **La unidad de ingesta es el documento.** La tabla se llama **`raw_documents` desde la V1**, con
   `kind` (`notification`, `email`, `statement`, `receipt`, `image`) y `source_ref` (id del mensaje de
   Pub/Sub, id de Gmail, hash del PDF o de la imagen). La idempotencia es
   `(account_id, kind, source_ref)`.
2. **`expenses` es la entidad canónica del hecho**, no del documento. El vínculo va en
   **`expense_documents`** (`expense_id`, `document_id`, `role`: `notification`, `statement`, `receipt`,
   `photo`).
3. **Reglas de emparejamiento, determinísticas y en este orden:**
   - mismo `account_id`;
   - **monto igual** (tolerancia solo si la boleta declara propina aparte);
   - **fecha dentro de ±2 días** (la notificación puede llegar el día anterior o siguiente);
   - comercio coincidente por alias, o similitud de texto por encima del umbral.
4. **Tres resultados posibles:** `auto` (monto igual + fecha ±1 día + comercio coincide) → se adjunta el
   documento y **no se crea un gasto nuevo**; `suggested` (coincide parcialmente o hay más de un
   candidato) → el usuario decide; `none` → gasto nuevo.
5. **Nunca automático cuando hay ambigüedad.** Dos candidatos abiertos es siempre `suggested`: unir dos
   compras distintas es peor que pedir un toque.
6. **Precedencia por campo**, porque ninguna fuente es autoritativa para todo:

   | Campo | Fuente que manda |
   |---|---|
   | monto, moneda, fecha | cartola > notificación |
   | nombre limpio del comercio | boleta > alias > modelo |
   | ítems y detalle | boleta (únicamente) |
   | categoría | usuario > alias > modelo |

7. **El matching no usa el modelo.** Es una consulta con índices, no un juicio: un LLM acá sería más
   caro, más lento y no auditable.
8. **Nada se borra al deshacer:** si un vínculo resulta falso, se separa el documento y el gasto vuelve
   a su estado anterior.

## Consecuencias

- Importar una cartola deja de ser peligroso: solo adjunta documentos a gastos existentes y crea los que
  faltan, con el reparto publicado (*"142 conciliadas, 8 nuevas, 3 sugeridas"*).
- El histórico de meses anteriores entra sin multiplicar el total.
- El matching es barato y testeable: un test por caso (auto, sugerido, ninguno, ambiguo).
- Agrega dos tablas y un renombre; a cambio, el número que el usuario mira —el total del mes— deja de
  ser una suma de duplicados.
- El renombre **se adelantó a la V1**: la tabla nació como `raw_documents` porque el costo era cero antes
  de escribir código y una migración con datos después. El 0017 se apoya en el mismo contenedor.

## Alternativas rechazadas

- **`unique` sobre monto + fecha**: dos compras legítimas del mismo día por el mismo monto se colapsan.
- **No conciliar y que el usuario borre duplicados**: fricción diaria y una fuente de error humana.
- **Conciliar con el modelo por similitud**: caro, no reproducible y sin forma de auditar un falso
  positivo.
- **Un gasto por documento**: es lo que hay hoy; duplica el total en cuanto entre la segunda fuente.
- **Esperar a tener todas las fuentes para conciliar**: el conciliador no se puede probar sin datos
  reales de al menos dos fuentes.
