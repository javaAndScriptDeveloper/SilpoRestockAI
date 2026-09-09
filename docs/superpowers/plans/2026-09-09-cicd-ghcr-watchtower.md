# CI/CD: GHCR publish + Watchtower auto-deploy — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A push to `main` that passes CI publishes a Docker image to ghcr.io, and the demo server picks it up and restarts itself — with no inbound connection from CI and no SSH key or long-lived credential anywhere.

**Architecture:** Pull-based CD. GitHub Actions gains one image job that runs `needs: build`, so it starts only after `spotlessCheck` and the full `./gradlew build` pass. It authenticates with the workflow's built-in `GITHUB_TOKEN` scoped to `packages: write` on that job alone — no repository secret is created, so there is none to rotate or leak. On the server, Watchtower polls ghcr.io outbound and updates only the container carrying its opt-in label; Postgres and Caddy are never touched.

**Tech Stack:** GitHub Actions, `docker/{metadata,setup-buildx,login,build-push}-action`, GitHub Container Registry, Watchtower (`containrrr/watchtower`), Docker Compose v2.

**Spec:** No separate spec file — this is the bounded-path design approved in chat on 2026-09-09, implementing Notion task *69. Simple CI/CD: GitHub Actions build+push, Watchtower auto-deploy on server* (`https://app.notion.com/p/3d67227def1c81ba94ddc1780a5b2b69`). Task 69's acceptance criteria are the requirements.

## Global Constraints

- **No SSH private key and no broad-scope credential in GitHub Secrets.** The publish job uses the built-in `GITHUB_TOKEN` only. This is a hard requirement of the task, not a preference.
- **The GHCR package is public** (decided with the user). Watchtower therefore needs no registry credentials on the server at all. The image carries no secrets: every value comes from `.env.prod` at runtime.
- **Repository slug is `javaAndScriptDeveloper/SilpoRestockAI`; GHCR requires a lowercase image path** — `ghcr.io/javaandscriptdeveloper/silporestockai`. Lowercase it explicitly in the workflow rather than relying on an action's implicit behaviour.
- **Push to ghcr.io happens only after tests pass, never in parallel** (`needs: build`), and only on a `push` event to `main` — never on a pull request, never on another branch.
- **Extend `docker-compose.prod.yml` from task 59; do not create a parallel prod compose file.** It keeps its `name: komora-prod` project isolation.
- **Watchtower updates the app container only.** `WATCHTOWER_LABEL_ENABLE=true` plus the opt-in label on `app`. An unattended Postgres major-version bump would destroy the database.
- Spotless (palantir) governs Java only; none of this task's files are Java. `make format` still must pass unchanged.

---

### Task 1: Publish the image to GHCR after tests pass

**Files:**
- Modify: `.github/workflows/ci.yml` (add a second job; leave the existing `build` job untouched)

**Interfaces:**
- Consumes: the existing `build` job's success, by name (`needs: build`).
- Produces: `ghcr.io/javaandscriptdeveloper/silporestockai:latest` and `:sha-<short>` on every green push to `main`. Task 2's compose default and Task 4's runbook both name that exact path.

- [ ] **Step 1: Add the job to `.github/workflows/ci.yml`**

Append after the existing `build` job. Note `permissions` is set per-job so the top-level `contents: read` stays the default for everything else.

```yaml
  # Build the image on every run — the Dockerfile breaks independently of the test suite, and catching that
  # on the pull request is the cheapest place to catch it. Push only from a green main.
  image:
    # Sequential, deliberately: `needs` makes this wait for spotlessCheck and the full test suite. An image
    # is never published from a commit whose tests have not passed.
    needs: build
    runs-on: ubuntu-latest
    permissions:
      contents: read
      # The one extra grant, scoped to this job. This is what replaces a stored registry credential:
      # GITHUB_TOKEN is minted per run and expires with it, so there is no secret to rotate or leak.
      packages: write
    steps:
      - uses: actions/checkout@v4

      # GHCR rejects an uppercase image path, and this repository's slug has capitals in it.
      - name: Compute the lowercase image path
        run: echo "IMAGE=ghcr.io/${GITHUB_REPOSITORY,,}" >> "$GITHUB_ENV"

      - name: Set up Buildx
        uses: docker/setup-buildx-action@v3

      - name: Log in to GHCR
        # Only when something is actually going to be pushed; a pull request build needs no credentials.
        if: github.event_name == 'push' && github.ref == 'refs/heads/main'
        uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - name: Derive tags and labels
        id: meta
        uses: docker/metadata-action@v5
        with:
          images: ${{ env.IMAGE }}
          # `latest` is what Watchtower follows; the sha tag is what a rollback pins to.
          tags: |
            type=raw,value=latest,enable={{is_default_branch}}
            type=sha,format=short

      - name: Build and push
        uses: docker/build-push-action@v6
        with:
          context: .
          push: ${{ github.event_name == 'push' && github.ref == 'refs/heads/main' }}
          tags: ${{ steps.meta.outputs.tags }}
          labels: ${{ steps.meta.outputs.labels }}
          # The Dockerfile compiles with Gradle inside the build stage. Without a cache that is a cold
          # dependency download on every run; with it, only changed sources are recompiled.
          cache-from: type=gha
          cache-to: type=gha,mode=max
```

- [ ] **Step 2: Verify the workflow parses and the gating is right**

```bash
python3 -c "import yaml,sys; d=yaml.safe_load(open('.github/workflows/ci.yml')); \
print('jobs:', list(d['jobs'])); \
print('needs:', d['jobs']['image']['needs']); \
print('perms:', d['jobs']['image']['permissions'])"
```

Expected: `jobs: ['build', 'image']`, `needs: build`, `perms: {'contents': 'read', 'packages': 'write'}`.

- [ ] **Step 3: Verify no secret other than GITHUB_TOKEN is referenced**

```bash
grep -oE 'secrets\.[A-Za-z_]+' .github/workflows/ci.yml | sort -u
```

Expected: exactly `secrets.GITHUB_TOKEN` and nothing else. Any other line is an acceptance-criteria failure.

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "Publish the image to GHCR once the tests are green"
```

---

### Task 2: Run the app from the registry and add Watchtower

**Files:**
- Modify: `docker-compose.prod.yml` (parameterise `app.image`, add the opt-in label, add a `watchtower` service and nothing else)

**Interfaces:**
- Consumes: the image path Task 1 publishes.
- Produces: `APP_IMAGE` and `WATCHTOWER_POLL_INTERVAL` as environment knobs, which Task 3's `deploy.sh` and Task 4's `.env.prod.example` both name.

- [ ] **Step 1: Point the app at the registry image, keeping the local build as a fallback**

Replace the app service's `image:` line, and add a `labels:` block:

```yaml
    # Default to what CI publishes (task 69). `build:` above stays for the offline fallback:
    # `docker compose pull app` then `up -d` uses the pulled image, and `deploy.sh --build` uses the
    # local build instead. Pin this to a :sha-xxxxxxx tag in .env.prod to roll back or freeze a demo.
    image: ${APP_IMAGE:-ghcr.io/javaandscriptdeveloper/silporestockai:latest}
```

```yaml
    labels:
      # Watchtower's opt-in. It is on the app and on NOTHING else in this file: an unattended Postgres
      # major-version upgrade would destroy the database, and a restarted Caddy re-negotiates TLS.
      com.centurylinklabs.watchtower.enable: "true"
```

- [ ] **Step 2: Add the Watchtower service after `caddy`**

```yaml
  # Pull-based CD (task 69). Watchtower polls ghcr.io outbound and restarts the app when `latest` moves,
  # so the server needs no inbound access and CI needs no SSH key — that is the whole point of it.
  #
  # It needs the Docker socket, which is effectively root on the host. That is inherent to how Watchtower
  # works and cannot be configured away; it is why the image comes from a registry only this repository's
  # CI can publish to. Mounted read-only, which limits nothing but costs nothing.
  watchtower:
    image: containrrr/watchtower:1.7.1
    container_name: komora-watchtower
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock:ro
    environment:
      # Update ONLY containers carrying com.centurylinklabs.watchtower.enable=true — the app.
      WATCHTOWER_LABEL_ENABLE: "true"
      # Delete the superseded image after a successful update. A demo VPS disk fills up fast otherwise.
      WATCHTOWER_CLEANUP: "true"
      # Seconds. 60 keeps the gap between a green build and a live server around two minutes; raising it
      # costs nothing but patience, and the manual fallback in the runbook bypasses the wait entirely.
      WATCHTOWER_POLL_INTERVAL: ${WATCHTOWER_POLL_INTERVAL:-60}
      # So its log timestamps line up with the app's.
      TZ: Europe/Kyiv
    restart: unless-stopped
```

- [ ] **Step 3: Verify the compose file still resolves**

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod config -q && echo "config OK"
docker compose -f docker-compose.prod.yml --env-file .env.prod config \
  | grep -E "image:|watchtower.enable"
```

Expected: `config OK`; the app's image resolves to the ghcr path; the label appears exactly once.

- [ ] **Step 4: Verify Watchtower's scoping for real**

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d
sleep 25
docker logs komora-watchtower 2>&1 | grep -iE "Watching|Only checking|Scheduling|containers"
```

Expected: it reports watching exactly **1** container. If it reports 3, `WATCHTOWER_LABEL_ENABLE` is not taking effect and Postgres is in scope — stop and fix before going further.

- [ ] **Step 5: Verify it left the database and proxy alone**

```bash
docker inspect -f '{{.Name}} started={{.State.StartedAt}}' komora-db komora-caddy komora-app
```

Expected: `komora-db` and `komora-caddy` keep their original start times.

- [ ] **Step 6: Commit**

```bash
git add docker-compose.prod.yml
git commit -m "Auto-deploy the app image with Watchtower, and nothing else"
```

---

### Task 3: Flip `deploy.sh` to pulling the published image

**Files:**
- Modify: `scripts/deploy.sh` (flag parsing, the build step, the start step)
- Modify: `Makefile` (help text for `deploy`; no new target needed)

**Interfaces:**
- Consumes: `APP_IMAGE` from Task 2.
- Produces: `deploy.sh --build` as the documented offline fallback, which Task 4's runbook references by that exact spelling.

- [ ] **Step 1: Replace the `--no-build` flag with a `--build` fallback**

The old default built on the server; the new default pulls. Replace the flag block:

```bash
DO_PULL=1
MODE=registry

while [[ $# -gt 0 ]]; do
    case "$1" in
        --no-pull)  DO_PULL=0; shift ;;
        # Build the image here instead of pulling what CI published. For working offline, or on a commit
        # that has not been through CI yet.
        --build)    MODE=build; shift ;;
        -h|--help)  sed -n '2,12p' "$0"; exit 0 ;;
        *)          echo "unknown flag: $1" >&2; exit 2 ;;
    esac
done
```

- [ ] **Step 2: Replace the build step with a mode switch**

```bash
if [[ "$MODE" == "registry" ]]; then
    step "pull the published image"
    # What CI built from this commit's branch. Seconds, not minutes — and it sidesteps the Gradle build
    # being OOM-killed on a 1 GB VPS, which is the failure the --build path is prone to.
    "${COMPOSE[@]}" pull app
else
    step "build image locally"
    # Gradle inside the build stage wants well over a gigabyte. If this is OOM-killed, add swap:
    #   fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
    "${COMPOSE[@]}" build app
fi
```

- [ ] **Step 3: Report which image actually ended up running**

Add to the `verify` step, after the actuator check:

```bash
# Which image is serving, so a "did my fix deploy?" question has an answer that is not a guess.
echo "running image: $(docker inspect -f '{{.Config.Image}}' komora-app 2>/dev/null || echo unknown)"
```

- [ ] **Step 4: Update the usage comment at the top of the file**

```bash
#   scripts/deploy.sh              # pull the image CI published, restart, verify
#   scripts/deploy.sh --build      # build the image here instead (offline, or a commit CI has not seen)
#   scripts/deploy.sh --no-pull    # skip `git pull` (a hotfix you edited on the box)
```

- [ ] **Step 5: Verify the script and both modes**

```bash
bash -n scripts/deploy.sh && echo "syntax OK"
grep -c "no-build" scripts/deploy.sh   # expected: 0 — the old flag is fully gone
./scripts/deploy.sh --no-pull --build 2>&1 | tail -20
```

Expected: syntax OK; zero `no-build` mentions; the `--build` run reaches `=== verify ===` and prints a running image.

- [ ] **Step 6: Commit**

```bash
git add scripts/deploy.sh Makefile
git commit -m "Deploy by pulling the published image, building only as a fallback"
```

---

### Task 4: Document the flow, the polling delay, and the escape hatches

**Files:**
- Modify: `.env.prod.example` (add `APP_IMAGE`, `WATCHTOWER_POLL_INTERVAL`)
- Modify: `docs/RUNBOOK.md` (new section 20, after section 19's deploy checklist)

**Interfaces:**
- Consumes: every knob and flag from Tasks 1–3.
- Produces: nothing code depends on.

- [ ] **Step 1: Add the two knobs to `.env.prod.example`**

```bash
# --- Continuous deployment (task 69) ---
# The image Watchtower follows and deploy.sh pulls. The default is what CI publishes from main; nothing
# needs to be set here for the normal flow. Pin it to a :sha-xxxxxxx tag to roll back, or to freeze the
# demo box against further pushes while the jury is watching:
#   APP_IMAGE=ghcr.io/javaandscriptdeveloper/silporestockai:sha-1a2b3c4
APP_IMAGE=
# Seconds between checks of ghcr.io. 60 puts a green build on the server in roughly two minutes. There is
# no credential here because the package is public; if it is ever made private, Watchtower needs
# REPO_USER / REPO_PASS with a read-only (read:packages) token and nothing wider.
WATCHTOWER_POLL_INTERVAL=60
```

- [ ] **Step 2: Write RUNBOOK section 20**

Cover, in this order: the one-paragraph flow (push → tests → image → poll → restart); why there is no SSH key; the polling-delay tradeoff stated as a number, not a vibe; the manual immediate-deploy fallback; rollback by pinning `APP_IMAGE`; how to watch it happen; and the honest limits (Docker socket, a restart drops in-flight requests, `latest` racing a second push).

The manual fallback, verbatim, since it is the thing someone will copy at 3am:

```bash
# Do not wait for the poll — pull and restart now.
docker compose -f docker-compose.prod.yml --env-file .env.prod pull app
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d app
# or simply:
make deploy
```

- [ ] **Step 3: Verify the runbook renders and the fallback command is correct**

```bash
grep -n "^## 20\." docs/RUNBOOK.md
bash -n <(sed -n '/^```bash$/,/^```$/p' docs/RUNBOOK.md | grep -v '^```') 2>&1 | head -3
```

Expected: section 20 present; no syntax error reported from the extracted shell blocks.

- [ ] **Step 4: Commit**

```bash
git add .env.prod.example docs/RUNBOOK.md
git commit -m "Document the CD flow, its polling delay, and how to bypass it"
```

---

### Task 5: Full verification and push

**Files:** none modified — this task only runs checks.

- [ ] **Step 1: Confirm the acceptance criteria that can be checked without a server**

```bash
# No SSH key, no broad-scope credential anywhere in CI.
grep -rniE "ssh|private_key|deploy_key" .github/workflows/ | grep -v "^Binary" || echo "no ssh references"
grep -oE 'secrets\.[A-Za-z_]+' .github/workflows/ci.yml | sort -u   # expect only secrets.GITHUB_TOKEN
```

- [ ] **Step 2: Run the full build, unchanged by this task**

```bash
./gradlew build
```

Expected: `BUILD SUCCESSFUL`. Nothing in this plan touches Java, so a failure here is unrelated and must be investigated before pushing.

- [ ] **Step 3: Tear the rehearsal stack down**

```bash
make prod-down
docker ps --format '{{.Names}}' | grep -E "app-db" && echo "dev stack still intact"
```

- [ ] **Step 4: Push to main**

```bash
git push origin main
```

The push is what proves acceptance criterion 1: watch the `image` job appear in Actions, wait for it to go green, and confirm the package exists at `ghcr.io/javaandscriptdeveloper/silporestockai`. Criterion 2 — the server picking it up — cannot be verified until task 59 has a host.

---

## Self-Review

**Spec coverage against task 69's acceptance criteria:**

| Criterion | Task |
|---|---|
| A push to `main` that passes CI produces a new image in ghcr.io | 1 (built), 5 (proven by the push) |
| The server picks it up and restarts within the polling interval | 2 — verifiable only once task 59 has a host; stated as such |
| No SSH key or broad-scope credential in GitHub Secrets | 1, verified explicitly in 1.3 and 5.1 |
| Runbook documents the polling delay and the manual fallback | 4 |

**Placeholder scan:** none — every step carries the literal YAML, shell, or command it needs.

**Type consistency:** `APP_IMAGE` and `WATCHTOWER_POLL_INTERVAL` are spelled identically in Tasks 2, 3 and 4. The label key `com.centurylinklabs.watchtower.enable` matches `WATCHTOWER_LABEL_ENABLE`. The flag is `--build` in Tasks 3 and 4, and Task 3 Step 5 asserts the old `--no-build` is gone.

**Known gap:** criterion 2 is not verifiable in this session. That is a dependency on task 59, not an omission, and it is why the Notion status becomes *In review* rather than *Done*.
