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

### Silpo MCP

Every product feature goes through the official Silpo MCP server, which is OAuth 2.1 + PKCE protected:

| Variable                      | Default                                   | Purpose                                        |
|-------------------------------|-------------------------------------------|------------------------------------------------|
| `SILPO_MCP_ENDPOINT`          | `https://mcp.silpo.ua/mcp`                | Streamable HTTP MCP endpoint                   |
| `SILPO_MCP_ISSUER`            | `https://mcp.silpo.ua`                    | OAuth issuer (`/authorize`, `/token`, `/register`) |
| `SILPO_MCP_RESOURCE`          | `https://mcp.silpo.ua/mcp`                | RFC 8707 `resource` indicator                  |
| `SILPO_MCP_CLIENT_ID`         | *(empty)*                                 | Registered client id; empty triggers Dynamic Client Registration on first use |
| `SILPO_MCP_REDIRECT_URI`      | `http://localhost:8080/auth/silpo/callback` | OAuth callback                               |
| `SILPO_TOKEN_ENCRYPTION_KEY`  | *(empty)*                                 | Base64 AES-256 key for tokens at rest; generate with `openssl rand -base64 32` |

Connect an account by opening `/auth/silpo/start?userId=<uuid>` and logging in with a Silpo phone + OTP.
Tokens are stored **AES-256-GCM encrypted** in `mcp_oauth_token`, never logged, and never returned by an
endpoint. With `SILPO_TOKEN_ENCRYPTION_KEY` unset the app generates an ephemeral key and warns at startup —
stored tokens will not survive a restart, which is fine for local work only.

### Telegram

| Variable                  | Default   | Purpose                                                                                                 |
|---------------------------|-----------|---------------------------------------------------------------------------------------------------------|
| `TELEGRAM_BOT_TOKEN`      | *(empty)* | Bot token from [@BotFather](https://t.me/BotFather)                                                       |
| `TELEGRAM_WEBHOOK_URL`    | *(empty)* | Public HTTPS URL of `POST /telegram/webhook`; blank skips registration at startup                          |
| `TELEGRAM_WEBHOOK_SECRET` | *(empty)* | Shared secret Telegram echoes in `X-Telegram-Bot-Api-Secret-Token`; generate with `openssl rand -hex 32`   |

#### Running the webhook locally

Telegram only delivers to a public HTTPS URL, so a local run needs a tunnel:

1. Start the tunnel: `ngrok http 8080` (any equivalent works — Cloudflare Tunnel, localtunnel).
2. Copy the `https://` forwarding URL ngrok prints.
3. Put it in `.env` together with a secret:

   ```bash
   TELEGRAM_BOT_TOKEN=<token from @BotFather>
   TELEGRAM_WEBHOOK_URL=https://<subdomain>.ngrok-free.app/telegram/webhook
   TELEGRAM_WEBHOOK_SECRET=$(openssl rand -hex 32)
   ```

4. `make run`. The app calls `setWebhook` on startup and logs `registered the Telegram webhook at …`.
5. Message the bot. It answers with the onboarding welcome, which proves webhook → router →
   conversation state → outbound.
6. Check what Telegram thinks it is delivering to with
   `curl https://api.telegram.org/bot<token>/getWebhookInfo`.

The URL changes every time the tunnel restarts, so step 3 repeats each session. Without
`TELEGRAM_WEBHOOK_URL` the app boots normally and never contacts Telegram. A failed registration is logged
and does not stop the app.

#### Onboarding

The first message any new chat sends starts onboarding. The bot creates the user row, offers a Silpo
connect link, and — once connected — reads `silpo_get_my_family`, `silpo_get_my_food_restrictions`,
`silpo_get_my_online_orders` and `silpo_get_my_favorites`, letting Claude turn whatever they return into a
profile snapshot. Only the fields Silpo could not supply are asked, plus the weekly budget, which it never
knows.

Skipping the connect step, an unreachable Silpo, and a guest with no order history all take the same
fallback: the bot asks directly. Onboarding ends with a saved `user_profile` and an
`OnboardingCompletedEvent`.

#### The first weekly plan

`MealPlanHandoffService` picks that event up asynchronously — the webhook thread must not wait on a model
call — and asks `MealPlanService` for a week of meals. The system prompt is
`src/main/resources/prompts/meal-plan-system.txt`, so wording can be changed without recompiling; the
household's own constraints go in the user message.

A plan that comes back missing days, or with a day that has fewer than three meals, is not stored: the
service retries once with the defect named in the prompt and raises `MealPlanGenerationException` if the
second answer is also unusable. Regeneration (`regenerateWithAdjustment`, for "мінус 200 ккал на день")
writes a new `meal_plan` row and leaves the old one, because showing what changed between two plans needs
both.

`ShoppingListService` then collapses the plan into the list somebody actually shops from — no model call,
just arithmetic: one line per ingredient and unit, quantities summed across every meal that uses it. The
same ingredient in two units (2 шт цибулі and 200 г цибулі) deliberately stays two lines rather than being
converted on a guess. Deriving again for a plan replaces its lines; an ad-hoc list (`createAdHocList`, for
the Friday-night snacks) carries a `user_id`, no `meal_plan_id`, and is never touched by a regeneration.

#### Building a real Silpo cart

`CartBuildingService.buildCart(userId, items)` runs the documented sequence — `silpo_get_my_shopping_cart`,
`silpo_get_shopping_cart_by_id`, `silpo_get_time_slots`, `silpo_find_products_batch` (chunked at 30),
`silpo_add_or_update_cart_products`, then `silpo_get_shopping_cart_by_id` again to verify. Every call is
logged at INFO as `MCP -> tool {args}` / `MCP <- result`, which is the evidence log the hackathon asks for:
record the console during a run and the JSON-RPC conversation is visible.

Items Silpo cannot match come back in `CartSummary.unresolved` rather than disappearing. Loyalty bonuses are
reported (`bonusAvailable`, `bonusDecisionPending`) and never spent — confirming that is task 10's job. No
time slot at all is fatal: a cart nobody can deliver fails here rather than at checkout.

**Smoke-testing it against the real server** (needs a real Silpo account; nothing in CI can do this):

1. `make run`, then complete the Silpo OAuth login so an `mcp_oauth_token` row exists for the user.
2. Finish onboarding in Telegram, so a `user_profile`, a `meal_plan` and its `shopping_list_item` rows exist.
3. Call `buildCart` for that user — from a REST controller once task 10 adds one, or from a scratch
   `@SpringBootTest` pointed at the live endpoint.
4. Watch the log: six `MCP ->` lines in the documented order, then a `verified` line with a non-zero item
   count. Opening `checkoutWebLink` should show the same cart in Silpo's web checkout.

Tool names and response keys come from the MCP documentation and have not been exercised against the live
server. If a key differs, it is a one-line fix in `utils/McpResponses`, where every key name this application
depends on is declared.

### Anthropic Claude

Used for meal plan generation, check-in parsing and (stretch) fridge-photo parsing:

| Variable               | Default           | Purpose                                                                          |
|------------------------|-------------------|----------------------------------------------------------------------------------|
| `ANTHROPIC_API_KEY`    | *(empty)*         | API key; blank makes Claude calls fail with a clear message, the app still boots   |
| `ANTHROPIC_MODEL`      | `claude-sonnet-5` | Model id                                                                          |
| `ANTHROPIC_MAX_TOKENS` | `4096`            | Output token ceiling per call                                                     |
| `ANTHROPIC_TIMEOUT`    | `120s`            | Per-request timeout; meal plan generation is slow                                  |

Retry and circuit-breaker behaviour lives under `resilience4j.retry.instances.claude` and
`resilience4j.circuitbreaker.instances.claude` in `application.yml`. Only rate limits and upstream outages
are retried; a malformed request fails on the first attempt and does not count towards opening the breaker.

Structured output uses the SDK's native output config: `completeStructured(system, user, MyRecord.class)`
sends a schema derived from the record and returns a populated instance, so malformed model output surfaces
as `ClaudeStructuredOutputException` rather than a parse crash.

To smoke-test against the real API, export a key and point the client at production:

```bash
ANTHROPIC_API_KEY=sk-ant-... ANTHROPIC_BASE_URL=https://api.anthropic.com make run
```

### Fridge photos (vision check-ins)

A photo sent while a check-in is open is read by Claude and becomes the same three-bucket delta a typed
answer produces. It needs no configuration beyond `ANTHROPIC_API_KEY`, and it is deliberately rough:
the prompt forbids putting anything into "gone" that is merely not visible, and the reply carries a
disclaimer so the user can correct it.

To try it end to end, with a key exported and the bot running:

1. wait for a check-in prompt, or force one — `komora.checkin.interval` accepts `1m` for a demo;
2. send two or three photos of an open fridge or a shelf, one per check-in;
3. read the acknowledgement and the `checkin` rows — `source = PHOTO` marks this path.

Photos of a mostly-empty shelf and of a full one make the difference legible in a recording.

### Speech to text (voice check-ins)

The Anthropic Messages API takes text, images and PDFs — not audio — so a voice check-in needs a
transcription service of its own. Any OpenAI-compatible `/v1/audio/transcriptions` endpoint works:

| Variable       | Default                                          | Purpose                                                       |
|----------------|--------------------------------------------------|---------------------------------------------------------------|
| `STT_API_KEY`  | *(empty)*                                        | Bearer token; blank disables voice, the bot asks for text      |
| `STT_ENDPOINT` | `https://api.openai.com/v1/audio/transcriptions` | Point at Groq or a local whisper server to change providers    |
| `STT_MODEL`    | `whisper-1`                                      | Transcription model id                                         |
| `STT_LANGUAGE` | `uk`                                             | Language hint, so the model does not have to guess Ukrainian   |

Blank is a supported configuration, not a broken one: a voice note is answered with *"Голосові поки не
розбираю. Напиши, будь ласка, текстом."* and every other path keeps working.

To smoke-test the voice path end to end, export a key, send the bot a voice note while a check-in is
open, and watch for the `transcribed N bytes of audio` line:

```bash
STT_API_KEY=sk-... make run
```

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
