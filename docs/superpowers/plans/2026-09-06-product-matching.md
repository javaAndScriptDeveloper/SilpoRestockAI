# Choosing the right product from Silpo's search results

## The problem, from a live cart

`CartBuildingService.resolveProducts` took `products[0]` of each `silpo_find_products_batch` query entry.
Silpo's own ranking is not "the most ordinary version of this thing first", so a plain weekly list came
back as:

| the list said | the cart got | price |
|---|---|---|
| Яловичина 850 г | Яловичина обJerky «Техаська» **в'ялена**, 34 packets of 25 g | ₴3246 |
| Рис 450 г | Рис Tartufi Jimmy **з чорним трюфелем** | ₴949 |
| Спагеті 500 г | Спагеті Yumart **Shirataki** (konjac, not pasta) | ₴63 |
| Пластівці вівсяні 300 г | Пластівці вівсяні Mornflake Stoneground 750 г | ₴399 |
| Банан 1 шт | **Банан чіпси смажені** | ₴149 |

The full candidate lists say why no keyword rule can fix this. «Яловичина» returns 26 candidates whose
top three are jerky snacks and whose tail includes **cat food** («Корм для котів Felix … яловичина») and
**dog treats**. «Спагеті» returns 19, of which index 4 is a **spoon for spaghetti**. «Банан» returns 30 —
chips, dried, purées, a liqueur, and two **anti-stress toys** — and **not one fresh banana**, which is the
important case: sometimes the right product is simply not there, and the honest answer is "не знайшов".

A stop-word list ("сушен", "чіпси", "в'ялен", "іграшка", "корм", …) is unbounded and would still not know
that Shirataki is konjac rather than pasta.

## Approach

Let the model choose among the candidates Silpo really returned — the pattern task 22 already proved for
ready meals (`ReadyMealCatalogService` finds real candidates → Claude curates → the choice is matched back
by exact identity, never by an id the model typed).

- **One call per cart**, not per line: every line's candidates go in a single structured request.
- **The model answers with an index**, never a product id. An index outside the candidate list is rejected
  the same way a fabricated `productId` is — the model may only pick from what Silpo sent.
- **"None of these" is a valid answer** (`-1`). That is what turns «Банан» from silently ordering banana
  chips into the unresolved line the product already reports honestly («Не знайшов: …»).
- Candidates carry name, price, `displayRatio`, `weighted` and `stock`, so the model can avoid a candidate
  the branch has almost none of — the other source of the `product.offer.stock.max` refusals.

## What stays deterministic

- `available: false` candidates are dropped before the model ever sees them.
- Partner placements (task 46) keep precedence and their own live verification; matching never runs for a
  line a placement already answered.
- Pre-resolved lines (a real UUID `silpoProductId`) skip search and matching entirely, as now.

## Failure behaviour

No silent success. Two distinct cases, deliberately logged differently:

- **No `ANTHROPIC_API_KEY`** — a supported configuration. Falls back to Silpo's own ranking (today's
  behaviour) and says so at INFO once per cart.
- **Configured but the call failed** — falls back the same way, at ERROR with the exception. The cart is
  still real; it is just matched no better than before. Making the whole cart unresolved would be worse
  for the household than a badly matched one, and the log is where this is visible.

## Not doing

- **Re-querying with a better search term.** «Банан» not returning a fresh banana in its top 30 is a query
  problem, not a ranking one; fixing it means a second search round-trip and a way to generate a better
  term. Out of scope here — this change makes that case *honest* rather than wrong, which is the
  prerequisite for measuring how often it happens.
- **Price ceilings or "cheapest wins".** ₴949 truffle rice is wrong because it is truffle rice, not because
  it is expensive; «Сир пармезан» genuinely costs more than «Молоко». Price is one input to the choice,
  not a rule.
