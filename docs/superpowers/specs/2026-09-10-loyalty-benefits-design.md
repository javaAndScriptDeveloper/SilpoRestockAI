# Лояльність та акції at checkout — design (tasks 78 + 79)

Date: 2026-09-10. Notion tasks
[78](https://app.notion.com/3d77227def1c8132b53ee0c2ba31b7ef) and
[79](https://app.notion.com/3d77227def1c81a685bfd677f763a7b7).

## The live schema check, done before any code

Task 79 demanded the feasibility question be settled against the live server, not against
documentation prose. It was: `tools/list` was called against `https://mcp.silpo.ua/mcp` on
2026-09-10 with the household's own OAuth token (decrypted out of `mcp_oauth_token` with the
`.env` AES-GCM key — the column is ciphertext, a raw `Bearer` of it 401s).

Server `silpo-mcp-service` **1.110.0**, protocol `2025-06-18`, **40 tools** — the same count the app
logs at session open.

`silpo_update_shopping_cart`'s live input schema carries **both**:

```json
"promoCode":      { "description": "Promo code to apply",  "anyOf": [{"type":"string"},{"type":"null"}] },
"bonusRequested": { "description": "Балабонуси to apply…",  "anyOf": [{"type":"number"},{"type":"null"}] }
```

Every one of the 40 schemas was then searched for a coupon-shaped argument. The complete set of
matches:

| Tool | Loyalty-related arguments |
|---|---|
| `silpo_update_shopping_cart` | `promoCode`, `bonusRequested` |
| `silpo_add_or_update_certificates` | `certificatesToAdd[{barcode,pincode}]`, `certificatesToRemove[…]` |
| `silpo_get_coupon_details` | `businessCouponId` (read) |
| `silpo_get_my_certificates` | `limit`, `offset` (read) |
| `silpo_get_products` | `promotionCode`, `mustHavePromotion` (catalog search, not a cart mutation) |

**Verdict, from the live schema and nothing else:**

| Mechanism | Apply path exists? | What we build |
|---|---|---|
| Балабонуси | yes — `update_shopping_cart.bonusRequested` | real auto-apply (already shipped in task 24) |
| Подарункові сертифікати | yes — `add_or_update_certificates` | real auto-apply (new) |
| Промокоди | **yes — `update_shopping_cart.promoCode`** | real auto-apply (new) |
| Купони | **no.** Read-only tools only; no cart tool accepts a coupon id, barcode or promoId | informational only |
| Персональні промо (`get_my_promos`) | no activation tool exists | informational only |
| Premium «Плюхс» | no — read-only by nature | informational only |

Coupons therefore degrade honestly: they are shown, with their real terms and eligibility, and the
message says plainly that activating them happens in the Silpo app, because through MCP it cannot
happen anywhere else. No button is drawn that would do nothing.

## What the live account actually holds (2026-09-10)

Probed with real `tools/call`s, so the empty paths below are facts about this account, not guesses:

- `silpo_get_loyalty_info` → card `Постійна`, member 38484446, **balance 0**.
- `silpo_get_shopping_cart_by_id` → `loyalty: {bonusAvailable: 0, bonusTotal: 0, bonusRequested: null, isEnabled: true}`.
- `silpo_get_my_coupons` → **2 real coupons**: `-15% на покупку` (ліміт 150 грн, `active: false`,
  ends 2026-09-10) and `Безкоштовний мобільний зв'язок Yezzz!` (`active: true`, ends 2026-10-03).
- `silpo_get_promo_codes` → `{"promoCodes": [], "meta": {"total": 0}}`.
- `silpo_get_my_promos` → `{"promos": [], "meta": {"total":0, "minSelect":0, "maxSelect":0}}`.
- `silpo_get_my_certificates` → **`Error in get-my-certificates: API returned 500 Internal Server Error.`**
  — exactly the intermittent failure task 78 predicted. The graceful-degradation path is therefore the
  one this account exercises live, every time.
- `silpo_get_my_premium_subscription` → no active subscription, plus both subscribe links.
- Cart object carries `promoCode`, `certificates: []`, `calculation.certificatesTotal`.

Consequence for honesty: bonuses, certificates and promo codes all have **real** apply code, but on
this account none of the three has non-empty data to apply. What can be live-verified is the call
shape, the refusal handling and the no-offer-when-nothing-available path. The commit says so.

## Design

### One service, `LoyaltyBenefitsService`

All seven «Лояльність та акції» tools live behind one service, the way `OrderHistoryService` owns the
two history tools. Every call is wrapped so a failure degrades to «no offer» and never reaches the
cart flow — the 500 above is not hypothetical.

Two faces:

- `cartBenefits(userId)` → `CartBenefits(certificates, promoCode, coupons)` — what can be applied to
  the cart in front of the household right now, read at cart-presentation time.
- `overview(userId)` → `BenefitsOverview(…)` — everything, for the informational surface.
- `applyCertificates(userId, cartId, certificates)` and, via `CartBuildingService.applyPromoCode`,
  the two real mutations.

### Consent at cart confirmation

Silpo's own reference workflow is «ask before spending», and this is the household's own money. The
existing bonus flow already asks by offering a second confirm button; the two new mechanisms join it
rather than adding buttons of their own — three independent yes/no questions would be eight button
variants on one keyboard.

```
Зібрав кошик на тиждень:
— Молоко … — 42.90 грн
…
Разом: 912 грн
Доставка: чт · 13:30–15:00

💳 Твої вигоди:
• 250 балабонусів
• Сертифікат 500 грн (…4321)
• Промокод SUMMER10
Купон «-15% на покупку» (до 10.09) застосується сам при оформленні в «Сільпо».

[Підтвердити]  [Підтвердити + вигоди]  [Інший час]  [Скасувати]
```

`Підтвердити + вигоди` applies each available mechanism in turn, keeps what Silpo accepted, re-reads
the cart total and reports the real new amount. A mechanism Silpo refuses is named as refused; it never
fails the order. When bonuses are the only benefit, the button keeps today's wording
(`Підтвердити + 250 бонусів`) — a live-tested string is not worth churning.

### Informational surface

A new `MY_BENEFITS` intent («які в мене купони/знижки/бонуси?») answers with the overview: balance,
coupons with their real terms and eligibility (`canBeAppliedToOrder`, `progress` from
`silpo_get_coupon_details`), personal promos, promo codes, certificates and Premium status — each
labelled either «застосую сам при замовленні» or «тільки в застосунку «Сільпо»». This is also what
makes every read tool fire in a real flow, which is what task 55's artefact and task 77's matrix count.

## Testing

Unit tests with a mocked `SilpoMcpClient` for: the 500 on certificates degrading to no offer; a cart
with no benefits producing no offer and no extra button; certificates applied and refused; promo code
applied; the overview's wording for a coupon that cannot be applied through MCP. Live verification is
a manual pass driven with synthetic webhooks, logged in `docs/RUNBOOK.md`.
