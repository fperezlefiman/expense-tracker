# Datos personales: qué se guarda, para qué y hasta cuándo

Documento operativo, no legal: describe lo que el sistema hace con los datos y las decisiones que lo
sostienen (`docs/decisions/0017-data-minimization.md`).

## Quién y en qué marco

- **Responsable**: Francisco Pérez Lefiman, uso **personal** de la aplicación.
- **Marco legal**: Chile, Ley 21.719 (nueva Ley de Protección de Datos Personales), que rige desde el
  **15 de octubre de 2027** por la postergación de la **Ley 21.855** (publicada el 23 de noviembre de
  2026). Hasta esa fecha aplica la Ley 19.628.
- **Hoy este sistema está fuera del ámbito de la ley**: la 21.719 excluye el tratamiento que una persona
  natural hace de sus propias actividades personales.
- **Disparador**: el primer usuario que **no** sea el responsable. Ahí se activan las obligaciones de
  responsable de datos: base de licitud, información al titular, contratos de encargado, notificación de
  brechas y registro de actividades.

## Qué se guarda

| Dato | Contenido | Retención |
|---|---|---|
| Correo candidato | texto **enmascarado** (sin RUT, tarjeta, emails, teléfonos, titular) | 90 días |
| Adjunto permitido | PDF o imagen de cartola/boleta, en bucket cifrado | 90 días |
| Gasto | monto, moneda, fecha, comercio, categoría, `origin` | hasta borrado por el usuario |
| Ítems | producto, cantidad, precio, descuento | hasta borrado por el usuario |
| Categorías y alias | datos que el usuario crea | hasta borrado por el usuario |
| Token OAuth de Gmail | cifrado por columna | mientras la cuenta esté conectada |
| API key | **hash**, nunca la clave | hasta revocarla |
| Métricas | agregadas y seudonimizadas (cuenta por id) | 90 días |

## Qué NO se guarda

- Correos que no pasaron el prefiltro: **ni cuerpo, ni asunto, ni remitente**. Solo el conteo.
- Historial del buzón anterior a la conexión (salvo cartolas que el usuario suba a propósito).
- Números de tarjeta, RUT, direcciones, teléfonos ni nombre del titular: se enmascaran antes de guardar.
- Imágenes de firma, adjuntos embebidos en HTML, `.zip`, `.ics` ni ningún MIME fuera de la allowlist.
- El PDF original cuando el texto alcanza para extraer.

## Para qué (finalidad)

1. Extraer el gasto de un correo o documento.
2. Mostrar el resumen y el detalle al usuario.
3. Medir precisión (evals y métricas de camino) para mejorar plantillas y prompts.

No se usa para otra cosa: no hay perfilado publicitario, no hay venta de datos, no hay entrenamiento de
modelos con los datos del usuario.

## Dónde vive y quién lo trata (encargados)

| Proveedor | Qué ve | Nota |
|---|---|---|
| Google (Gmail API, Pub/Sub) | la cuenta y los avisos de correo | origen de los datos |
| Neon (PostgreSQL) | gastos, ítems, categorías, tokens cifrados | base de datos |
| Bucket cifrado | PDFs e imágenes | retención 90 días |
| Proveedor de LLM | **solo texto enmascarado y mínimo** | exige retención cero y sin entrenamiento |
| Cloud Run / Logging | logs sin contenido | 30 días |

**Transferencia internacional**: los tres primeros y el proveedor del LLM operan fuera de Chile. Se
documenta como tal y se minimiza lo que se envía: el cuerpo enmascarado, nunca la imagen si el texto
alcanza.

## Borrado

- **Cuenta**: borra gastos, ítems, categorías, alias, documentos y token OAuth. Los adjuntos se eliminan
  del bucket en el mismo proceso.
- **Documento vencido**: purga por lotes a los 90 días, verificada con un conteo (`docs/operations.md`).
- **Token revocado**: la cuenta pasa a `needs_reauth`; el token se borra al reconectar o al desconectar.
- Plazo objetivo: el borrado de una cuenta se completa en menos de 24 horas y se registra en el log.

## Si hay una brecha

1. Cortar el acceso comprometido (rotar claves, revocar tokens, rotar la API key).
2. Evaluar el alcance: qué documentos y qué gastos estuvieron expuestos.
3. Si el sistema ya procesa datos de terceros, notificar a la Agencia y a los titulares en los plazos
   legales.
4. Registrar el incidente con fecha, causa y medidas, aunque no haya obligación de notificar.

## Revisión

Este documento se revisa cuando cambie cualquiera de estas cosas: se agrega una fuente de datos, se
suma un proveedor que trate datos, entra el primer usuario que no sea el responsable, o cambia la ley.
