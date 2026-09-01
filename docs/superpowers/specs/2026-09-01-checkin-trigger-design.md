# Scheduled check-in trigger — design

Notion task **11. Scheduled check-in trigger + Telegram prompt**. Opens Stage 2: product brief user
flow #2, step 1. Parsing the answer is #12; this is "decide who to ask, and ask".

## Problem

Nothing in the application ever speaks first. Every message so far is an answer to something the user
did. The check-in cycle inverts that: roughly every three days the agent has to open the conversation
itself — but only with people who have a basket to compare against, only when enough time has passed
since the last contact, and never twice for the same window.

## Shape

| Piece | Package | Job |
|---|---|---|
| `CheckinScheduler` | `job` | one `@Scheduled` method, hourly, that calls the sweep |
| `CheckinPromptService` | `service` | who is due, and what happens when they are |
| `CheckinMessageService` | `service.telegram` | the wording of the prompt |
| `CheckinProperties` | `config` | the interval and the sweep cron |

The scheduler holds no logic. That split is what makes the sweep testable: a test calls `sweep()`
directly and never waits on a clock.

## When is someone due

One rule, three inputs:

```
anchor = max(lastCheckinPromptSentAt, lastCheckin.receivedAt, lastConfirmedOrder.confirmedAt)
due    = now - anchor >= komora.checkin.interval
```

Taking the newest of the three means every kind of contact counts as contact. A user who confirmed an
order yesterday is not asked what is left in their fridge; a user who answered a check-in yesterday is
not asked again; and a user who was *prompted* yesterday and said nothing is not nagged today — but is
asked again after another full interval, rather than being written off forever.

`lastCheckinPromptSentAt` is a new column on `users`. It could have been inferred from the conversation
state, but the state is cleared the moment the user answers, and "when did we last speak" has to
survive that.

### Who is eligible at all

A user with a `baseline_basket` where `is_current = true`. That single condition subsumes "finished
onboarding" and "has a first order": the baseline is only ever written when a cart is confirmed.

### Who is skipped even when due

Anyone mid-`ONBOARDING` or mid-`CART_CONFIRMATION`. Those flows are waiting on the user for something
else, and a fridge question landing between "what is your budget?" and the answer would derail them.
A chat already in `CHECK_IN` is *not* skipped — that is the un-answered case above, and the timestamp
already governs its cadence.

## The sweep is a loop, not a query

The eligible set comes from one query (`users` with a current baseline); the anchor for each is then
computed in Java from two more lookups. That is N+1, deliberately. N is the number of households with a
confirmed basket, the sweep runs hourly, and the alternative is a JPQL statement with three correlated
subqueries and a `greatest()` that nobody will be able to change safely. If the user count ever makes
this matter, the fix is a single view, not a cleverer query today.

## Configuration

```yaml
komora:
  checkin:
    interval: ${CHECKIN_INTERVAL:3d}
    sweep-cron: ${CHECKIN_SWEEP_CRON:0 0 * * * *}
```

The interval is a property because a three-day demo is not a demo. A cron rather than a fixed delay:
`fixedDelay` fires once at startup, and an agent that messages everyone the moment the process comes
up is a bad property to have in a system that gets restarted. Tests set a cron that never fires and
call `sweep()` themselves.

## After the prompt

`conversation_state` is set to `CHECK_IN` / `AWAITING_REPORT`, which is the flag #12 reads to know that
the next message — text or voice — is a fridge report rather than a new request. `users.last_checkin_prompt_sent_at`
is stamped in the same step.

A Telegram failure for one user is logged and the sweep continues; one blocked chat must not stop
everybody else's check-in.

## Out of scope

Parsing the answer, the delta against the baseline, and inventory trends are #12. Routing the reply
into that parser is #12's wiring — this task only leaves the flag behind.
