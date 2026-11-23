# 0002 — Flyway para migraciones, Hibernate en `validate`

**Estado:** aceptado · **Fecha:** 2026-11-23

## Contexto

El esquema va a cambiar con el tiempo y no puede modificarse a mano en la base. Además, el esquema del
código tiene que coincidir de forma comprobable con el de producción.

## Decisión

- **Flyway** con migraciones SQL planas en `src/main/resources/db/migration` (`V1__...sql`).
- Hibernate corre con **`ddl-auto: validate`**: nunca crea ni altera tablas; solo hace fallar el
  arranque cuando las entidades y el esquema migrado no coinciden.

## Consecuencias

- Cada cambio de esquema es un archivo SQL revisable en el pull request.
- Una divergencia es un fallo de arranque, no una corrupción silenciosa descubierta semanas después.
- Renombrar una columna exige migración y cambio de entidad en el mismo commit: no hay almuerzo gratis,
  y ese es el punto.
- El rollback es una migración nueva hacia adelante (Flyway Community no tiene undo); aceptable para un
  servicio que despliega hacia adelante.

## Alternativas rechazadas

- **Liquibase**: mejor cuando hay varios motores y rollback declarativo (habitual en banca con Oracle +
  SQL Server + Postgres). Sobrecarga innecesaria acá.
- **`ddl-auto: update`**: cómodo y el error de producción más común en proyectos Spring; esconde
  cambios destructivos y no se puede revisar en el PR.

## Lo que habilita en una entrevista

"¿Por qué Flyway y no Liquibase?" — un solo motor, SQL visible en el diff, sin capa de abstracción.
"¿Por qué no `ddl-auto: update`?" — porque el esquema dejaría de ser código.
