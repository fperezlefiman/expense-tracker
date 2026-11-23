# 0001 — Java 25 LTS + Spring Boot 4.2

**Estado:** aceptado · **Fecha:** 2026-11-23

## Contexto

El repositorio tiene que ser legible por dos públicos muy distintos: el mercado backend corporativo
chileno (banca, retail, consultoras) y equipos de producto. Por volumen, Java + Spring es el stack
backend más pedido en Chile: al 23-nov-2026, Computrabajo Chile mostraba 158 avisos de Java contra 12
de Kotlin y 13 de Node.js.

## Decisión

Backend en **Java 25 LTS** con **Spring Boot 4.2**.

Java 25 es la LTS vigente (publicada el 2025-09-16, soporte hasta 2030) y Spring Boot 4.2 la soporta
(requiere Java 17 o superior, compatible hasta Java 27). Arrancar en Java 21 habría sido empezar dos
LTS atrás sin ninguna ventaja.

## Consecuencias

- El código habla el vocabulario exacto de los avisos: `@RestController`, `@Transactional`, filtros de
  Spring Security, Actuator.
- El conocimiento previo de Kotlin/Ktor se transfiere casi uno a uno: Koin ↔ `@Component`/`@Bean`,
  Exposed ↔ JDBC/JPA, plugins de Ktor ↔ filtros e interceptores.
- Más ceremonia y arranque más lento que Ktor para un servicio chico. Se acepta: el repositorio también
  es evidencia para una búsqueda laboral, no solo una herramienta.

## Alternativas rechazadas

- **Kotlin + Ktor**: lo que ya hago a diario, pero me deja fuera de las búsquedas por palabra clave
  Java/Spring y no enseña nada nuevo.
- **Node.js/TypeScript**: dominante en Get on Board, casi ausente en los avisos corporativos que pagan
  la banda objetivo.
- **Java 21**: LTS anterior, sin beneficio y con menos recorrido de soporte por delante.
