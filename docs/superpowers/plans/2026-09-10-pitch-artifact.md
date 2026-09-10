# Pitch Artifact Implementation Plan (task 55)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One self-contained HTML page, generated from a real run's own database rows, listing every `silpo_*` MCP tool this system actually called and how often each chat intent fired — served as a committed static file at `https://<domain>/pitch.html`.

**Architecture:** The tool matrix reads `mcp_tool_call`, the table task 37 already fills through `McpToolCalledEvent` → `McpCallLogService`. The intent distribution needs a new evidence table built the same way: `IntentRouterService` publishes an `IntentClassifiedEvent` at the three points it already calls `observabilityService.recordIntent(...)`, and `IntentLogService` writes one `intent_classification` row per event, swallowing its own failures. `PitchArtifactService` renders both lists into HTML through a static method; `InternalMetricsController` exposes it behind the existing `X-Metrics-Token`; `make pitch-artifact` curls it into `src/main/resources/static/pitch.html`, which is committed and baked into the image.

**Tech Stack:** Java 25 / Spring Boot 4.1, Spring Data JPA + PostgreSQL, Liquibase, Spring `ApplicationEventPublisher`, JUnit 5 + Testcontainers + AssertJ, MockMvc.

**Spec:** `docs/superpowers/specs/2026-09-10-pitch-artifact-design.md`

## Global Constraints

- Liquibase owns the schema (`ddl-auto: validate`): the new entity needs `034-intent-classification.yaml` under `src/main/resources/db/changelog/changes/`, or every `@SpringBootTest` fails.
- Constructor injection only (`@RequiredArgsConstructor`); `Controller` / `Service` / `Repository` / `Scheduler` name suffixes; `@Slf4j` for logging. ArchUnit enforces all of it.
- The evidence log never breaks a working flow: `IntentLogService` catches `RuntimeException` and logs at WARN, exactly as `McpCallLogService` does.
- The public page carries tool names, counters, intent names and hand-written notes only — no chat text, no product names, no user identifiers.
- `make format` before every commit; `make test` green before the final commit (stop the running app first — it shares `build/classes`).
- The page is a point-in-time snapshot. Nothing public reads the database per request.

---

### Task 1: `intent_classification` — the evidence table behind the distribution

**Files:**
- Create: `src/main/resources/db/changelog/changes/034-intent-classification.yaml`
- Create: `src/main/java/com/silporestockai/entity/IntentClassification.java`
- Create: `src/main/java/com/silporestockai/repository/IntentClassificationRepository.java`
- Create: `src/main/java/com/silporestockai/model/IntentClassifiedEvent.java`
- Create: `src/main/java/com/silporestockai/service/IntentLogService.java`
- Modify: `src/main/java/com/silporestockai/service/IntentRouterService.java` (the three `recordIntent` call sites at ~183, ~190, ~196, plus the constructor)
- Test: `src/test/java/com/silporestockai/integration/IntentClassificationIntegrationTest.java`

**Interfaces:**
- Produces: `IntentClassification` entity with `getIntent()`, `getOutcome()`, `getConfidence()`, `getUserId()`, `getClassifiedAt()`; `IntentClassificationRepository extends JpaRepository<IntentClassification, UUID>`; `record IntentClassifiedEvent(String intent, String outcome, Double confidence, UUID userId, Instant classifiedAt)`; the outcome constants `ROUTED` / `UNCLASSIFIED` / `FAILED` as `String` literals in `IntentLogService`.

- [ ] **Step 1: Write the failing integration test**

Model it on `IntentRouterIntegrationTest` (same package) for how the Claude client is stubbed and how a user is created.

```java
package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.entity.IntentClassification;
import com.silporestockai.repository.IntentClassificationRepository;
import org.junit.jupiter.api.Test;

/** Task 55: every classification leaves one row, including the ones that did not work. */
class IntentClassificationIntegrationTest extends AbstractIntegrationTest {
    // Autowire IntentRouterService, IntentClassificationRepository and the same Claude stub
    // IntentRouterIntegrationTest uses; route one sentence the stub classifies as HELP.

    @Test
    void routedClassificationIsRecordedWithItsIntentName() {
        // route a message the stub answers as {"intent":"HELP","confidence":0.95}
        assertThat(repository.findAll())
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getIntent()).isEqualTo("HELP");
                    assertThat(row.getOutcome()).isEqualTo("ROUTED");
                    assertThat(row.getConfidence()).isEqualTo(0.95);
                    assertThat(row.getClassifiedAt()).isNotNull();
                });
    }

    @Test
    void lowConfidenceIsRecordedAsUnclassified() {
        // stub answers {"intent":"HELP","confidence":0.2}
        assertThat(repository.findAll())
                .singleElement()
                .extracting(IntentClassification::getIntent, IntentClassification::getOutcome)
                .containsExactly("UNCLASSIFIED", "UNCLASSIFIED");
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests '*IntentClassificationIntegrationTest*'`
Expected: FAIL — `IntentClassification` does not exist (compilation error).

- [ ] **Step 3: Add the changeset**

```yaml
databaseChangeLog:
  - changeSet:
      id: 034-intent-classification
      author: komora
      comment: >-
        Task 55: one row per intent classification, including the ones the model could not make. The
        pitch artifact's intent distribution counts these rows; nothing else reads the table.
      changes:
        - createTable:
            tableName: intent_classification
            columns:
              - column: {name: id, type: uuid, constraints: {primaryKey: true, nullable: false}}
              - column: {name: intent, type: varchar(64), constraints: {nullable: false}}
              - column: {name: outcome, type: varchar(16), constraints: {nullable: false}}
              - column: {name: confidence, type: double precision}
              - column: {name: user_id, type: uuid}
              - column: {name: classified_at, type: timestamptz, constraints: {nullable: false}}
        - createIndex:
            tableName: intent_classification
            indexName: idx_intent_classification_intent
            columns:
              - column: {name: intent}
```

- [ ] **Step 4: Add the entity, repository, event and listener**

`IntentClassification` mirrors `McpToolCall` field for field in style (`@Entity @Table @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor`, `@Id` UUID assigned by the caller).

```java
/**
 * One classification by {@code IntentRouterService} (task 55). The evidence behind "the router really does
 * dispatch across the whole taxonomy" — a count over this table, not a claim on a slide.
 *
 * <p>Failures are rows too: a distribution that shows only successes invites the question it should answer.
 */
@Entity
@Table(name = "intent_classification")
```

`IntentLogService` is a direct copy of `McpCallLogService`'s shape:

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentLogService {

    public static final String ROUTED = "ROUTED";
    public static final String UNCLASSIFIED = "UNCLASSIFIED";
    public static final String FAILED = "FAILED";

    private final IntentClassificationRepository intentClassificationRepository;

    @EventListener
    public void onIntentClassified(IntentClassifiedEvent event) {
        try {
            intentClassificationRepository.save(IntentClassification.builder()
                    .id(UUID.randomUUID())
                    .intent(event.intent())
                    .outcome(event.outcome())
                    .confidence(event.confidence())
                    .userId(event.userId())
                    .classifiedAt(event.classifiedAt())
                    .build());
        } catch (RuntimeException e) {
            log.warn("could not log intent classification {}: {}", event.intent(), e.getMessage());
        }
    }
}
```

- [ ] **Step 5: Publish the event from the router**

`IntentRouterService` gains an `ApplicationEventPublisher` constructor parameter (it has an explicit constructor already — add the field and the assignment). At each of the three existing sites:

```java
// classification threw
observabilityService.recordIntent("failed");
publisher.publishEvent(new IntentClassifiedEvent(
        IntentLogService.FAILED, IntentLogService.FAILED, null, user.getId(), receivedAt));
return false;
...
// unknown or below the threshold
observabilityService.recordIntent("unclassified");
publisher.publishEvent(new IntentClassifiedEvent(
        IntentLogService.UNCLASSIFIED, IntentLogService.UNCLASSIFIED, classified.confidence(), user.getId(), receivedAt));
return false;
...
// routed — after the unlessIntents check, so a rejection is not counted
observabilityService.recordIntent("routed");
publisher.publishEvent(new IntentClassifiedEvent(
        intent.name(), IntentLogService.ROUTED, classified.confidence(), user.getId(), receivedAt));
dispatch(user, text, classified, intent, receivedAt);
```

- [ ] **Step 6: Run the test and the architecture suite**

Run: `./gradlew test --tests '*IntentClassificationIntegrationTest*' --tests '*ArchitectureTest*'`
Expected: PASS.

- [ ] **Step 7: Format and commit**

```bash
make format
git add src/main/resources/db/changelog/changes/034-intent-classification.yaml \
        src/main/java/com/silporestockai/entity/IntentClassification.java \
        src/main/java/com/silporestockai/repository/IntentClassificationRepository.java \
        src/main/java/com/silporestockai/model/IntentClassifiedEvent.java \
        src/main/java/com/silporestockai/service/IntentLogService.java \
        src/main/java/com/silporestockai/service/IntentRouterService.java \
        src/test/java/com/silporestockai/integration/IntentClassificationIntegrationTest.java
git commit -m "Record which intent fired, not just that one did"
```

---

### Task 2: `PitchArtifactService` — the page itself

**Files:**
- Create: `src/main/java/com/silporestockai/service/PitchArtifactService.java`
- Test: `src/test/java/com/silporestockai/unit/PitchArtifactServiceTest.java`

**Interfaces:**
- Consumes: `IntentClassification` and `IntentClassificationRepository` from Task 1; `McpToolCall`, `McpToolCallRepository` and `MetricsService.SILPO_MCP_TOOL_COUNT` from task 37.
- Produces: `PitchArtifactService.html()` (reads both repositories, uses the injected `Clock`) and `static String render(List<McpToolCall> calls, List<IntentClassification> intents, Instant generatedAt)`; `static final Map<String, String> FLOW_NOTES`.

- [ ] **Step 1: Write the failing unit test**

```java
package com.silporestockai.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.entity.IntentClassification;
import com.silporestockai.entity.McpToolCall;
import com.silporestockai.service.PitchArtifactService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Task 55: the artifact renders what the tables hold, and nothing it was not given. */
class PitchArtifactServiceTest {

    private static final Instant AT = Instant.parse("2026-09-10T12:00:00Z");
    private static final UUID SOMEONE = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static McpToolCall call(String tool, boolean error) {
        return McpToolCall.builder()
                .id(UUID.randomUUID())
                .toolName(tool)
                .userId(SOMEONE)
                .error(error)
                .calledAt(AT)
                .build();
    }

    private static IntentClassification intent(String name, String outcome) {
        return IntentClassification.builder()
                .id(UUID.randomUUID())
                .intent(name)
                .outcome(outcome)
                .confidence(0.9)
                .userId(SOMEONE)
                .classifiedAt(AT)
                .build();
    }

    @Test
    void headlineCountsDistinctToolsAgainstTheServersForty() {
        String html = PitchArtifactService.render(
                List.of(call("silpo_find_products_batch", false), call("silpo_find_products_batch", false),
                        call("silpo_create_shopping_cart", false)),
                List.of(),
                AT);
        assertThat(html).contains("2").contains("40").contains("3");
    }

    @Test
    void toolsAreOrderedByCallCountAndFailuresAreCounted() {
        String html = PitchArtifactService.render(
                List.of(call("silpo_get_time_slots", true), call("silpo_find_products_batch", false),
                        call("silpo_find_products_batch", false)),
                List.of(),
                AT);
        assertThat(html.indexOf("silpo_find_products_batch")).isLessThan(html.indexOf("silpo_get_time_slots"));
    }

    @Test
    void anUnknownToolRendersWithoutAnInventedNote() {
        String html = PitchArtifactService.render(List.of(call("silpo_something_new", false)), List.of(), AT);
        assertThat(html).contains("silpo_something_new");
        assertThat(PitchArtifactService.FLOW_NOTES).doesNotContainKey("silpo_something_new");
    }

    @Test
    void intentDistributionCountsEveryOutcome() {
        String html = PitchArtifactService.render(
                List.of(),
                List.of(intent("HANGOVER_RELIEF", "ROUTED"), intent("HANGOVER_RELIEF", "ROUTED"),
                        intent("UNCLASSIFIED", "UNCLASSIFIED")),
                AT);
        assertThat(html).contains("HANGOVER_RELIEF");
        assertThat(html).contains("UNCLASSIFIED");
    }

    @Test
    void thePageNeverCarriesAUserIdentifier() {
        String html = PitchArtifactService.render(
                List.of(call("silpo_find_products_batch", false)), List.of(intent("HELP", "ROUTED")), AT);
        assertThat(html).doesNotContain(SOMEONE.toString());
    }

    @Test
    void everyFlowNoteNamesAToolTheCodebaseActuallyCalls() throws Exception {
        String sources = java.nio.file.Files.walk(java.nio.file.Path.of("src/main/java"))
                .filter(p -> p.toString().endsWith(".java"))
                .map(p -> {
                    try {
                        return java.nio.file.Files.readString(p);
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                })
                .collect(java.util.stream.Collectors.joining("\n"));
        assertThat(PitchArtifactService.FLOW_NOTES.keySet()).allSatisfy(tool -> assertThat(sources)
                .as("flow note for a tool nothing calls: %s", tool)
                .contains('"' + tool));
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests '*PitchArtifactServiceTest*'`
Expected: FAIL — `PitchArtifactService` does not exist.

- [ ] **Step 3: Write the service**

Shape:

```java
@Service
@RequiredArgsConstructor
public class PitchArtifactService {

    /** Which flow reaches for each tool. The one hand-written part of the page — knowledge, not data. */
    public static final Map<String, String> FLOW_NOTES = Map.ofEntries(...);

    private final McpToolCallRepository mcpToolCallRepository;
    private final IntentClassificationRepository intentClassificationRepository;
    private final Clock clock;

    public String html() {
        return render(mcpToolCallRepository.findAll(), intentClassificationRepository.findAll(), clock.instant());
    }

    public static String render(List<McpToolCall> calls, List<IntentClassification> intents, Instant generatedAt) { ... }
}
```

Author `FLOW_NOTES` from what the code actually names — every `TOOL_*` constant plus the two inline names:
`silpo_find_products_batch`, `silpo_create_shopping_cart`, `silpo_get_my_shopping_cart`,
`silpo_get_shopping_cart_by_id`, `silpo_update_shopping_cart`, `silpo_add_or_update_cart_products`,
`silpo_remove_cart_products`, `silpo_clear_shopping_cart`, `silpo_get_available_delivery_types`,
`silpo_get_my_delivery_addresses`, `silpo_get_time_slots`, `silpo_list_branches`, `silpo_get_replacements`,
`silpo_get_my_favorites`, `silpo_get_my_online_orders`, `silpo_get_my_offline_orders`,
`silpo_get_loyalty_info`, `silpo_get_my_certificates`, `silpo_add_or_update_certificates`,
`silpo_get_my_coupons`, `silpo_get_coupon_details`, `silpo_get_my_promos`, `silpo_get_promo_codes`,
`silpo_get_my_premium_subscription`. Each note is one Ukrainian line naming the flow and the task, e.g.
`silpo_get_replacements` → «Дельта-дозамовлення (#14): чим замінити позицію, якої нема в наявності».

Intent glosses: a second `Map<String, String>` keyed by `IntentType` name, one Ukrainian example sentence
each, so a judge reads what a person types rather than an enum. An intent without a gloss renders blank.

HTML escaping: everything interpolated from the database goes through a private `escape(String)` — the tool
names come from a remote server, and a page that trusts them is a stored-XSS hole on a public URL.

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --tests '*PitchArtifactServiceTest*'`
Expected: PASS.

- [ ] **Step 5: Format and commit**

```bash
make format
git add src/main/java/com/silporestockai/service/PitchArtifactService.java \
        src/test/java/com/silporestockai/unit/PitchArtifactServiceTest.java
git commit -m "Render the call sheet a jury can read in one sitting"
```

---

### Task 3: Serving it — the endpoint and the make target

**Files:**
- Modify: `src/main/java/com/silporestockai/controller/InternalMetricsController.java`
- Modify: `Makefile` (new `pitch-artifact` target, added to `.PHONY`)
- Modify: `src/test/java/com/silporestockai/unit/InternalMetricsControllerTest.java`
- Test: `src/test/java/com/silporestockai/integration/InternalMetricsIntegrationTest.java` (one added case)

**Interfaces:**
- Consumes: `PitchArtifactService.html()` from Task 2.
- Produces: `GET /internal/metrics/pitch-artifact` returning `text/html;charset=UTF-8`; `make pitch-artifact` writing `src/main/resources/static/pitch.html`.

- [ ] **Step 1: Write the failing controller tests**

Add to `InternalMetricsControllerTest` the same three cases the existing `/pitch` has — 404 when metrics are
disabled, 403 without the token, 200 with it — against `pitchArtifact(...)`.

- [ ] **Step 2: Run and watch it fail**

Run: `./gradlew test --tests '*InternalMetricsControllerTest*'`
Expected: FAIL — no such method.

- [ ] **Step 3: Add the endpoint**

```java
@GetMapping(value = "/pitch-artifact", produces = "text/html;charset=UTF-8")
public ResponseEntity<String> pitchArtifact(@RequestHeader(value = TOKEN_HEADER, required = false) String token) {
    if (!properties.enabled()) {
        return ResponseEntity.notFound().build();
    }
    if (token == null || !constantTimeEquals(properties.token(), token)) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }
    return ResponseEntity.ok(pitchArtifactService.html());
}
```

Update the class javadoc: it now serves two reports, one markdown for the notes and one HTML that becomes a
committed static page.

- [ ] **Step 4: Add the make target**

```make
pitch-artifact: ## Regenerate the public pitch page from the running app's own data (needs METRICS_TOKEN in .env)
	@set -a; . ./$(ENV_FILE); set +a; \
	curl -sf -H "X-Metrics-Token: $$METRICS_TOKEN" \
	  "http://localhost:$${SERVER_PORT:-8080}/internal/metrics/pitch-artifact" \
	  -o src/main/resources/static/pitch.html \
	&& echo "wrote src/main/resources/static/pitch.html — commit it to publish" \
	|| echo "no page: is the app running, and is METRICS_TOKEN set in .env?"
```

Add `pitch-artifact` to the `.PHONY` list.

- [ ] **Step 5: Run the tests**

Run: `./gradlew test --tests '*InternalMetricsControllerTest*' --tests '*InternalMetricsIntegrationTest*'`
Expected: PASS.

- [ ] **Step 6: Format and commit**

```bash
make format
git add src/main/java/com/silporestockai/controller/InternalMetricsController.java Makefile \
        src/test/java/com/silporestockai/unit/InternalMetricsControllerTest.java \
        src/test/java/com/silporestockai/integration/InternalMetricsIntegrationTest.java
git commit -m "Serve the call sheet behind the metrics token"
```

---

### Task 4: The real run, the snapshot, and the docs that point at it

**Files:**
- Create: `src/main/resources/static/pitch.html` (generated, committed)
- Modify: `docs/RUNBOOK.md` (a `### Task 55:` section)
- Modify: Notion «Selling Points та Пітч-аргументи» and «Сценарій демо-запису» (the artifact and its URL)

- [ ] **Step 1: Start the app against live Silpo MCP**

`make run` with the real `.env`. Confirm `/actuator/health` is UP and `mcp_oauth_token` holds a live token.

- [ ] **Step 2: Drive every flow that reaches MCP**

Synthetic webhook POSTs (see `docs/RUNBOOK.md` and the live-driving notes): onboarding → weekly plan → list →
cart build → delivery configuration → checkout → check-in → delta reorder → hangover relief (#72's cheap
staples) → blackout (#73's no-fridge cart) → dish ingredients → past-order seed → order status → benefits
overview (#78 certificates, #79 promo codes) → calendar view → list modify → UA-producer filter → help.
Pace the sends with `python3 -c "import time; time.sleep(N)"` — `sleep` is a no-op in this sandbox and firing
them at once trips the Claude circuit breaker. Reset `conversation_state.current_flow` to `NONE` between
steps.

- [ ] **Step 3: Check the coverage before generating**

```sql
SELECT tool_name, count(*), count(*) FILTER (WHERE is_error) FROM mcp_tool_call GROUP BY 1 ORDER BY 2 DESC;
SELECT intent, outcome, count(*) FROM intent_classification GROUP BY 1, 2 ORDER BY 3 DESC;
```

A tool the code names but the run never called means a flow was missed — go back to step 2 rather than
publishing an understated number.

- [ ] **Step 4: Generate and eyeball the page**

```bash
make pitch-artifact
```

Open `src/main/resources/static/pitch.html` in a browser. Check: the headline count matches the SQL, no user
identifier appears anywhere (`grep -c '[0-9a-f]\{8\}-[0-9a-f]\{4\}' src/main/resources/static/pitch.html`
should be 0), every tool row has a note.

- [ ] **Step 5: Stop the app, run the full suite**

```bash
./scripts/stop-app.sh && make test
```

- [ ] **Step 6: Document and commit**

Add a `### Task 55:` section to `docs/RUNBOOK.md`: how to regenerate (run the app, drive the flows, `make
pitch-artifact`, commit, push — Watchtower deploys), and the public URL. Then:

```bash
make format
git add src/main/resources/static/pitch.html docs/RUNBOOK.md docs/superpowers/
git commit -m "Publish the MCP call sheet as a static page"
```
