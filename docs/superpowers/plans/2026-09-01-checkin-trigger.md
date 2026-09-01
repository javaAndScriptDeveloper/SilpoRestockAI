# Scheduled check-in trigger — plan

Design: [`../specs/2026-09-01-checkin-trigger-design.md`](../specs/2026-09-01-checkin-trigger-design.md).
Notion task 11.

## Step 1 — schema and entity

- `db/changelog/changes/014-users-checkin-prompt.yaml`: nullable `last_checkin_prompt_sent_at`
  (`timestamptz`) on `users`.
- `entity/User`: matching field.

## Step 2 — the lookups the sweep needs

- `UserRepository.findAllWithCurrentBaseline()` — JPQL `exists` against `BaselineBasket`.
- `CustomerOrderRepository.findFirstByUserIdAndStatusOrderByConfirmedAtDesc(UUID, OrderStatus)`.
- `CheckinRepository.findFirstByUserIdOrderByReceivedAtDesc` already exists.

## Step 3 — `config/CheckinProperties`

`@ConfigurationProperties(prefix = "komora.checkin")` record: `Duration interval`, `String sweepCron`.
Registered where the other property records are; defaults in `application.yml` under the
`${ENV_VAR:default}` idiom. `application-test.yml` gets a cron that never fires.

## Step 4 — `service.telegram.CheckinMessageService`

`promptText()` — "Як справи з їжею? Що вже закінчилось, а чого ще вистачає?" Short and direct, per the
brief's tone guidance.

## Step 5 — `service.CheckinPromptService`

- `public static final String STEP_AWAITING_REPORT = "AWAITING_REPORT"`, read by #12.
- `sweep()` — eligible users, `isDue`, `prompt`; per-user failures logged, loop continues; returns the
  number prompted so the scheduler can log it.
- `isDue(user)` — the anchor rule from the design.
- `prompt(user)` — send, set conversation state, stamp `lastCheckinPromptSentAt`.

## Step 6 — `job/CheckinScheduler`

One `@Scheduled(cron = "${komora.checkin.sweep-cron}")` method calling `sweep()`. `@EnableScheduling`
added to `config/BaseConfig` next to `@EnableAsync`.

## Step 7 — `CheckinPromptIntegrationTest`

Real Postgres, stub Telegram, `sweep()` driven directly:

1. a user whose last order confirmed four days ago is prompted, and the message is the check-in one;
2. a second sweep straight after sends nothing;
3. a user with no current baseline is never prompted;
4. a user whose order confirmed an hour ago is not prompted;
5. a recent check-in counts as contact — no prompt;
6. after the prompt, the conversation state is `CHECK_IN` / `AWAITING_REPORT` and the stamp is set;
7. a user mid-onboarding is skipped even when otherwise due;
8. an un-answered prompt older than a full interval is re-sent.

## Step 8 — close out

`make format`, `./gradlew test`, `./gradlew build`, commit, tick Notion, ff-merge into `main`.
