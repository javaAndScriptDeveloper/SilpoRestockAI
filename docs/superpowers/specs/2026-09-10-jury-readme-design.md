# A README the jury can verify from (task 77)

## Problem

The README is still the Spring Boot template's: stack table, prerequisites, `make run`. A judge who scans the
repo QR code lands on a dev-setup page and has to take the pitch's claims on trust — which is the opposite of
why the QR code exists. The repo is the jury's second point of contact after the video, and it is the only one
where every claim can be checked against primary evidence by clicking.

Sources read fresh for this design (2026-09-10): the hackathon page's «Завдання» and «Критерії оцінювання»
(fetched live, not from memory), the Notion task page, «Selling Points та Пітч-аргументи», the current README,
`docs/RUNBOOK.md`, and `PitchArtifactService.FLOW_NOTES` (task 55).

### The hackathon's six requirements, verbatim as fetched on 2026-09-10

| # | Вимога |
|---|---|
| 01 | Рішення обов'язково використовує офіційний MCP «Сільпо». |
| 02 | AI-агент або помічник не лише генерує текст, а використовує доступні tools для виконання завдання. |
| 03 | Чітко визначено, для кого створене рішення і яку проблему воно вирішує. |
| 04 | Є working demo, інтерактивний прототип або переконлива демонстрація сценарію. |
| 05 | Показано, де саме може працювати рішення: у продукті «Сільпо», зовнішньому сервісі або як окремий продукт. |
| 06 | Є пояснення, як перевірити корисність рішення через тестування, інтерв'ю, метрики, експеримент або економічний ефект. |

Deadline: **14 вересня 2026, 23:59:59**. The wording matches the table already in «Selling Points», so that
table is reused rather than rewritten — the README's job is to add the one column a pitch deck cannot have: a
link into the code.

## Goal

A README in Ukrainian — the jury's language, and the reason this file exists — that answers each of the six
requirements in one line with a link into the repository, carries the same MCP matrix as task 55's artifact,
and points at the live things a judge can open. Developer setup shrinks to a few lines and a link to
`docs/RUNBOOK.md`, which stays in English and stays the full manual.

## Structure

1. **Хук і що це** — one paragraph, the pitch's opening line, plus one sentence on the revenue model
   (paid partner placement #46 / own-brand featuring #63).
2. **Живі посилання** — the bot, the pitch artifact page, both Grafana dashboards.
3. **Пряма відповідь на 6 вимог** — a four-column table: `#` · вимога (verbatim) · відповідь · де це в коді.
   Every row's last cell is a repository link, because that is what a deck cannot do.
4. **MCP-матриця** — the same tool set the artifact renders, with the same flow note per tool and the same
   headline count. Generated from `PitchArtifactService.FLOW_NOTES`, so the two cannot disagree by hand.
5. **Архітектура за 30 секунд** — the flow from `/start` to checkout, then the package map, both linking to
   directories.
6. **Запуск** — five lines, then `docs/RUNBOOK.md`.
7. **Ліцензія, BYOK і відкритість до «Сільпо»** — three short paragraphs, linking to `LICENSE` and to the
   full argument in «Selling Points».

## What has to change outside the README

**`LICENSE` is MIT and the decision was Apache 2.0** — «Selling Points» says why: the same permissive terms
plus an explicit patent grant, which is what a corporate legal team wants to see before adopting code. The
file is replaced with the canonical Apache 2.0 text, copyright 2026 Kyryl Feshchenko.

## The honest gap: there is no public deployment yet

Task 59 is Done, but `.env.prod` carries `DOMAIN=localhost`: the production stack has only ever been
rehearsed locally, and Watchtower/GHCR (task 69) was rehearsed the same way. The Grafana dashboards live at
`charmingaphid2632.grafana.net` and need a login; no public-dashboard sharing has been set up.

The README states what is true today and links what actually opens:

- The Telegram bot, by @username — it is reachable right now and needs no server of ours.
- The pitch artifact, as the committed file in the repo, plus the path it will be served at
  (`/pitch.html`) once a domain exists.
- The dashboards, marked as needing a Grafana login, with the note that the JSON is in the repo and
  `make observability-local-up` renders both against a throwaway Prometheus for anyone who wants to see them
  without an account.

No invented URL, and no «coming soon» that a judge would read as a broken promise. Closing the gap — a real
domain in `.env.prod`, and Grafana public-dashboard links — is the user's call and is flagged separately.

## Testing

Documentation, so the test is a read-through plus two mechanical checks:

- Every repository-relative link in the README resolves to a file or directory that exists.
- The tool count and the tool names in the README's matrix equal `PitchArtifactService.FLOW_NOTES` and the
  artifact's own headline.

## Out of scope

Restating «Сценарій демо-запису» or «Selling Points». The README links to primary sources — the code, the
artifact, the dashboards — instead of repeating the argument.
