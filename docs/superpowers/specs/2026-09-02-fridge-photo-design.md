# Fridge photo check-ins — design

Notion task **17. Fridge photo parsing (vision) demo**. A stretch item, explicitly scoped in the
product brief as "a couple of demo-quality examples, not robust". The design follows that scoping:
no new machinery where an existing path already works.

## Problem

A check-in currently arrives as text or as a voice note. A photo of an open fridge is the fastest
answer a person can give, and the model can read one. What it cannot do is count what is behind the
milk.

## The photo is a third input to the same pipeline

Everything after "what did they tell us" already exists: the baseline grounds the answer, the filter
drops anything invented, the delta is stored, the trend counters move. A photo therefore joins at the
same seam as voice — one more branch in `CheckinFlowService`, one more method on
`CheckinParsingService`, and nothing downstream learns that pictures exist.

```
text  ─┐
voice ─┼─→ CheckinDelta → filter to baseline → checkin row → inventory trend
photo ─┘
```

## Structured output is not available on the vision path

`ClaudeApiClient.completeStructured` is the SDK's schema-constrained call; `image(...)` returns text.
Rather than widen the client interface for one stretch feature, the photo prompt asks for the same JSON
object and the reply is parsed here. A reply that is not JSON is treated exactly like an unparseable
sentence: the raw text is stored, and the user is asked to say it in words.

That is a real difference in reliability between the paths, and it is the honest place to put it — the
vision answer is the approximate one anyway.

## Grounded, and honest about being approximate

The prompt carries the baseline item list, same as the text path, and the code filters against it
afterwards. On top of that the prompt says plainly what a photo cannot show: items behind other items,
how much is left inside an opaque package, anything outside the frame. Nothing visible goes to
`goneCompletely` — a thing missing from a photo is a thing that might be behind the juice.

The acknowledgement carries a one-line disclaimer so the person can correct it, rather than presenting
a guess as a reading.

## A source marker on the row

`checkin.source` (`TEXT`, `VOICE`, `PHOTO`), defaulting to `TEXT` for the rows that already exist. The
task asks for it so the data stays distinguishable later; it also makes the demo legible, and it costs
one column.

## One small generalisation

`TelegramOutboundService.downloadVoiceNote` becomes `downloadFile`. It was already the generic Bot API
`getFile` + download dance with a voice-shaped name, and photos need exactly it.

## What is not built

No counting, no per-item quantity estimates, no multi-photo stitching. The brief scopes this as a demo
enhancer, and a fridge photo cannot support more than a rough three-bucket reading.

The "two or three curated demo photos" acceptance criterion needs a real Anthropic key and real photos;
the runbook goes in the README and the criterion stays unticked until someone runs it.
