# Комора

**Комора** is an AI agent that automates a household's weekly food supply. It onboards a household,
generates a weekly meal plan, builds a real Silpo cart through the official Silpo MCP server
(`https://mcp.silpo.ua/mcp`, OAuth 2.1 + PKCE), then runs a repeating check-in → diff → delta-reorder
loop with state persisted across turns. Built for the [Silpo AI Factory](https://ai-factory.silpo.ua)
hackathon.

Read [`docs/PRODUCT_BRIEF.md`](docs/PRODUCT_BRIEF.md) for the product context — problem, value, feature
order, and all ten user flows — before implementing anything. `CLAUDE.md` covers the conventions this
codebase enforces.

The service is bootstrapped from a Spring Boot template, so the infrastructure below is already wired up.

## Stack

| Concern            | Choice                                                            |
|--------------------|-------------------------------------------------------------------|
| Language / runtime | Java 25 (LTS), auto-provisioned via Gradle toolchains             |
| Framework          | Spring Boot 4.1 (Spring Framework 7)                              |
| Build              | Gradle 9.6 (Kotlin DSL) + version-aligned Spring Cloud            |
| Persistence        | Spring Data JPA + PostgreSQL, schema managed by Liquibase         |
| API docs           | springdoc-openapi (Swagger UI at `/swagger-ui.html`)             |
| Mapping / boilerplate | MapStruct + Lombok                                            |
| HTTP clients       | Spring Cloud OpenFeign + Resilience4j (circuit breaker, retry)   |
| Caching            | Spring Cache abstraction backed by Caffeine                      |
| Errors             | RFC 9457 `ProblemDetail` responses                               |
| Testing            | JUnit 5 + Testcontainers (real PostgreSQL), Instancio, ArchUnit  |
| Ops                | Actuator (health/info/metrics/prometheus), graceful shutdown     |
| Tooling            | Spotless (Palantir format), JaCoCo, GitHub Actions CI, Renovate, Docker |

## Prerequisites

- **JDK 25** — or nothing at all; Gradle downloads the right JDK via the toolchain resolver.
- **Docker** — for the local database, containerized builds, and Testcontainers-based tests.

## Quick start

```bash
# Run the app — spring-boot-docker-compose starts PostgreSQL for you
make run          # or: ./gradlew bootRun

# Run against a throwaway Testcontainers DB (no docker-compose, no config)
make dev          # or: ./gradlew bootTestRun

# Run the full test suite
make test         # or: ./gradlew test
```

Once running:

- API base: <http://localhost:8080>
- Swagger UI: <http://localhost:8080/swagger-ui.html>
- Health: <http://localhost:8080/actuator/health>

Run `make help` to see all available commands.

## Configuration

Configuration lives in `src/main/resources/application.yml` and reads from environment variables with
sensible local defaults:

| Variable      | Default                                    | Purpose            |
|---------------|--------------------------------------------|--------------------|
| `APP_NAME`    | `silpo-restock-ai`                         | Application name   |
| `SERVER_PORT` | `8080`                                     | HTTP port          |
| `DB_URL`      | `jdbc:postgresql://localhost:5432/app`     | JDBC URL           |
| `DB_USERNAME` | `app`                                      | DB user            |
| `DB_PASSWORD` | `app`                                      | DB password        |

The schema is owned by **Liquibase** (`src/main/resources/db/changelog`). Hibernate is set to `validate`
only — add your changesets under `db/changelog/changes/`.

### Profiles

Base config is profile-agnostic; two overlays ship out of the box, selected via `SPRING_PROFILES_ACTIVE`:

- **`dev`** (`application-dev.yml`) — verbose logging and easy SQL tracing for local work.
- **`prod`** (`application-prod.yml`) — Hikari timeouts and leak detection, trimmed actuator exposure, `INFO`
  logging. The Compose `app` service sets `SPRING_PROFILES_ACTIVE=prod`.

### Caching & resilience

`spring.cache` is backed by **Caffeine** (tune via `spring.cache.caffeine.spec`); annotate methods with
`@Cacheable`. **Feign** clients are wrapped in a **Resilience4j** circuit breaker
(`spring.cloud.openfeign.circuitbreaker.enabled`) — see `client/ExampleApiClient` and its fallback. Circuit-breaker
and retry defaults live under `resilience4j.*` in `application.yml`.

## Project layout

```
src/main/java/com/silporestockai
├── Application.java          # entry point
├── client/                   # outbound integrations — mcp/, llm/, stt/, telegram/
├── config/                   # @Configuration, OpenAPI, global error handling, aspects
├── controller/               # REST controllers
├── dto/                      # request/response models
├── entity/                   # JPA entities
├── exception/                # ApplicationException + domain errors
├── job/                      # @Scheduled agent stages (check-in prompts, reorder triggers)
├── mapper/                   # MapStruct mappers
├── model/                    # domain models
├── repository/               # Spring Data repositories
├── service/                  # business logic
└── utils/                    # helpers
```

## Testing

Integration tests extend `AbstractIntegrationTest`, which boots the full context against a real PostgreSQL
started by Testcontainers (`TestcontainersConfiguration`) — no local database required. Docker must be
running.

- **Test data** — build objects with **Instancio** via the `support.Fixtures` helper instead of hand-rolling
  fixtures; `InstancioExampleTest` shows the pattern (full random objects, per-field overrides, reproducible seeds).
- **Architecture** — `ArchitectureTest` (ArchUnit) enforces layering, naming, and no field injection. Rules
  tolerate the empty scaffold (`archunit.properties`) and start biting as packages fill in.
- **Coverage** — `./gradlew test` runs **JaCoCo**; the report lands in `build/reports/jacoco/`. Raise the
  threshold in `jacocoTestCoverageVerification` (`build.gradle.kts`) as the codebase grows.

## Containerize

Build a single image:

```bash
make image                       # docker build -t silpo-restock-ai .
docker run --rm -p 8080:8080 silpo-restock-ai
```

Or run the whole stack (app + PostgreSQL) with Compose:

```bash
cp .env.example .env             # tweak values as needed
make up                          # docker compose --env-file .env.example --profile full up --build -d
make down                        # stop it
```

`make up` uses `.env` when present and falls back to `.env.example`. The `app` service sits behind the
Compose `full` profile, so `./gradlew bootRun` still starts only the `db`.

The multi-stage `Dockerfile` produces a layered, non-root image on a slim JRE.

## CI & dependency updates

- **GitHub Actions** (`.github/workflows/ci.yml`) checks formatting, runs `./gradlew build` on every push and
  PR, and uploads the test and coverage reports.
- **Renovate** (`renovate.json`) opens grouped dependency-update PRs and auto-merges safe minor/patch bumps.

## Where the work is planned

- [`docs/PRODUCT_BRIEF.md`](docs/PRODUCT_BRIEF.md) — product context and user flows (mirrored from Notion).
- `CLAUDE.md` — package conventions and the invariants that break the build.
- Notion database *Комора — Development Plan* — the task breakdown; work one task at a time, in order.

## License

[MIT](LICENSE)
