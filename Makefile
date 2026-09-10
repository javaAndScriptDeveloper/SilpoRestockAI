.DEFAULT_GOAL := help
.PHONY: help run dev demo mcp-log test build format check db-up db-down up down image clean metrics promotions \
	alloy-up alloy-down alloy-logs dashboard dashboards-json observability-local-up observability-local-down \
	deploy prod-up prod-down prod-logs prod-ps prod-alloy-up prod-alloy-down prod-alloy-logs \
	webhook webhook-info

# The production stack (task 59). Its own compose project name lives in docker-compose.prod.yml, so these
# never touch the development containers above, and .env.prod never mixes with .env.
PROD := docker compose -f docker-compose.prod.yml --env-file .env.prod

# Prefer .env if present, otherwise fall back to the committed example.
ENV_FILE := $(if $(wildcard .env),.env,.env.example)

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-12s\033[0m %s\n", $$1, $$2}'

run: ## Run the app (auto-starts docker-compose DB), full log at logs/app.log
	@mkdir -p logs
	set -a; . ./$(ENV_FILE); set +a; ./gradlew bootRun 2>&1 | tee logs/app.log

dev: ## Run with a throwaway Testcontainers DB (no docker-compose needed)
	set -a; . ./$(ENV_FILE); set +a; ./gradlew bootTestRun

demo: ## Run in recording mode: quiet app log, colour-coded MCP/Claude lines (profile `demo`)
	@mkdir -p logs
	set -a; . ./$(ENV_FILE); set +a; SPRING_PROFILES_ACTIVE=demo ./gradlew bootRun 2>&1 | tee logs/app.log

mcp-log: ## tail -f the demo call log — the second window during a screen recording
	@mkdir -p logs
	@touch logs/mcp-calls.log
	@tail -f logs/mcp-calls.log

test: ## Run unit + integration tests (needs Docker for Testcontainers)
	./gradlew test

build: ## Full build incl. tests and formatting check
	./gradlew build

format: ## Auto-format the codebase
	./gradlew spotlessApply

check: ## Verify formatting without changing files
	./gradlew spotlessCheck

db-up: ## Start the local Postgres in the background
	docker compose --env-file $(ENV_FILE) up -d db

db-down: ## Stop the local Postgres
	docker compose down

up: ## Build & run the full stack (app + db) with $(ENV_FILE)
	docker compose --env-file $(ENV_FILE) --profile full up --build -d

down: ## Stop the full stack
	docker compose --profile full down

image: ## Build the OCI image (tag: silpo-restock-ai)
	docker build -t silpo-restock-ai .

clean: ## Remove build artifacts
	./gradlew clean

metrics: ## Print the pitch-metrics report from the running app (needs METRICS_TOKEN in .env)
	@set -a; . ./$(ENV_FILE); set +a; \
	curl -sf -H "X-Metrics-Token: $$METRICS_TOKEN" "http://localhost:$${SERVER_PORT:-8080}/internal/metrics/pitch" \
	|| echo "no report: is the app running, and is METRICS_TOKEN set in .env?"

promotions: ## Print the partner-placement funnel report from the running app (needs METRICS_TOKEN in .env)
	@set -a; . ./$(ENV_FILE); set +a; \
	curl -sf -H "X-Metrics-Token: $$METRICS_TOKEN" "http://localhost:$${SERVER_PORT:-8080}/internal/promotions/report" \
	|| echo "no report: is the app running, and is METRICS_TOKEN set in .env?"

alloy-up: ## Start Grafana Alloy, pushing /actuator/prometheus to Grafana Cloud (needs GRAFANA_CLOUD_* in .env)
	docker compose --env-file $(ENV_FILE) --profile observability up -d alloy
	@echo "Alloy UI: http://localhost:$${ALLOY_PORT:-12345}  (the scrape target should read UP)"

alloy-down: ## Stop Grafana Alloy
	docker compose --profile observability rm -sf alloy

alloy-logs: ## Follow Alloy's logs — where a rejected Grafana Cloud token shows up
	docker compose --profile observability logs -f alloy

dashboard: ## Push every observability/grafana/*.json dashboard to Grafana (needs GRAFANA_URL + GRAFANA_API_TOKEN)
	@set -a; . ./$(ENV_FILE); set +a; \
	for f in observability/grafana/*.json; do \
	  jq -n --slurpfile d $$f \
	     '{dashboard: ($$d[0] + {id: null}), overwrite: true, message: "komora dashboards"}' \
	  | curl -sf -X POST -H "Authorization: Bearer $$GRAFANA_API_TOKEN" -H "Content-Type: application/json" \
	      --data-binary @- "$$GRAFANA_URL/api/dashboards/db" \
	  | jq -r '"pushed: " + .url' \
	  || echo "no push for $$f: are GRAFANA_URL and GRAFANA_API_TOKEN set in .env?"; \
	done

dashboards-json: ## Regenerate observability/grafana/*.json from build-dashboards.py (never hand-edit the JSON)
	python3 observability/grafana/build-dashboards.py

observability-local-up: ## Throwaway Prometheus+Grafana rendering the same two dashboards, no cloud token needed
	docker compose -f observability/local/docker-compose.yml up -d
	@echo "Business:  http://localhost:3000/d/komora-business  (anonymous admin)"
	@echo "Technical: http://localhost:3000/d/komora-observability"

observability-local-down: ## Tear the local observability harness down
	docker compose -f observability/local/docker-compose.yml down -v

# --- Production (task 59). Run these ON THE SERVER; they need .env.prod, which never leaves it. ---

deploy: ## Deploy on the server: git pull, pull the CI image, restart, verify (RUNBOOK "Deploy checklist")
	./scripts/deploy.sh

deploy-build: ## Same, but build the image on this machine instead of pulling what CI published
	./scripts/deploy.sh --build

prod-pull: ## Skip Watchtower's poll: pull the newest published image and restart the app now
	$(PROD) pull app
	$(PROD) up -d app
	@echo "running image: $$(docker inspect -f '{{.Config.Image}}' komora-app 2>/dev/null || echo unknown)"

prod-up: ## Start the production stack without pulling or rebuilding
	$(PROD) up -d

prod-down: ## Stop the production stack. Keeps the volumes — never add -v to this
	$(PROD) down

prod-logs: ## Follow the production app log
	$(PROD) logs -f app

prod-ps: ## Status and health of the production containers
	$(PROD) ps

# Alloy is the only thing that gets Комора's metrics off this host, and it lives behind a compose profile —
# so `prod-up` alone starts the whole stack except the metrics. `deploy.sh` turns the profile on by itself
# when .env.prod has a Grafana Cloud URL; these are for driving it by hand.
prod-alloy-up: ## Start Alloy on the server, pushing /actuator/prometheus to Grafana Cloud (needs GRAFANA_CLOUD_* in .env.prod)
	$(PROD) --profile observability up -d alloy
	@echo "no published port by design — check it with: make prod-alloy-logs"

prod-alloy-down: ## Stop the server's Alloy. Metrics stop reaching Grafana Cloud until it is started again
	$(PROD) --profile observability rm -sf alloy

prod-alloy-logs: ## Follow the server's Alloy log — where a rejected Grafana Cloud token shows up
	$(PROD) --profile observability logs -f alloy

webhook: ## Point Telegram at the DOMAIN in .env.prod (the app also does this itself at every boot)
	./scripts/set-webhook.sh

webhook-info: ## What Telegram thinks the webhook is, incl. last_error_message — "why is the bot silent?"
	./scripts/set-webhook.sh --info
