# Architecture rules and the coverage gate — design

Notion task **16. Testcontainers + ArchUnit coverage for new modules**. A quality pass over what
tasks 1–15 built, not a feature.

## Problem

Two gates were tuned for an empty scaffold and never retuned:

- `archunit.properties` set `archRule.failOnEmptyShould=false`, so a rule matching nothing passed —
  reasonable when the domain packages were empty, dangerous now that a deleted package would silently
  disable its own rule.
- `jacocoTestCoverageVerification` had `minimum = 0.0`, which is not a gate.

And the rules themselves only checked naming and layering. Naming is the cheapest property to hold;
the ones actually worth enforcing are the invariants this codebase decided on and wrote down.

## Measured, then set

Coverage before touching anything, over `build/reports/jacoco`:

| Counter | Before | After |
|---|---|---|
| Instruction | 91.0% | 91.2% |
| Branch | 70.7% | 71.0% |
| Line | 90.0% | 90.3% |

The gate is **85% instruction, 65% branch** — a few points under measured, so an honest refactor has
room and a deleted test suite does not. Both counters, not just instructions: instruction coverage
alone is satisfied by walking straight lines through branchy code.

`archRule.failOnEmptyShould` goes back to its default. Every package the rules name has classes in it.

## The rules worth having

Naming and layering stay. Five new rules encode decisions that are otherwise only comments in
`CLAUDE.md`:

- **Anthropic SDK stays inside `client.claude`**, mirroring the Telegram rule from #3. A service that
  imports `MessageCreateParams` is a service that cannot be pointed at another model.
- **MCP SDK stays inside `client.mcp`** — transport, session handshake and refresh-on-401 in one place.
- **Stored OAuth tokens never reach the web layer.** Controllers are the only classes that can put
  something on the wire towards a browser or a chat, so keeping `SilpoOAuthToken`, its repository and
  `TokenCipher` out of their reach turns "tokens stay server-side" into a build property.
- **`model` records carry no framework** — no Spring, no JPA, no Telegram SDK. They cross a JSON column,
  a Claude schema and a Telegram message in one shape, and each of those would otherwise pull its
  annotations in.
- **`@Scheduled` only in `job`**, so every agent stage that runs on a clock is findable in one package.

Plus ArchUnit's own `NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS` and `NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING`
— the project uses `@Slf4j` and nothing should print.

A rule considered and rejected: "no `Instant.now()` outside a `Clock`". Three services legitimately
stamp rows with the current time and the injected `Clock` is used where a date is *derived* rather than
recorded; the rule would have been noise plus exemptions.

## Coverage gaps found, and what happened to them

| Gap | Action |
|---|---|
| `CheckinScheduler` never executed by any test | covered — a test drives the scheduled method itself |
| Voice check-in with no STT key configured | covered — asserted in the context that leaves `stt.api-key` blank |
| A leftover keyboard tapped during a check-in | covered |
| Exception classes with only constructors | left; a test asserting a constructor sets a message tests Java |
| `SilpoOAuthApiClient` error branches (92%) | left as a follow-up: the uncovered paths are Feign decoder branches that need a stub OAuth server returning malformed bodies |
| `ClaudeApiClient` interface default paths (91%) | left; covered indirectly through every service that calls it |

## CI

`.github/workflows/ci.yml` runs `spotlessCheck` then `./gradlew build`; `check` depends on
`jacocoTestCoverageVerification`, so the new gate is enforced there without a workflow change. Docker
is present on `ubuntu-latest`, which is what Testcontainers needs. No drift found, nothing changed.
