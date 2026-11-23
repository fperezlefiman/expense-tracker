# 0013 — Categorías como dato del usuario, moneda e idioma por cuenta

**Estado:** aceptado · **Fecha:** 2026-11-23

## Contexto

Hasta ahora `expenses.category` era un `text` libre que el LLM rellenaba. Eso alcanza para una lista,
pero la pantalla principal del producto es **el gasto del mes por categoría**: la categoría no es un
adorno, es el eje de la home. Con texto libre, "supermercado", "Supermercado" y "súper" son tres
categorías distintas, la agregación miente y el usuario no puede corregir nada sin editar gasto por
gasto.

Además, el producto se usa en un solo idioma y una sola moneda si nadie lo configura. Con un usuario en
Chile y la posibilidad de recibir correos en dólares, el formato no puede estar cableado.

## Decisión

1. **Tabla `categories` por cuenta**: `id`, `account_id`, `name`, `color`, `archived_at`,
   `created_at`. Único `(account_id, lower(name))`, para que no haya duplicados por mayúsculas.
2. **`expenses.category_id` como FK nullable.** `null` significa **"sin categoría"** y es un estado
   válido, no un error: el gasto existe y se puede clasificar después.
3. **Semillas al crear la cuenta** (en el idioma de la cuenta): Supermercado, Restaurantes, Transporte,
   Hogar, Salud, Ocio, Otros. Son un punto de partida editable, no un catálogo cerrado.
4. **El extractor elige de una lista cerrada.** El prompt recibe los nombres de las categorías del
   usuario y el modelo responde con un valor de ese conjunto o `sin_categoria`. **Nunca crea una
   categoría nueva**; si propone una que no existe, se descarta y queda `sin_categoria`.
5. **Se archiva, no se borra.** `archived_at` oculta la categoría de los selectores; los gastos que la
   usan la conservan y la interfaz la muestra como archivada. Borrar una categoría con gastos no existe
   como operación.
6. **`accounts.currency` (`char(3)`) y `accounts.locale`** (ej. `es-CL`). Los montos se guardan siempre
   con su moneda; los totales se agrupan **por moneda**, sin conversión.

## Consecuencias

- La agregación por categoría es exacta y la home puede confiar en ella.
- El prompt baja de costo y sube de precisión: clasificar entre 8 opciones es mucho más barato y estable
  que generar texto libre.
- Renombrar una categoría no toca ningún gasto: los gastos apuntan al `id`, no al nombre.
- `null` en `category_id` es el estado que el usuario ve como **"3 sin categoría"**, con la acción de
  asignar en un gesto.
- Sin conversión de moneda en v0: un total sumado en dos monedas sería un número inventado. Convertir
  exige tipo de cambio con fuente y fecha, y eso merece su propio ADR el día que se necesite.
- El índice `(account_id, category_id, occurred_on desc)` sostiene la consulta de la home.

## Alternativas rechazadas

- **Texto libre (lo actual)**: rompe la agregación y no permite corregir en bloque.
- **Catálogo global fijo**: no se adapta a la vida de cada persona; obliga a "Otros" para todo.
- **Dejar que el LLM proponga categorías nuevas**: resultados impredecibles, costosos y sin forma de
  deduplicar lo que inventa.
- **Borrado en cascada**: perdería el histórico del gasto por limpiar una etiqueta.
- **Conversión de moneda automática**: número bonito y falso mientras no haya tasa con fuente y fecha.
