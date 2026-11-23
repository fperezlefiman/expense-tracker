# Interfaz: presentación de los gastos

Contrato de la capa de presentación. El front vive en otro repo (React + TypeScript + Vite); acá se
define qué muestra, con qué jerarquía y qué necesita del backend.

## El trabajo de la pantalla

**Quien entra quiere saber cuánto gastó este mes y en qué.** No viene a administrar una base de datos ni
a mirar gráficos: viene a leer un número y entender su composición en menos de veinte segundos, casi
siempre desde el teléfono.

Consecuencia de diseño: **la home es el resumen del mes por categoría**, no la bandeja de revisión.
Revisar es una tarea, y las tareas van al final de la pantalla, no en el centro.

## Superficies

### 1. Home — gasto del mes por categoría

Jerarquía estricta, de arriba hacia abajo:

1. **Mes navegable** (`← noviembre 2026 →`). El mes es contexto, no protagonista.
2. **Total del mes**, el único elemento grande de la pantalla, con la moneda explícita.
3. **Variación contra el mes anterior** al lado del total, en texto secundario: `+12 % vs octubre`.
   Si no hay mes anterior, no se muestra nada en vez de un `0 %`.
4. **Ranking de categorías**, ordenado por monto descendente. Cada fila: punto de color, nombre,
   monto, porcentaje del total y una barra proporcional fina. La suma de porcentajes se redondea a 100.
5. **Pendiente de revisar**, discreto y solo si existe: `3 sin revisar ›`.

La fila de categoría es un enlace: abre la lista filtrada de esa categoría en ese mes.

### 2. Lista filtrada

Gastos del mes o de la categoría elegida, agrupados por día. Cada ítem: monto y moneda a la derecha,
comercio o descripción a la izquierda, fecha secundaria. Tocar abre el detalle.

### 3. Detalle del gasto

Lo que da confianza: monto, moneda, fecha, descripción, categoría editable, y **la procedencia** — qué
correo lo originó y cuándo llegó. Si el crudo ya se purgó por retención, se dice: `correo original
eliminado por retención`, en vez de un enlace roto.

Acciones: cambiar categoría, corregir monto o descripción, descartar.

## Reglas

- **El dinero manda.** Cifras con números tabulares, alineadas a la derecha en listas, moneda siempre
  visible. Nunca dos monedas sumadas en un mismo total: los totales se agrupan por moneda.
- **Sin gráfico de torta como protagonista.** El ranking con barras comunica lo mismo y se lee antes.
- **Cero gráficos vacíos.** Sin datos, la pantalla es una acción: `Conectá Gmail para empezar`.
- **Categorías vacías no se muestran.** El ranking lista solo categorías con gasto en el mes.
- **"Sin categoría" es una fila más**, siempre al final y con acción para asignar.
- **Tocar algo tiene que hacer algo.** Nada de filas con hover y sin destino.

## Estados

| Estado | Qué se muestra |
|---|---|
| Primera vez, sin Gmail conectado | Una sola acción: conectar Gmail. Sin totales en cero |
| Mes sin gastos | `Sin gastos en noviembre` + navegación a otro mes. Sin ranking vacío |
| Cargando | Esqueleto con la forma del ranking, no un spinner centrado |
| Error de red | Mensaje claro y reintento; los datos previos siguen en pantalla |
| Gasto sin categoría | Fila `Sin categoría` con acción de asignar |
| Categoría archivada | Se muestra en gris con la etiqueta `archivada`; acumula igual |
| Correo purgado | `correo original eliminado por retención` en el detalle |

## Del backend

- `GET /summary?period=YYYY-MM` → total por moneda, variación, y por categoría: `id`, `name`,
  `color`, `amount`, `count`, `archived`.
- `GET /expenses?period|category_id|from&to` → paginado, con `category` embebida.
- `GET /expenses/{id}` → el gasto con su procedencia.
- `PATCH /expenses/{id}` → monto, descripción, `category_id`.
- `GET /categories` · `POST /categories` · `PATCH /categories/{id}` · `POST /categories/{id}/archive`.

Todo con `account_id` derivado de la credencial, nunca del cliente (ADR 0010).

## Restricciones

- **Móvil primero**, 360 px como ancho de referencia; el escritorio es la misma jerarquía con más aire.
- Números y fechas por `Intl` según `account.locale`; montos según la moneda del gasto.
- Textos de interfaz traducibles desde el día uno (`es-CL` primero): la categoría es dato, el resto es
  interfaz.
- Contraste mínimo AA en texto y en las barras; el color de categoría nunca es el único portador de
  información — siempre va con el nombre.
