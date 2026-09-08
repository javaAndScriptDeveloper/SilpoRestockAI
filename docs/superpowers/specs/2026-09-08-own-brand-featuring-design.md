# Own-brand featuring and share-of-category metrics — design

Task 63 in *Комора — Development Plan*. Depends on task 46 (paid featured placement), which is Done.

## Why

Task 46 sells a partner raw counts: "your product was featured 4 times, added 4 times, confirmed once."
That is a weak number. It does not say whether four is a lot, and it gives Silpo nothing to reason
about internally. The number that justifies a price is **share of category**: "your brand answered 80 %
of the tea lines our households asked for, against a natural share nearer a third."

The same mechanism has a second, entirely internal use. Silpo can prefer its own private-label or
high-margin brands exactly the way a paying partner is preferred, with no external payment at all.
That is a different value pool — internal margin, not external revenue — and the two must never be
added together into one number for the business side.

So this change does two things: it splits the promotion table by intent, and it replaces raw counts
with share, baseline and lift.

## Scope

In: `promotion_type`, a resolution log that gives share a real denominator, an FSR/baseline/lift
report split by type, and a live own-brand placement for the demo.

Out: the Grafana panel (task 64), and any A/B infrastructure for a statistically rigorous baseline.
The baseline here is an approximation wherever it says it is one.

## Data model

### `partner_promotion.promotion_type`

Changeset `029-promotion-type.yaml` adds one column:

| column | type | notes |
|---|---|---|
| `promotion_type` | `VARCHAR(32)` NOT NULL, default `PAID_PARTNER` | `PAID_PARTNER` or `OWN_BRAND_MARGIN_BOOST` |

The default migrates every task-46 row as paid, which is what those rows are — no backfill script, no
guessing. For an `OWN_BRAND_MARGIN_BOOST` row, `partner_name` holds an internal label («Сільпо власна
марка», or the specific brand being pushed) rather than an external payer.

New enum `model.PromotionType`, mapped `@Enumerated(EnumType.STRING)` like `PartnerPromotionStatus`.

### `category_resolution_log`

Changeset `030-category-resolution-log.yaml`. One row per resolved shopping-list line, promoted or not.
Without the not-promoted rows there is no denominator, and therefore no share — only the raw counts
task 46 already prints.

| column | type | notes |
|---|---|---|
| `id` | `UUID` PK | |
| `user_id` | `UUID` NOT NULL | whose cart the line came from |
| `line_name` | `VARCHAR(256)` NOT NULL | the line as the household asked it, e.g. «Молоко» |
| `resolved_product_id` | `VARCHAR(64)` NOT NULL | what the catalog answered |
| `resolved_product_name` | `VARCHAR(256)` | the catalog's own name |
| `promotion_id` | `UUID` NULL | the placement that answered, or null for an ordinary match |
| `candidate_count` | `INTEGER` NULL | how many plausible candidates the catalog returned for the line |
| `occurred_at` | `TIMESTAMP WITH TIME ZONE` NOT NULL | |

Indexes on `occurred_at` and `promotion_id`.

`promotion_id` is a foreign key with **ON DELETE SET NULL**, deliberately not the `deleteCascade` that
`partner_promotion_event` uses. An event exists to describe a campaign and dies with it; a resolution
is a historical fact about what a household got, and deleting a campaign must never shrink the
denominator of every other brand's share.

No brand column. Attribution is by product id, which is exact. Parsing a brand out of «Молоко
«Яготинське» 2,6% п/е» is a guess, and a guess in the numerator would quietly corrupt every share.

## Write path

`CartBuildingService.resolveProducts` is the single integration point task 46 already established:
every flow — weekly plan, ad-hoc, hangover, blackout, dish ingredients, past-order fallback — resolves
its lines there. Logging there means all of them are counted, with no flow-specific code.

The write happens once, after `secondPass`, over the final `resolved` list. `ResolvedProduct` already
carries `requestedName`, `productId`, `catalogName` and `promotionId`; the only value it lacks is
`candidate_count`, which is collected during first-pass matching into an
`IdentityHashMap<ShoppingListItem, Integer>` and left null for lines the second pass rescued — an
honest null rather than a fabricated count.

A new `CategoryResolutionLogService` owns the write, and the whole call is wrapped in a try/catch that
logs a warning. Evidence for a report must never break a cart that is otherwise fine — the same rule
`PartnerPromotionService.record` follows.

Volume is roughly one row per list line, about twenty per cart build. Nothing to manage.

## Metrics

A new `PromotionMetricsService` computes everything and owns the report text. `report()` moves out of
`PartnerPromotionService`, which goes back to being about matching and events only.

### Category matching

A promotion's denominator is the set of log rows whose `line_name` matches its `category_or_query`
using **the same** word matcher `PartnerPromotionService.match` uses. The matcher is extracted to one
shared static so the two cannot drift: if the report counted a category differently from the way the
cart decided it, every share would be wrong in a way nobody could see.

### The numbers

- **Featured Share Rate (FSR)** — rows with `promotion_id = P` over all rows in P's category. This is
  the flagship number for both a paying partner and an own-brand review.
- **Organic baseline share**, always tagged with how it was obtained:
  - `MEASURED` — among the category's organic rows (`promotion_id IS NULL`), the fraction whose
    `resolved_product_id` is the promoted product. This is a real observation of how often the ordinary
    matcher picks that product on its own, available whenever the placement was paused, expired, or
    simply not returned live. Used only when there are at least **5** organic rows; below that the
    number is noise wearing a decimal point.
  - `APPROXIMATED` — `1 / avg(candidate_count)` over the category's rows that have one (rows with a
    null count are left out of the average, not counted as zero): one brand's naive share of
    the candidates the catalog offered. `candidate_count` counts distinct returned candidates, which is
    a proxy for distinct brands, not the same thing — which is exactly why this method is labelled an
    approximation everywhere it appears.
  - `UNKNOWN` — no usable data. Prints «—», **and lift prints «—» too**. A lift computed against a
    baseline we do not have is a fabricated number, and the pitch does not need one.
- **Lift** — FSR minus baseline, in percentage points, carrying the baseline's method tag. Never
  rendered without it.
- **Confirmed-order rate** — `CONFIRMED_ORDER / ADDED_TO_CART` from task 46's existing events. The
  closest signal to an actual purchase, so it is the rate each section's totals lead with; the
  impression-to-cart rate task 46 printed is dropped from the table to keep the emphasis where the
  revenue is.
- **Category coverage** — distinct categories where a partner or brand currently holds an active
  placement.

### Honest edges

The denominator includes lines the placement could never have won: a lactose-free household's
«молоко» line counts, even though `match()` refuses the milk placement before searching. That is the
correct reading of *category share* — the brand's share of all milk resolutions, not its share of the
subset it was eligible for — and it drags FSR down rather than up. The report footnotes it so nobody
reads 60 % as a targeting failure.

## Report

`make promotions` prints two sections, never a blended total:

```
## Платні розміщення (PAID_PARTNER)
| Партнер | Категорія | Товар | Показів | У кошику | Підтверджено | Кошик→замовлення | FSR | Базлайн | Метод | Lift |

## Власні марки (OWN_BRAND_MARGIN_BOOST)
| Бренд | Категорія | Товар | Показів | У кошику | Підтверджено | Кошик→замовлення | FSR | Базлайн | Метод | Lift |
```

Method renders as «виміряно», «наближення (1/N кандидатів)» or «—». Each section carries its own
totals and a category-coverage line. External revenue and internal margin are two pools; a single
combined figure would describe neither.

## Testing

Integration tests against the Testcontainers Postgres, as `PartnerPromotionIntegrationTest` does:

1. A hand-counted scenario — five tea lines, four answered by the placement — yields FSR exactly 80 %,
   with the denominator taken from the log rather than from the event count.
2. A row inserted without `promotion_type` reads back as `PAID_PARTNER`.
3. An own-brand placement appears in the own-brand section and nowhere in the paid section, and the
   paid totals do not move when it is added.
4. The `MEASURED` baseline path: enough organic rows exist, the promoted product's organic hits are
   counted, the row is tagged «виміряно».
5. The `APPROXIMATED` path: too few organic rows, `1/avg(candidate_count)` is used, the row is tagged
   as an approximation.
6. The `UNKNOWN` path: both baseline and lift print «—».
7. Ordinary, non-promoted lines produce log rows, so the denominator exceeds the featured count.
8. A repository failure while logging leaves the built cart untouched.

## Live verification

After the suite is green, and with the app running against the real Silpo account: probe the catalog
through a connected session for a Silpo private label («Премія», «Зелена країна», «Повна чаша») in a
category the weekly list already contains, read the catalog's own `productName` back — task 46 learned
that a composed query returns 422 and a bare brand is not deterministic — then create the placement
through `POST /internal/promotions` with `promotion_type: OWN_BRAND_MARGIN_BOOST`. Walk plan → cart →
confirm, then read `make promotions` for a real FSR against a real denominator.
