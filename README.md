# Climind SaaS — Climind Cotiza

**Climind SaaS** es la línea de productos por suscripción de Climind-IA. Su primer producto es **Climind Cotiza**: los negocios crean cotizadores sin programar, comparten un enlace y sus clientes obtienen un presupuesto estimado en segundos.

> Estado actual: **Fases 1 a 5 implementadas.** Incluye:
> - registro y negocio, y constructor de cotizadores con versiones inmutables;
> - página pública donde el cliente cotiza (el servidor calcula), consultas, PDF y estadísticas;
> - planes con cupos y suscripciones de Mercado Pago;
> - landing con demo en vivo y precios desde la base, centro de ayuda y emails de producto;
> - exportación de datos y baja de cuenta;
> - imagen Docker y backups con restauración probados.
>
> Para salir a vender falta la configuración externa: Mercado Pago real, SMTP, DNS, EasyPanel, destino de backups y revisión legal. Ver [ROADMAP](docs/ROADMAP.md).

## Stack

| Capa | Tecnología |
|---|---|
| Backend | Java 21, Spring Boot 4.1 (Web MVC, Data JPA/Hibernate 7, Security 7, Validation, Session JDBC), Flyway, MapStruct, springdoc-openapi |
| Base de datos | PostgreSQL 17 |
| Frontend | HTML + CSS + JavaScript (ES Modules), Bootstrap 5.3 vendorizado, sin paso de build |
| Tests | JUnit 5, PostgreSQL embebido real (zonky), ArchUnit, Playwright |
| Deploy | Un contenedor Docker (API + frontend) + PostgreSQL, en EasyPanel |

## Estructura

```
backend/    API Spring Boot (monolito modular)            → docs/ARCHITECTURE.md
frontend/   cotiza/ (app), saas/ (portal), shared/, vendor/
database/   notas de base de datos, backups y restore     → docs/DATABASE.md
infra/      docker-compose, variables de ejemplo y backup → docs/DEPLOYMENT.md
tests/e2e/  recorridos críticos en navegador (Playwright) → docs/TESTING.md
docs/       documentación del proyecto
Dockerfile  imagen única de producción
```

## Requisitos

- **JDK 21** (en Windows, si el JDK por defecto es otro: `export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.7.6-hotspot"` en Git Bash).
- Maven 3.9+ (o `backend/mvnw`).
- Node 20+ solo para los tests E2E.
- Docker es **opcional** en desarrollo.

## Ejecutar en desarrollo (sin Docker)

```bash
cd backend
./mvnw spring-boot:test-run -Dspring-boot.run.main-class=com.climind.saas.LocalDevApplication
```

Levanta PostgreSQL 17 embebido (datos efímeros) y la app con perfil `dev` en http://localhost:8080:

| URL | Qué es |
|---|---|
| `/` | Landing de Climind Cotiza (con demo en vivo y precios) |
| `/precios`, `/ayuda` | Página de precios y centro de ayuda |
| `/c/demo/limpieza` | Demo pública (negocio ficticio; también `catering` e `instalaciones`) |
| `/registro`, `/ingresar` | Alta e ingreso |
| `/app/` | Panel del negocio (`/app/cotizadores`, `/app/editor`, `/app/consultas`, `/app/estadisticas`, `/app/plan`, `/app/negocio`, `/app/cuenta`, `/app/onboarding`) |
| `/c/{negocio}/{cotizador}` | Cotizador público (el enlace aparece en la pestaña Publicación del editor) |
| `/saas/` | Portal Climind SaaS |
| `/api/docs` | Swagger UI (solo dev) |
| `/api/dev/emails` | Bandeja de emails de desarrollo (links de verificación y recuperación) |
| `/app/plan` | Planes: en desarrollo los pagos usan un simulador (no se cobra nada) |

Super admin de desarrollo: `admin@cotiza.local` / `Climind-dev-2026` (solo perfil `dev`).

El frontend se sirve directo desde `frontend/`: los cambios en HTML/CSS/JS se ven al recargar.

## Ejecutar con Docker

```bash
cp infra/.env.example infra/.env     # completar DB_PASSWORD
docker compose -f infra/docker-compose.yml --env-file infra/.env up --build
```

App en http://localhost:8080 y emails en Mailpit (http://localhost:8025). Incluye el servicio de backups: `docker compose -f infra/docker-compose.yml --env-file infra/.env run --rm backup now` hace un backup y prueba restaurarlo.

## Tests

```bash
cd backend && ./mvnw test                               # 198 tests: unitarios + integración + arquitectura
cd tests/e2e && npm ci && npx playwright test           # 28 recorridos (móvil + escritorio)
E2E_BROWSERS=all npx playwright test                    # + Safari de iPhone (WebKit) y Firefox
```

Detalle en [docs/TESTING.md](docs/TESTING.md).

## Documentación

[Arquitectura](docs/ARCHITECTURE.md) · [Base de datos](docs/DATABASE.md) · [API](docs/API.md) · [Reglas de negocio](docs/BUSINESS_RULES.md) · [Variables de entorno](docs/ENVIRONMENT.md) · [Despliegue](docs/DEPLOYMENT.md) · [Tests](docs/TESTING.md) · [Roadmap](docs/ROADMAP.md)
