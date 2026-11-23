# 0005 — Deploy en Cloud Run + Pub/Sub con OIDC

**Estado:** propuesto · **Fecha:** 2026-11-23

## Contexto

El `watch` de Gmail publica en un topic de Pub/Sub, y la suscripción push entrega contra un endpoint
HTTPS. Se necesita correr una aplicación JVM con PostgreSQL, un webhook que reciba esos push y un
worker que haga la extracción, con un costo de v0 cercano a cero y una historia de producción
defendible.

## Decisión

- **Cloud Run** para el webhook, el worker y la API, como servicios separados. Webhook y worker **sin
  ingress público**; Pub/Sub se autentica con **OIDC** (`--no-allow-unauthenticated`).
- **Pub/Sub** para la ingesta y para la cola de trabajos, con **dead letter topic** propio.
- **PostgreSQL**: Neon para el v0 (tier gratis, branching para tests); Cloud SQL cuando haya clientes
  que paguen.
- **Cloudflare** delante: DNS, WAF, rate limiting y la landing pública. No ejecuta la aplicación.
- **GitHub Actions con Workload Identity Federation**, sin llaves JSON en secretos.

## Consecuencias

- El endpoint de ingesta no queda expuesto a internet: no hay que verificar tokens a mano.
- Cloud Run escala a cero: costo v0 estimado en USD 0–15/mes (free tier de 2M requests, Pub/Sub
  despreciable, Postgres gratis o ~USD 10).
- El arranque en frío de la JVM (~5–10 s) obliga a mantener una instancia mínima en el webhook si la
  latencia de ack importa.
- Acoplamiento a GCP, que ya era inevitable por Pub/Sub.

## Alternativas rechazadas

- **Cloudflare Containers** (GA desde abril de 2026, plan Workers Paid): puede correr el contenedor,
  pero no ofrece PostgreSQL, la instancia mínima (1/16 vCPU, 256 MiB) no alcanza para una JVM y el
  arranque en frío es peor. Cloudflare aporta más como CDN/WAF que como runtime.
- **Railway o Render + Neon**: los más rápidos de arrancar, pero el endpoint queda público y la
  verificación OIDC de Pub/Sub hay que escribirla.
- **VPS propio (Hetzner/Coolify)**: barato y con control total, pero los backups, parches y
  disponibilidad pasan a ser responsabilidad propia. Para algo que se quiere cobrar, no.
