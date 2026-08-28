# Комора — Claude Code guide

**Read [`docs/PRODUCT_BRIEF.md`](docs/PRODUCT_BRIEF.md) before implementing anything.** It holds the
product context (problem, value, feature order, all 10 user flows) mirrored from Notion. Task-level
specs live in the Notion database *Комора — Development Plan*; work one task at a time, in order.

Комора is an AI agent that automates a household's weekly food supply through the official Silpo MCP
server (`https://mcp.silpo.ua/mcp`, OAuth 2.1 + PKCE). It must behave as an agent — persisted state
and a real sequence of MCP tool calls — not as a chat bot.

## Package conventions

Everything lives under `src/main/java/com/silporestockai`. Put new code in the existing package that
fits; do not invent a parallel structure.

| Package | Holds |
|---|---|
| `client` | outbound integrations (`mcp`, `llm`, `stt`, `telegram` subpackages) |
| `config` | `@Configuration`, OpenAPI, global error handling, aspects |
| `controller` | REST controllers |
| `dto` | `request` / `response` models |
| `entity` | JPA entities |
| `exception` | `ApplicationException` + domain errors |
| `job` | `@Scheduled` agent stages (check-in prompts, reorder triggers) |
| `mapper` | MapStruct mappers |
| `model` | domain models, incl. records for Claude structured output |
| `repository` | Spring Data repositories |
| `service` | business logic |
| `utils` | helpers |

## Invariants that break the build, not just style

- **Liquibase owns the schema.** `spring.jpa.hibernate.ddl-auto: validate` — every new `@Entity`
  needs a changeset under `src/main/resources/db/changelog/changes/` (master uses `includeAll`, so
  name files `001-...yaml`, `002-...yaml`) or *every* `@SpringBootTest` fails.
- **ArchUnit is enforced** (`src/test/java/.../architecture/ArchitectureTest.java`): constructor
  injection only (`@RequiredArgsConstructor`, no `@Autowired` fields); `Controller` / `Service` /
  `Repository` / `Scheduler` name suffixes; `Service` reachable only from `Controller` and `Job`.
- **Spotless (palantir)** — run `make format` before pushing; CI runs `spotlessCheck` before `build`.
- **Cache names are a fail-fast list** — add any new cache to `spring.cache.cache-names` in
  `application.yml` or the lookup throws at runtime.
- **Feign + Resilience4j is the outbound-HTTP convention** (see `client/ExampleApiClient`). The one
  deliberate exception is the MCP transport: Streamable HTTP needs SSE and the `Mcp-Session-Id`
  handshake, which Feign cannot express.
- **Config idiom** is `${ENV_VAR:default}` inline in `application.yml`. Secrets never hardcoded;
  `.env` is gitignored, `.env.example` is committed. OAuth tokens stay server-side — never render
  them into anything a client sees.
- `@Slf4j` for logging; no manual `LoggerFactory`.

## Commands

`make run` (app + compose Postgres) · `make dev` (throwaway Testcontainers DB) · `make test` ·
`make format` · `make build` · `make image`. Docker must be running for tests and images.
