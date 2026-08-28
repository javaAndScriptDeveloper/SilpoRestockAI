# Liquibase schema and JPA entities for the core domain

Notion task `05. Liquibase schema + JPA entities for core domain` (Phase `1. DB and Domain`, Must have,
size L, depends on `01`).

## Context

Everything from task 06 onward needs persistence. Liquibase owns the schema and Hibernate runs with
`ddl-auto: validate`, so each table arrives as a changeset. Defining the whole MVP schema in one pass keeps
later tasks from reshuffling tables underneath each other.

Tasks 02 and 03 shipped two tables ahead of this one, by agreement: `mcp_oauth_token` (`001`) and
`conversation_state` (`002`). This change adds the remaining nine and retrofits the one foreign key that was
waiting on `users`.

## Decisions

### `order` becomes `customer_order`

`ORDER` is reserved in PostgreSQL. Keeping the name in the task text would mean a quoted identifier in the
changeset, in `@Table`, and in every hand-written query anyone adds later. The entity is `CustomerOrder` and
the repository `CustomerOrderRepository`.

### Only `mcp_oauth_token` gets a foreign key to `users`

`conversation_state` stays keyed by `telegram_chat_id` with no foreign key. A conversation exists before
onboarding creates the user row — the very first message a stranger sends must be storable — so a foreign
key there would break task 06's own flow.

### jsonb through Hibernate's native mapping

`@JdbcTypeCode(SqlTypes.JSON)` over a typed field, the same mechanism `ConversationState` already uses. The
task suggests `@Convert` with an `AttributeConverter`; that predates Hibernate 7's native support, and one
mechanism in the codebase beats two.

### JSON payloads are typed where a dependent task already pinned the shape

- `model/BasketItem(String silpoProductId, String name, String unit, BigDecimal quantity, BigDecimal price)`
  — `baseline_basket.items_json` and `customer_order.items_json`.
- `model/CheckinDelta(List<String> stillHave, List<String> runningLow, List<String> goneCompletely)` —
  `checkin.parsed_delta_json`, exactly the shape task 12 specifies it will produce.

`meal_plan.plan_json` stays `Map<String, Object>`: task 07 owns that shape and has not defined it. Typing it
now would be invention.

### One current baseline per user is a database constraint

`008` adds a partial unique index, `CREATE UNIQUE INDEX ux_baseline_basket_current ON baseline_basket
(user_id) WHERE is_current`. Task 10's confirm flow and its double-tap idempotency requirement both depend on
that invariant; enforcing it in application code alone would leave a race.

## Schema

| Changeset | Table | Columns |
|---|---|---|
| `003-users` | `users` | `id` UUID PK, `telegram_chat_id` BIGINT UNIQUE, `silpo_guest_id` VARCHAR(255), `created_at` |
| `004-mcp-oauth-token-user-fk` | — | FK `mcp_oauth_token.user_id` → `users.id`, `ON DELETE CASCADE` |
| `005-user-profile` | `user_profile` | `id`, `user_id` FK UNIQUE, `household_size`, `has_kids`, `kids_ages` jsonb, `dietary_restrictions` jsonb, `weekly_budget` NUMERIC(10,2), `disliked_foods` jsonb, `only_ua_producer` BOOLEAN default false, `special_mode` VARCHAR(64), `special_mode_started_at` |
| `006-meal-plan` | `meal_plan` | `id`, `user_id` FK, `week_start_date` DATE, `plan_json` jsonb, `created_at` |
| `007-shopping-list-item` | `shopping_list_item` | `id`, `meal_plan_id` FK nullable, `name`, `quantity` NUMERIC(10,3), `unit`, `category` |
| `008-baseline-basket` | `baseline_basket` | `id`, `user_id` FK, `items_json` jsonb, `confirmed_at`, `is_current` BOOLEAN + partial unique index |
| `009-checkin` | `checkin` | `id`, `user_id` FK, `raw_input_text` TEXT, `parsed_delta_json` jsonb, `received_at` |
| `010-inventory-trend` | `inventory_trend` | `id`, `user_id` FK, `item_name`, `consecutive_untouched_cycles` INT default 0, `last_updated`, unique `(user_id, item_name)` |
| `011-customer-order` | `customer_order` | `id`, `user_id` FK, `type`, `items_json` jsonb, `delivery_slot`, `status`, `silpo_cart_id`, `created_at`, `confirmed_at` |
| `012-trust-level` | `trust_level` | `id`, `user_id` FK UNIQUE, `consecutive_unedited_confirmations` INT default 0, `current_trust_tier` |

`shopping_list_item.meal_plan_id` is nullable on purpose: the same table serves ad-hoc lists that belong to
no weekly plan.

`inventory_trend` gains a unique constraint on `(user_id, item_name)` that the task draft does not mention —
task 13 updates a row per item per check-in, and without it a retry would silently create duplicates.

## Entities and enums

New entities in `entity`: `User`, `UserProfile`, `MealPlan`, `ShoppingListItem`, `BaselineBasket`, `Checkin`,
`InventoryTrend`, `CustomerOrder`, `TrustLevel`. All follow the existing `SilpoOAuthToken` /
`ConversationState` shape: Lombok `@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor`, explicit
`@Column(name = ...)`, `Instant` timestamps.

Foreign keys are mapped as plain `UUID userId` columns rather than `@ManyToOne` associations. Every consumer
identified in tasks 06 to 16 looks rows up by user id; associations would add lazy-loading and cascade
semantics nobody asked for.

New enums in `model`: `SpecialMode` (`NONE`, `MEDICAL_GASTRITIS_ACUTE`, `MEDICAL_DIET_TABLE_5`, `MASS_GAIN`,
`BLACKOUT`), `OrderType` (`INITIAL`, `SCHEDULED_REORDER`, `AD_HOC`), `OrderStatus` (`DRAFT`, `CONFIRMED`,
`CANCELLED`), `TrustTier` (`MANUAL_CONFIRM`, `FAST_CONFIRM`). The auto-confirm tier is deliberately absent —
the product brief says leave room, not build it.

## Repositories

Method sets derived from the dependent tasks, not over-provisioned blindly.

| Repository | Methods | Wanted by |
|---|---|---|
| `UserRepository` | `findByTelegramChatId(long)`, `findBySilpoGuestId(String)` | 06 |
| `UserProfileRepository` | `findByUserId(UUID)` | 06, 07 |
| `MealPlanRepository` | `findByUserIdAndWeekStartDate(UUID, LocalDate)`, `findFirstByUserIdOrderByWeekStartDateDesc(UUID)` | 07 |
| `ShoppingListItemRepository` | `findByMealPlanId(UUID)`, `deleteByMealPlanId(UUID)` | 08 |
| `BaselineBasketRepository` | `findByUserIdAndIsCurrentTrue(UUID)`, `findByUserIdOrderByConfirmedAtDesc(UUID)` | 10, 12, 15 |
| `CheckinRepository` | `findFirstByUserIdOrderByReceivedAtDesc(UUID)`, `findTop2ByUserIdOrderByReceivedAtDesc(UUID)`, `findByUserIdOrderByReceivedAtDesc(UUID)` | 12, 13 — 13 compares against the previous check-in to detect an untouched streak |
| `InventoryTrendRepository` | `findByUserId(UUID)`, `findByUserIdAndItemName(UUID, String)`, `findByUserIdAndConsecutiveUntouchedCyclesGreaterThanEqual(UUID, int)` | 13's `getRemovalCandidates` with a configurable threshold |
| `CustomerOrderRepository` | `findByUserIdOrderByCreatedAtDesc(UUID)`, `findByUserIdAndStatus(UUID, OrderStatus)`, `findBySilpoCartId(String)` | 10, 14, 15 |
| `TrustLevelRepository` | `findByUserId(UUID)` | 16 |

## Testing

- `SchemaRoundTripIntegrationTest` — persist and read back every new entity. `ddl-auto: validate` already
  catches a column mismatch; only a round trip catches a broken jsonb mapping, which is the real risk here.
- `RepositoryQueriesIntegrationTest` — exercises each derived query. `findTop2ByUserIdOrderByReceivedAtDesc`
  and `findByUserIdAndConsecutiveUntouchedCyclesGreaterThanEqual` are the two easiest to get silently wrong.
- `BaselineBasketConstraintIntegrationTest` — a second `is_current = true` row for the same user must be
  rejected by the database.
- `DomainFixturesTest` — Instancio fixtures for `UserProfile` and `CustomerOrder`, in the style of
  `InstancioExampleTest`, as the pattern later tasks copy.

## Note for task 06

Task 06 refers to a conversation state called `IDLE`. The `ConversationFlow` enum shipped in task 03 calls
that value `NONE`. Task 06 should use `NONE` rather than adding a second name for the same state.

## Out of scope

No business logic and no services reading or writing these tables — that is every subsequent task. Schema,
entities and repositories only. `meal_plan.plan_json` structure belongs to task 07.
