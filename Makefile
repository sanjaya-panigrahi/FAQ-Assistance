.PHONY: help check-tools setup-env build-all up-all up-all-limited ui-refresh down-all down-rebuild-up rebuild-indexes test-all clean kong-refresh kong-verify-graph kong-recover-graph kong-smoke-all phase3-smoke-events kafka-up kafka-down
.PHONY: spring-build spring-up spring-down spring-test
.PHONY: langchain-build langchain-up langchain-down langchain-test
.PHONY: langgraph-build langgraph-up langgraph-down langgraph-test

help:
	@echo "FAQ Assistance - Multi-Stack RAG Microservices"
	@echo ""
	@echo "Available targets:"
	@echo ""
	@echo "  build-all              Build all three stacks (Spring AI, LangChain, LangGraph)"
	@echo "  up-all                 Bring up all three stacks"
	@echo "  down-all               Shut down all three stacks"
	@echo "  down-rebuild-up        Down, rebuild images, and bring up all stacks"
	@echo "  test-all               Run smoke tests on all stacks"
	@echo "  clean                  Down and remove all containers (incl. orphans)"
	@echo "  rebuild-indexes        Rebuild FAQ indexes across all stacks"
	@echo "  check-tools            Validate local prerequisites"
	@echo "  setup-env              Create missing .env files from examples"
	@echo ""
	@echo "Spring AI (Java, port 9000 unified):"
	@echo "  spring-build           Build Spring AI services"
	@echo "  spring-up              Start Spring AI services"
	@echo "  spring-down            Stop Spring AI services"
	@echo "  spring-test            Test Spring AI endpoints"
	@echo ""
	@echo "LangChain (Python, port 8180 unified):"
	@echo "  langchain-build        Build LangChain services"
	@echo "  langchain-up           Start LangChain services"
	@echo "  langchain-down         Stop LangChain services"
	@echo "  langchain-test         Test LangChain endpoints"
	@echo ""
	@echo "LangGraph (Python, port 8280 unified):"
	@echo "  langgraph-build        Build LangGraph services"
	@echo "  langgraph-up           Start LangGraph services"
	@echo "  langgraph-down         Stop LangGraph services"
	@echo "  langgraph-test         Test LangGraph endpoints"
	@echo ""
	@echo "Resource management:"
	@echo "  up-all-limited         Start all stacks WITH memory/CPU limits (recommended for local dev)"
	@echo "  ui-refresh             Rebuild and refresh only the FAQ UI container"
	@echo ""
	@echo "Kong utilities:"
	@echo "  kong-refresh           Restart Kong to clear stale DNS cache"
	@echo "  kong-verify-graph      Verify /spring/graph route via Kong"
	@echo "  kong-recover-graph     Refresh Kong + verify graph + 5x smoke checks"
	@echo "  kong-smoke-all         Smoke-check graph/agentic/retrieval health and graph ask"
	@echo ""
	@echo "Phase 3 utilities:"
	@echo "  kafka-up               Start local Kafka + Zookeeper stack"
	@echo "  kafka-down             Stop local Kafka + Zookeeper stack"
	@echo "  phase3-smoke-events    Verify Kafka event consume stats for Spring Graph lifecycle"

check-tools:
	@command -v docker >/dev/null 2>&1 || (echo "✗ docker not found" && exit 1)
	@docker compose version >/dev/null 2>&1 || (echo "✗ docker compose not available" && exit 1)
	@command -v curl >/dev/null 2>&1 || (echo "✗ curl not found" && exit 1)
	@echo "✓ Required tools are available"

setup-env:
	@cp -n .env.example .env || true
	@cp -n .env.langchain.example .env.langchain || true
	@cp -n .env.langgraph.example .env.langgraph || true
	@echo "✓ Environment files are ready"

# === All Stacks ===

build-all: spring-build langchain-build langgraph-build
	@echo "✓ All stacks built"

up-all: check-tools setup-env spring-up langchain-up langgraph-up
	@echo "✓ All stacks running"
	@echo ""
	@echo "Spring AI:     http://localhost:9000 (unified)"
	@echo "LangChain:     http://localhost:8180 (unified)"
	@echo "LangGraph:     http://localhost:8280 (unified)"
	@echo "UI:            http://localhost:5173"

# Starts all stacks with per-container memory/CPU caps to prevent OOM on local machines
up-all-limited:
	@echo "Starting all stacks with resource limits..."
	@$(MAKE) check-tools setup-env
	COMPOSE_PROFILES=gateway,events docker compose -f docker-compose.master.yml -f docker-compose.infra.yml -f docker-compose.resources.yml up -d --build --remove-orphans
	@echo "✓ All stacks running with memory/CPU limits"

ui-refresh:
	@echo "Rebuilding and refreshing FAQ UI only..."
	COMPOSE_PROFILES=gateway docker compose -f docker-compose.master.yml -f docker-compose.infra.yml build faq-ui
	COMPOSE_PROFILES=gateway docker compose -f docker-compose.master.yml -f docker-compose.infra.yml up -d --no-deps --force-recreate faq-ui
	@curl -sS -o /dev/null -w 'UI status: %{http_code}\n' http://localhost:5173/
	@echo "✓ FAQ UI refreshed"

down-all: spring-down langchain-down langgraph-down
	@echo "✓ All stacks shut down"

down-rebuild-up:
	@echo "Running down -> rebuild -> up for all stacks..."
	$(MAKE) down-all
	$(MAKE) build-all
	$(MAKE) up-all
	@echo "✓ Down, rebuild, and up completed"

test-all: spring-test langchain-test langgraph-test
	@echo "✓ All smoke tests passed"

rebuild-indexes: spring-rebuild-indexes langchain-rebuild-indexes langgraph-rebuild-indexes
	@echo "✓ All indexes rebuilt"

clean:
	@$(MAKE) down-all
	@COMPOSE_PROFILES=events docker compose -f docker-compose.infra.yml down >/dev/null 2>&1 || true
	docker compose -f docker-compose.master.yml down --remove-orphans
	@echo "✓ Containers removed"

kong-refresh:
	@echo "Restarting Kong gateway to clear resolver cache..."
	docker restart kong-gateway
	@echo "✓ Kong restarted (may take ~20-40s to become healthy)"

kong-verify-graph:
	@echo "Verifying /spring/graph route via Kong..."
	@curl -fsS --retry 40 --retry-delay 1 --retry-all-errors --retry-connrefused \
		-X POST http://localhost:9080/spring/graph/api/query/ask \
		-H 'Content-Type: application/json' \
		-d '{"question":"What is Graph RAG?","customerId":"croma"}' > /dev/null \
		&& echo "✓ /spring/graph is reachable via Kong" \
		|| (echo "✗ /spring/graph failed via Kong after retries" && exit 1)

kong-recover-graph: kong-refresh kong-verify-graph
	@echo "Running 5-request smoke check for /spring/graph..."
	@for i in 1 2 3 4 5; do \
		code=$$(curl -s -o /dev/null -w '%{http_code}' -X POST http://localhost:9080/spring/graph/api/query/ask -H 'Content-Type: application/json' -d '{"question":"What is Graph RAG?","customerId":"croma"}'); \
		echo "  graph smoke $$i: $$code"; \
		if [ "$$code" != "200" ]; then \
			echo "✗ Graph smoke check failed on attempt $$i"; \
			exit 1; \
		fi; \
	done
	@echo "✓ Graph recovery and smoke check passed"

kong-smoke-all:
	@echo "Running Kong smoke checks (graph, agentic, retrieval)..."
	@curl -fsS http://localhost:9080/spring/graph/actuator/health > /dev/null \
		&& echo "✓ /spring/graph/actuator/health" \
		|| (echo "✗ /spring/graph/actuator/health" && exit 1)
	@curl -fsS http://localhost:9080/spring/agentic/actuator/health > /dev/null \
		&& echo "✓ /spring/agentic/actuator/health" \
		|| (echo "✗ /spring/agentic/actuator/health" && exit 1)
	@curl -fsS http://localhost:9080/spring/retrieval/actuator/health > /dev/null \
		&& echo "✓ /spring/retrieval/actuator/health" \
		|| (echo "✗ /spring/retrieval/actuator/health" && exit 1)
	@code=$$(curl -s -o /dev/null -w '%{http_code}' -X POST http://localhost:9080/spring/graph/api/query/ask -H 'Content-Type: application/json' -d '{"question":"What is Graph RAG?","customerId":"croma"}'); \
		echo "graph ask smoke: $$code"; \
		if [ "$$code" != "200" ]; then \
			echo "✗ /spring/graph/api/query/ask"; \
			exit 1; \
		fi
	@echo "✓ Kong smoke checks passed"

phase3-smoke-events:
	@echo "Running Phase 3 event smoke check (Kafka producer+consumer + Spring graph task lifecycle)..."
	@$(MAKE) kafka-up
	@echo "Building spring-ai-unified..."
	@cd spring-ai-unified && mvn -q -DskipTests package
	@echo "Recreating spring-ai-unified with event producer/consumer enabled..."
	@APP_EVENTS_ENABLED=true APP_EVENTS_CONSUMER_ENABLED=true APP_EVENTS_TOPIC=graph.tasks.lifecycle.v1 APP_EVENTS_CONSUMER_GROUP_ID=phase3-smoke-consumer KAFKA_BOOTSTRAP_SERVERS=faq-kafka:29092 \
		docker compose -f docker-compose.master.yml up -d --force-recreate spring-ai-unified
	@stats_json=""; \
		ready=0; \
		for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30; do \
			stats_json=$$(curl -fsS --max-time 3 http://localhost:9000/graph/api/events/stats 2>/dev/null || true); \
			if [ -n "$$stats_json" ]; then ready=1; break; fi; \
			echo "  waiting for spring events endpoint ($$i/30)..."; \
			sleep 1; \
		done; \
		if [ "$$ready" -ne 1 ]; then echo "✗ Spring events endpoint not ready in time"; exit 1; fi; \
		initial_total=$$(echo "$$stats_json" | sed -n 's/.*"totalConsumed":\([0-9]*\).*/\1/p'); \
		if [ -z "$$initial_total" ]; then echo "✗ Could not read initial totalConsumed"; exit 1; fi; \
		echo "initial consumed events: $$initial_total"; \
		task_id=$$(curl -sS -X POST http://localhost:9000/graph/api/index/rebuild | sed -n 's/.*"taskId":"\([^"]*\)".*/\1/p'); \
		if [ -z "$$task_id" ]; then echo "✗ Spring task id missing"; exit 1; fi; \
		echo "spring task: $$task_id"; \
		task_done=0; \
		for i in $$(seq 1 180); do \
			status=$$(curl -sS http://localhost:9000/graph/api/tasks/$$task_id | awk -F'"status":"' 'NF>1{split($$2,a,"\""); print a[1]}'); \
			echo "  spring poll $$i: $$status"; \
			if [ "$$status" = "COMPLETE" ]; then task_done=1; break; fi; \
			if [ "$$status" = "FAILED" ]; then echo "✗ Spring task failed"; exit 1; fi; \
			sleep 1; \
		done; \
		if [ "$$task_done" -ne 1 ]; then echo "✗ Spring task did not reach COMPLETE in time"; exit 1; fi; \
		events_ok=0; \
		for i in $$(seq 1 30); do \
			current_total=$$(curl -sS http://localhost:9000/graph/api/events/stats | sed -n 's/.*"totalConsumed":\([0-9]*\).*/\1/p'); \
			if [ -z "$$current_total" ]; then current_total=$$initial_total; fi; \
			echo "  event poll $$i: totalConsumed=$$current_total"; \
			if [ "$$current_total" -gt "$$initial_total" ]; then events_ok=1; break; fi; \
			sleep 1; \
		done; \
		if [ "$$events_ok" -ne 1 ]; then \
			echo "✗ Event stats did not increase after rebuild"; \
			curl -sS http://localhost:9000/graph/api/events/stats; echo; \
			exit 1; \
		fi
	@echo "✓ Phase 3 event smoke check passed"

kafka-up:
	@echo "Starting Kafka infrastructure for Phase 3..."
	COMPOSE_PROFILES=events docker compose -f docker-compose.master.yml -f docker-compose.infra.yml up -d zookeeper kafka
	@echo "✓ Kafka available on localhost:9092"

kafka-down:
	@echo "Stopping Kafka infrastructure..."
	COMPOSE_PROFILES=events docker compose -f docker-compose.infra.yml stop zookeeper kafka
	@echo "✓ Kafka stack stopped"

# === Spring AI (Java) ===

spring-build:
	@echo "Building Spring AI services..."
	docker compose -f docker-compose.yml build

spring-up:
	@echo "Starting Spring AI services..."
	cp -n .env.example .env || true
	docker compose -f docker-compose.yml up -d

spring-down:
	@echo "Stopping Spring AI services..."
	docker compose -f docker-compose.yml down

spring-rebuild-indexes:
	@echo "Rebuilding Spring AI FAQ indexes..."
	@for pattern in agentic corrective hierarchical multimodal graph; do \
		echo "  $$pattern..."; \
		curl -s -X POST http://localhost:9000/$$pattern/api/index/rebuild > /dev/null 2>&1; \
	done

spring-test:
	@echo "Testing Spring AI unified endpoint..."
	@curl -s http://localhost:9000/actuator/health | grep -q "UP" && echo "✓ spring-ai-unified :9000" || echo "✗ spring-ai-unified :9000"

# === LangChain (Python) ===

langchain-build:
	@echo "Building LangChain unified service..."
	docker compose -f docker-compose.master.yml build langchain-unified langchain-unified-worker

langchain-up:
	@echo "Starting LangChain unified service..."
	docker compose -f docker-compose.master.yml up -d langchain-unified langchain-unified-worker

langchain-down:
	@echo "Stopping LangChain unified service..."
	docker compose -f docker-compose.master.yml stop langchain-unified langchain-unified-worker

langchain-rebuild-indexes:
	@echo "Rebuilding LangChain FAQ indexes..."
	@for pattern in agentic corrective hierarchical multimodal graph retrieval; do \
		echo "  $$pattern..."; \
		curl -s -X POST http://localhost:8180/$$pattern/api/index/rebuild > /dev/null 2>&1; \
	done

langchain-test:
	@echo "Testing LangChain unified endpoint..."
	@curl -fsS http://localhost:8180/actuator/health | grep -q '"status"[[:space:]]*:[[:space:]]*"UP"' && echo "✓ langchain-unified :8180" || echo "✗ langchain-unified :8180"

# === LangGraph (Python) ===

langgraph-build:
	@echo "Building LangGraph unified service..."
	docker compose -f docker-compose.master.yml build langgraph-unified langgraph-unified-worker

langgraph-up:
	@echo "Starting LangGraph unified service..."
	docker compose -f docker-compose.master.yml up -d langgraph-unified langgraph-unified-worker

langgraph-down:
	@echo "Stopping LangGraph unified service..."
	docker compose -f docker-compose.master.yml stop langgraph-unified langgraph-unified-worker

langgraph-rebuild-indexes:
	@echo "Rebuilding LangGraph FAQ indexes..."
	@for pattern in agentic corrective hierarchical multimodal graph retrieval; do \
		echo "  $$pattern..."; \
		curl -s -X POST http://localhost:8280/$$pattern/api/index/rebuild > /dev/null 2>&1; \
	done

langgraph-test:
	@echo "Testing LangGraph unified endpoint..."
	@curl -fsS http://localhost:8280/actuator/health | grep -q '"status"[[:space:]]*:[[:space:]]*"UP"' && echo "✓ langgraph-unified :8280" || echo "✗ langgraph-unified :8280"
