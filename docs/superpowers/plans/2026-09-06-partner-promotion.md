# Partner promotion: paid featured placement + analytics (task 46) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A partner pays for a specific real product to be the match whenever any flow resolves a category the household already asked for; every impression, cart-add and confirmed order is logged so the partner sees conversion. Never a product nobody asked for; never over an allergy.

**Architecture:** Two tables of their own (`partner_promotion`, `partner_promotion_event`, changeset 027). `PartnerPromotionService` answers «is there an active, restriction-safe promotion for this list line» and records events. The one integration point is `CartBuildingService.resolveProducts` — every flow (weekly plan, ad-hoc, hangover, blackout, dish ingredients, past-order fallback lines) resolves through it. Live verification costs no extra call: the promoted product's catalog name is added as one more term to the same `silpo_find_products_batch` batch, and the promotion is used only if Silpo returns that exact product id for this branch and slot now; otherwise the ordinary first match wins. `addProductsToCart` logs ADDED_TO_CART for promotion-resolved lines; an `OrderConfirmedEvent` listener logs CONFIRMED_ORDER. The cart message marks partner lines with ★ and one honest footer. Admin: `POST /internal/promotions` (MCP-verified through a connected user) and `GET /internal/promotions/report`, both behind the task-37 token.

**Tech Stack:** Liquibase 027, JPA, Spring events, Spring MVC, MockMvc + MCP stub.

**Spec:** Notion task 46 (`3d37227d-ef1c-814f-a78d-c46f9655e36b`).

## Global Constraints

- Restrictions first: a promotion is skipped before matching if its product name or category hits a keyword from the household's restrictions, dislikes or diet type (keyword lists in the service; documented as heuristic).
- One active promotion per category (highest `priority_weight` wins if two overlap).
- No new MCP calls: verification rides on the existing batch search.
- Disclosure: ★ on the line and a one-line footer — honest, not a banner.

---

### Task 1: Schema, entities, repositories, models
`027-partner-promotion.yaml`; `entity/PartnerPromotion`, `entity/PartnerPromotionEvent`; `model/PartnerPromotionStatus`, `model/PartnerPromotionEventType`; `repository/PartnerPromotionRepository`, `repository/PartnerPromotionEventRepository`; `model/ResolvedProduct` (+`promotionId`, old 6-arg ctor kept); `model/CartSummary` (+`promotedProductIds`, old 11-arg ctor kept).

### Task 2: Service and resolution
`service/PartnerPromotionService` (`activePromotions`, `match(promos, lineName, profile)`, `recordImpression`, `recordAddedToCart`, `@EventListener onOrderConfirmed`, `report()`); `service/CartBuildingService` (`resolveProducts` prefers the live-verified promoted product; `addProductsToCart` logs; `getVerifiedCart` overload with promoted ids); `service/telegram/CartMessageService` (★ + footer).

### Task 3: Admin
`service/PartnerPromotionAdminService` (verify via `silpo_find_products_batch` as a connected user, then create); `controller/InternalPromotionsController` (POST create, GET report); `dto/request/PartnerPromotionRequest`, `dto/response/PartnerPromotionResponse`.

### Task 4: Tests, docs, Notion
`integration/PartnerPromotionIntegrationTest`: preferred + IMPRESSION + ADDED_TO_CART + ★; fallback when the SKU is not returned live; lactose restriction blocks a milk promotion (and the partner term is not even searched); no promotion for a category not on the list; CONFIRMED_ORDER on the event; report markdown; POST creates a verified row. RUNBOOK «Task 46», `OVERNIGHT_QUESTIONS.md`, Notion → In review, demo step 13.5 → In review.
