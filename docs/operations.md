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

## Costos v0 (estimado)

| Componente | Costo mensual |
|---|---|
| Cloud Run (scale-to-zero, free tier) | USD 0 |
| Pub/Sub | < USD 1 |
| PostgreSQL (Neon gratis / Cloud SQL chico) | USD 0 – 12 |
| LLM (centavos por correo) | USD 1 – 5 |
| Cloudflare (DNS, WAF) | USD 0 |

Total: **USD 1 – 18/mes**.

## Límites de Google que definen el producto

- **Cuotas de la Gmail API**: por usuario y por proyecto; se manejan con token bucket y respetando
  `Retry-After`.
- **`watch`**: expira a los 7 días (ADR 0007).
- **Scopes restringidos**: `gmail.readonly` es un *restricted scope*. Para pasar del modo prueba (100
  usuarios) a una app pública, Google exige verificación y una evaluación de seguridad (CASA) anual,
  con costo real de miles de dólares. **Esto es la barrera de monetización, no la infraestructura.**
  El v0 funciona con cuentas propias y usuarios de prueba sin trámite.
