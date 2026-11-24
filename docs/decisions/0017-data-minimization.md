# 0017 — Minimización, enmascarado y retención de documentos

**Estado:** aceptado · **Fecha:** 2026-11-23

## Contexto

El push de Gmail avisa de **cada** correo nuevo del buzón. Con el diseño anterior —"el webhook guarda el
crudo"— la base terminaba almacenando **toda la correspondencia personal**, incluida la que no tiene nada
que ver con gastos. Eso no es un tracker de gastos: es un archivo de vida privada con una tabla de
gastos al lado.

En Chile, la Ley 21.719 (que rige desde el **15-oct-2027** por la Ley 21.855, publicada el 23-nov-2026)
exige finalidad determinada, proporcionalidad, minimización, conservación limitada y seguridad. Su ámbito
**excluye** el tratamiento que una persona natural hace de sus propias actividades personales, así que un
uso exclusivamente propio queda fuera. El disparador que lo cambia todo es el primer **tercer** usuario,
no el cobro.

## Decisión

1. **Prefiltro con metadatos, sin cuerpo.** De cada aviso se pide solo cabeceras (`format=metadata`:
   remitente y asunto). Deciden reglas: dominios de bancos, tarjetas y comercios conocidos, más palabras
   de transacción en el asunto ("compra", "cargo", "boleta", "factura", "pago"). **El cuerpo no se
   descarga** si no pasa.
2. **Lo descartado no se guarda**: ni cuerpo, ni asunto, ni remitente. Solo un contador agregado
   ("14 correos ignorados hoy").
3. **Sin backfill del buzón.** Se procesa desde la fecha de conexión. El histórico entra únicamente si el
   usuario sube una cartola, que es un acto explícito.
4. **Enmascarado antes de persistir.** RUT, números de tarjeta, correos, teléfonos y nombre del titular
   se reemplazan por marcadores antes de guardar el crudo. El texto enmascarado alcanza para reprocesar
   (monto, comercio, fecha) y no contiene identificadores.
5. **Adjuntos con allowlist**: solo `application/pdf`, `image/jpeg`, `image/png`, bajo un tope de tamaño,
   **solo si el extractor los necesita** y solo de correos que pasaron el filtro. Se guardan en un bucket
   privado cifrado; la base guarda la referencia, nunca una URL pública. Acceso por URL firmada de
   minutos. El parseo corre en el worker con límites de tamaño y tiempo: un PDF es entrada no confiable.
6. **Retención de 90 días para el documento** (crudo y adjunto). El **hecho derivado** —gasto, ítems,
   categoría, origen— persiste hasta que el usuario lo borre. La purga es un `delete` por lotes y se
   verifica con un conteo, no con la confianza.
7. **Cifrado**: tokens OAuth cifrados por columna, bucket cifrado, API keys hasheadas. Los gastos de
   categorías que insinúan salud, religión u opinión no aparecen en logs ni en métricas.
8. **El proveedor del LLM recibe el mínimo, enmascarado**, y solo el texto necesario; nunca el PDF
   original si el texto alcanza. Proveedor con retención cero y sin entrenamiento con los datos.
9. **Métrica de control**: ratio guardado/recibido. Si cae a cero, el prefiltro se rompió y hay alerta —
   el mismo razonamiento que el "silencio de ingesta".

## Consecuencias

- La base deja de ser un archivo de correspondencia: contiene lo que pasó el filtro, sin identificadores.
- Se puede reprocesar durante 90 días (cambiar una plantilla y volver a correr sobre el crudo); después
  quedan solo los hechos, que es lo que el producto necesita.
- El costo de almacenamiento se mantiene bajo y la superficie de una brecha se reduce a lo estrictamente
  necesario.
- Un prefiltro demasiado angosto pierde gastos en silencio: se mitiga con lista generosa, ratio medido y
  alerta. Si un correo entra y no se entiende, cae a `needs_review`; si nunca entra, se ve en la ratio.
- Con esto, abrir la app a terceros no obliga a rehacer el diseño de datos: obliga a escribir la política
  de privacidad y los contratos de encargado (ver `docs/privacy.md`).

## Alternativas rechazadas

- **Guardar todo para reprocesar cómodo**: convierte el tracker en un archivo de la vida privada; el
  costo de almacenamiento es lo de menos.
- **Guardar sin enmascarar**: 90 días de RUT y tarjetas en una base administrada por una sola persona sin
  controles de acceso serios.
- **Bajar todos los adjuntos "por si acaso"**: superficie de ataque y costo sin uso.
- **Retención indefinida**: contradice la conservación limitada y multiplica el impacto de una brecha.
- **No guardar nada**: impide reprocesar cuando un banco cambia la plantilla y borra la evidencia de por
  qué el sistema dijo lo que dijo.
