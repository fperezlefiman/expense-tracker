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
3. Deploy a Cloud Run autenticado por **Workload Identity Federation** (sin llaves JSON).
4. Migraciones Flyway corren al arrancar; `ddl-auto: validate` aborta el deploy si el esquema no
   coincide.

Rollback: se vuelve a la imagen anterior. Las migraciones ya aplicadas no se revierten (ADR 0002):
si un cambio de esquema es incompatible, se despliega en dos pasos.

## Secretos

- Credenciales de Gmail (refresh token) por cuenta, cifradas en base.
- Credenciales de Cloud Run vía service account; nada de llaves estáticas en el repositorio.
- Llave del proveedor de LLM en Secret Manager, inyectada en runtime.

## Alertas

Cuatro, y todas sobre síntoma, no sobre causa. Cada una con su runbook de tres líneas.

**1. Silencio de ingesta** — ningún evento en 12 h.
Runbook: revisar `watch` en Gmail (`users.getProfile` + fecha de expiración guardada) → si venció,
renovar; si no, revisar suscripción Pub/Sub y última ejecución del webhook.

**2. Pendiente viejo** — la fila más antigua con `processed_at = null` supera 15 min.
Runbook: mirar `attempts` y `locked_at`; si el lock está vencido, el worker murió → revisar logs del
worker; si todos los pendientes tienen error del LLM, revisar cuota y proveedor.

**3. Extracción fallida > 20 % en 1 h.**
Runbook: leer el error agrupado (timeout / 429 / JSON inválido); si es JSON, sospechar cambio de modelo
o de prompt y correr evals contra el dataset antes de tocar nada.

**4. Gasto mensual del LLM > presupuesto.**
Runbook: revisar correos procesados y tokens por correo; si el promedio subió, buscar correos con
adjuntos o HTML gigante. Si es volumen legítimo, ajustar el presupuesto, no apagar la extracción.

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
- **Alerta al 70 % del cupo de ingesta** (~35 GiB): detecta un defecto que infla el volumen, no el
  crecimiento orgánico.

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

### Alertas de uso (configurar antes del primer deploy)

1. **Presupuesto con alertas escalonadas**: USD 1, 5 y 20 sobre la cuenta de facturación. Los
   presupuestos **avisan, no bloquean** — sirven para enterarse el mismo día, no para frenar.
2. **Alerta de instancias** sobre el metric *Container instance count* de Cloud Run: si supera 2
   instancias durante 10 minutos, algo está escalando sin control.
3. **Alerta de requests** por servicio al 70 % del cupo mensual (1,4M de los 2M).
4. **Neon**: aviso al 70 % de storage (700 MB) y al 70 % de cómputo mensual.
5. **Revisión mensual de la facturación**: cinco minutos el día 1, aunque marque USD 0.

### Topes duros (no dependen de que alguien mire)

- **Máximo de instancias = 1** por servicio. Es un ajuste **por servicio**, no global: cada servicio de
  Cloud Run tiene su propio máximo, acotado por las cuotas del proyecto. Poner 1 acá **no impide**
  desplegar otros servicios o proyectos.
- **Concurrencia y timeout** acotados, para que una instancia no acumule trabajo indefinido.
- **Flow control en la suscripción** de Pub/Sub, para que un bucle de reintentos no genere requests sin
  techo.
- **Cualquier producto de pago requiere un ADR** que justifique salir del tier gratuito. Sin ADR, no se
  habilita.

### Costos v0 (estimado)

| Componente | Costo mensual |
|---|---|
| Cloud Run (scale-to-zero, free tier) | USD 0 |
| Pub/Sub | USD 0 |
| PostgreSQL (Neon gratis) | USD 0 |
| LLM (centavos por correo) | USD 1 – 5 |
| Cloudflare (DNS, WAF) | USD 0 |

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
