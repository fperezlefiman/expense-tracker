# Operación

Deploy, alertas y costos. Los detalles de arquitectura están en [`architecture.md`](architecture.md);
las decisiones, en [`decisions/`](decisions/).

## Entornos

| Entorno | Base de datos | Uso |
|---|---|---|
| local | PostgreSQL en `docker compose` (puerto 5433) | desarrollo y tests |
| staging | Neon, rama de base | verificación antes de producción |
| prod | Neon (v0) → Cloud SQL | cuentas reales |

## Pipeline

1. Push a `main` → GitHub Actions: `mvn verify` (unitarios + Testcontainers).
2. Imagen publicada en Artifact Registry, tageada con el SHA del commit.
3. **Migraciones en un Cloud Run Job**, no al arrancar: se ejecuta una sola vez antes de desplegar y
   aplica Flyway. Con tres servicios arrancando en paralelo, migrar en el arranque significa tres
   intentos concurrentes y un rollback de imagen que no revierte el esquema.
4. Deploy de los tres servicios (webhook, worker, API) y del job de migración, autenticado por
   **Workload Identity Federation** (sin llaves JSON).
5. Los servicios arrancan con `ddl-auto: validate`: si el esquema no coincide, el deploy falla.
6. Cloud Scheduler se actualiza apuntando al worker nuevo (OIDC, un minuto).

Rollback: se vuelve a la imagen anterior de los servicios. Las migraciones ya aplicadas no se revierten
(ADR 0002): si un cambio de esquema es incompatible, se despliega en dos pasos.

## Secretos

- Credenciales de Gmail (refresh token) por cuenta, cifradas en base.
- Credenciales de Cloud Run vía service account; nada de llaves estáticas en el repositorio.
- Llave del proveedor de LLM en Secret Manager, inyectada en runtime.

## OAuth con Google y tokens

- **La app va en estado Producción, sin verificar.** No es un atajo sucio: es la única forma de tener
  `gmail.readonly` sin evaluación CASA. Queda limitada a 100 usuarios y Google muestra una pantalla de
  "app no verificada" que el usuario acepta una vez.
- **No se usa el estado Testing.** En Testing los refresh tokens **caducan a los 7 días**: la ingesta se
  detendría sola cada semana, en silencio. Es exactamente el modo de falla que el ADR 0007 quiere
  evitar, y convertirlo en el estado normal sería un error de operación.
- El refresh token se guarda **cifrado** por cuenta y se usa solo en el proceso de ingesta.
- **Revocación**: si el usuario revoca el acceso desde su cuenta de Google, el primer refresh falla con
  `invalid_grant`. El sistema debe marcar la cuenta como `needs_reauth`, dejar de reintentar en bucle y
  avisar en la interfaz con una acción concreta: reconectar.
- Reautorizar es idempotente: el flujo reemplaza el token de la cuenta existente, no crea otra.

## Alertas

Todas sobre **síntoma, no sobre causa**, y todas en un solo lugar: una alerta que vive en tres
secciones del documento es una alerta que se duplica en la consola.

| # | Alerta | Umbral | Fuente |
|---|---|---|---|
| 1 | Silencio de ingesta | 0 eventos en 12 h | métrica propia del webhook |
| 2 | Pendiente viejo | fila más antigua `processed_at is null` > 15 min | consulta sobre `raw_emails` |
| 3 | Extracción fallida | > 20 % en 1 h | métrica del worker |
| 4 | Gasto del LLM | > presupuesto mensual | `account_usage` |
| 5 | Cuota de una cuenta | > 80 % de `monthly_email_quota` | `account_usage` |
| 6 | Ingesta de logs | > 70 % del cupo (~35 GiB) | Cloud Logging |
| 7 | Instancias de Cloud Run | > 2 durante 10 min | *Container instance count* |
| 8 | Requests del servicio | > 70 % del cupo (1,4M de 2M) | Cloud Monitoring |
| 9 | Neon | > 70 % de storage (700 MB) o de cómputo | consola de Neon |
| 10 | Correos en `needs_review` | > 5 acumulados | métrica del worker |
| 11 | Rechazos del `MoneyGuard` | > 0 en 24 h | métrica del worker |

Además de las alertas: **revisión manual de la facturación el día 1 de cada mes**, cinco minutos,
aunque marque USD 0.

**La cuota (ADR 0012) está especificada pero no activa en el v0**: con un solo usuario que además es el
dueño del proyecto, el límite es él. La decisión queda escrita para el día que entre un segundo usuario,
no borrada.

### Runbooks

**1. Silencio de ingesta.** Tres causas, en este orden: el `watch` de Gmail venció (renovar);
la suscripción de Pub/Sub está mal o el webhook falló (revisar la última ejecución); o el **refresh token
de Google fue revocado** (`invalid_grant` en el log de ingesta → marcar `needs_reauth` y pedir
reconexión).

**2. Pendiente viejo.** Mirar `attempts`, `next_attempt_at` y `locked_at`; si el lock está vencido, el
worker murió → logs del worker; si todos los pendientes tienen error del LLM, revisar cuota y proveedor.

**3. Extracción fallida.** Leer el error agrupado (timeout / 429 / JSON inválido); si es JSON, sospechar
cambio de modelo o de prompt y correr las evals contra el dataset antes de tocar nada.

**4. Gasto del LLM.** Revisar correos procesados y tokens por correo; si el promedio subió, buscar
correos con adjuntos o HTML gigante. Si es volumen legítimo, ajustar el presupuesto, no apagar la
extracción.

**6. Logs.** Buscar el `service` que más ingesta aporta en Logs Explorer; casi siempre es un health
check o un bucle de reintento. Si es un bucle, arreglar la causa antes de agregar exclusiones.

**10. `needs_review` acumulado.** Leer el `parser_id` y el motivo del rechazo: si todos vienen del mismo
dominio, ese emisor cambió su plantilla → escribir la plantilla nueva con un fixture del correo real.
Mientras tanto los correos están guardados, no perdidos.

**11. Rechazo del `MoneyGuard`.** El modelo propuso un monto que no aparece en el texto. Revisar el
crudo, comparar con lo que devolvió el modelo y agregar el caso al dataset de evals: el rechazo es el
sistema funcionando, pero el caso es una laguna del prompt o de la plantilla.

## Logs y observabilidad

Los logs son el rubro donde un v0 "gratis" empieza a cobrar sin que nadie toque infraestructura.

### Cómo se cobra Cloud Logging

| Eje | Qué incluye |
|---|---|
| **Ingesta** | ~50 GiB por proyecto por mes; lo que excede, ~USD 0,50/GiB |
| **Retención** | 30 días incluidos en el bucket `_Default`; más tiempo, se cobra por GB-mes |
| **Auditoría** | Actividad administrativa gratis; logs de acceso a datos, se cobran |

En Cloud Run todo lo que la aplicación escribe a **stdout/stderr se ingesta automáticamente**: es
opt-out, no opt-in.

### Qué consume el cupo en la práctica

No los logs de negocio, los del framework y la infraestructura:

- **Health checks**: una línea por request cada pocos segundos, 24/7. El gasto más común y el más inútil.
- **Arranques de JVM** con stack traces y warnings de Spring.
- **Un log por push** con el payload completo, que además es PII.
- **Bucles de reintento** que repiten el mismo error con stack trace.

### Política del proyecto

- **JSON estructurado a stdout**, niveles `INFO/WARN/ERROR`. `DEBUG` no existe en producción.
- **Nunca PII en logs**: sin cuerpo, sin asunto, sin montos. Sí `messageId`, `account` seudonimizado,
  resultado, latencia, tokens y costo. Es requisito del ADR 0007.
- **Excluir health checks** del bucket por defecto con una exclusión en el sink:

  ```bash
  gcloud logging sinks update _Default \
    --add-exclusion=name=skip-health-checks,filter='httpRequest.requestUrl:"/actuator/health"'
  ```

- **Retención 30 días**, sin extender.
- **Logs para explicar, métricas para contar**: p95, costo por correo y tasa de fallo van como métricas
  de Micrometer, no como líneas de log que después hay que contar caras.
- **Alerta al 70 % del cupo de ingesta** (~35 GiB), en la tabla de [Alertas](#alertas): detecta un
  defecto que infla el volumen, no el crecimiento orgánico.

### Lo que no se hace

Exportar logs a BigQuery ni montar ELK. Logs Explorer más métricas de Cloud Monitoring alcanza; cada
pieza extra es costo y superficie que defender en una entrevista.

## Costos y control de gasto

**Regla del proyecto: no se gasta dinero.** El v0 tiene que correr dentro de lo gratuito, y si algo se
acerca al límite, se corta o se diseña distinto — no se paga.

### Dos cosas que se confunden

| Programa | Qué es | ¿Vence? |
|---|---|---|
| **Free Trial** | USD 300 de crédito, una sola vez | Sí: 90 días o hasta consumir el crédito |
| **Always Free** | Cupo mensual por producto, permanente | No: se reinicia cada mes |

Cupos Always Free que usamos: **Cloud Run** 2M requests, 360K GB-seg de memoria y 180K vCPU-seg por
mes (calculado con precios de `us-central1`); **Pub/Sub** 10 GB de mensajes. Se agregan **por cuenta de
facturación**, no por proyecto. **Cloud SQL no tiene tier gratuito** — por eso la base es Neon, cuyo
plan gratis no tiene límite de tiempo (1 GB de storage y 100 horas de cómputo por proyecto al mes).

**Always Free es una franquicia, no un tope:** si se excede, se factura la diferencia. Nadie te avisa
solo, así que los avisos hay que configurarlos.

### Presupuesto (configurar antes del primer deploy)

- **Alertas escalonadas de USD 1, 5 y 20** sobre la cuenta de facturación. Los presupuestos **avisan, no
  bloquean**: sirven para enterarse el mismo día, no para frenar.
- Los demás avisos de uso —instancias, requests, Neon y cuota por cuenta— viven en una sola tabla en
  [Alertas](#alertas), para que no existan dos listas que se desincronicen.

### Topes duros (no dependen de que alguien mire)

- **Máximo de instancias = 1** por servicio. Es un ajuste **por servicio**, no global: cada servicio de
  Cloud Run tiene su propio máximo, acotado por las cuotas del proyecto. Poner 1 acá **no impide**
  desplegar otros servicios o proyectos.
- **Concurrencia y timeout** acotados, para que una instancia no acumule trabajo indefinido.
- **Flow control en la suscripción** de Pub/Sub, para que un bucle de reintentos no genere requests sin
  techo.
- **Rate limiting en la aplicación** (Bucket4j) desde el v0: la API pública es el punto por donde
  alguien podría consumir el cupo gratuito. Cloudflare lo haría mejor en el edge, pero suma un
  componente que el v0 no necesita (ADR 0005).
- **Cualquier producto de pago requiere un ADR** que justifique salir del tier gratuito. Sin ADR, no se
  habilita.

### Costos v0 (estimado)

| Componente | Costo mensual |
|---|---|
| Cloud Run (scale-to-zero, free tier) | USD 0 |
| Pub/Sub | USD 0 |
| PostgreSQL (Neon gratis) | USD 0 |
| LLM (centavos por correo) | USD 1 – 5 |
| Cloudflare (DNS, WAF) | USD 0 · opcional, fuera del v0 |

Total: **USD 0 – 5/mes**, y el único costo real es el del modelo. Si el tier gratuito de GCP
desapareciera, el reemplazo costaría USD 10–18/mes: eso es el techo de exposición, no el presupuesto.


## Límites de Google que definen el producto

- **Cuotas de la Gmail API**: por usuario y por proyecto; se manejan con token bucket y respetando
  `Retry-After`.
- **`watch`**: expira a los 7 días (ADR 0007).
- **Scopes restringidos**: `gmail.readonly` es un *restricted scope*. Para pasar del modo prueba (100
  usuarios) a una app pública, Google exige verificación y una evaluación de seguridad (CASA) anual,
  con costo real de miles de dólares. **Esto es la barrera de monetización, no la infraestructura.**
  El v0 funciona con cuentas propias y usuarios de prueba sin trámite.
