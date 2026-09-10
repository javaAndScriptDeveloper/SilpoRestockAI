# Jury README Implementation Plan (task 77)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the template README with one a judge can verify from — the hackathon's six requirements answered one line each with a link into the code, the same MCP matrix task 55's artifact renders, and the links that actually open today.

**Architecture:** Documentation only. The README is written in Ukrainian (the jury's language and the reason the file exists); `docs/RUNBOOK.md` stays English and stays the full manual, and the README points at it rather than duplicating it. The MCP matrix is copied from `PitchArtifactService.FLOW_NOTES` and the generated `src/main/resources/static/pitch.html`, so the two artifacts state the same number. `LICENSE` is replaced with the canonical Apache 2.0 text.

**Tech Stack:** Markdown; the canonical Apache 2.0 text from `/usr/share/common-licenses/Apache-2.0`.

**Spec:** `docs/superpowers/specs/2026-09-10-jury-readme-design.md`

## Global Constraints

- Requirement wording is quoted verbatim from the live hackathon page as fetched on 2026-09-10, not paraphrased.
- Every claim in the six-requirement table ends in a repository-relative link that resolves.
- The MCP matrix's tool names and headline count equal task 55's artifact exactly — no hand-typed numbers.
- No invented URLs. A link that needs a login says so; a link that does not exist yet is not written at all.
- `LICENSE` is the unmodified Apache 2.0 text apart from the copyright line.

---

### Task 1: Apache 2.0 licence

**Files:**
- Modify: `LICENSE`

- [ ] **Step 1: Replace the file**

```bash
cp /usr/share/common-licenses/Apache-2.0 LICENSE
```

Then substitute the boilerplate copyright line with `   Copyright 2026 Kyryl Feshchenko`.

- [ ] **Step 2: Verify**

Run: `head -3 LICENSE && grep -c "Copyright 2026 Kyryl Feshchenko" LICENSE`
Expected: the Apache header, and `1`.

---

### Task 2: The README

**Files:**
- Modify: `README.md` (full rewrite)

- [ ] **Step 1: Write the seven sections**

In order: hook and revenue model · live links · the 01–06 table · the MCP matrix · architecture at a glance ·
running it · licence, BYOK and openness to Silpo. Section content is specified in the design document.

- [ ] **Step 2: Check every repository link resolves**

```bash
grep -oE '\]\(([^)h][^)]*)\)' README.md | sed -E 's/^\]\(//; s/\)$//' | cut -d'#' -f1 \
  | while read -r p; do [ -e "$p" ] || echo "MISSING: $p"; done
```
Expected: no output.

- [ ] **Step 3: Check the matrix against the artifact**

```bash
diff <(grep -oE 'silpo_[a-z_]+' README.md | sort -u) \
     <(grep -oE 'silpo_[a-z_]+' src/main/resources/static/pitch.html | sort -u)
```
Expected: no lines that are in the README but not in the page.

- [ ] **Step 4: Commit**

```bash
git add README.md LICENSE docs/superpowers/
git commit -m "Answer the jury's six questions in the repo they land on"
```
