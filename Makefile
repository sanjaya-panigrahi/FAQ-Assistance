.PHONY: help check-tools setup-env build-all up-all up-all-limited down-all down-rebuild-up rebuild-indexes test-all clean kong-refresh kong-verify-graph kong-recover-graph kong-smoke-all phase3-smoke-events kafka-up kafka-down
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
	@echo "Spring AI (Java, ports 8081-8085):"
	@echo "  spring-build           Build Spring AI services"
	@echo "  spring-up              Start Spring AI services"
	@echo "  spring-down            Stop Spring AI services"
	@echo "  spring-test            Test Spring AI endpoints"
	@echo ""
	@echo "LangChain (Python, ports 8181-8185):"
	@echo "  langchain-build        Build LangChain services"
	@echo "  langchain-up           Start LangChain services"
	@echo "  langchain-down         Stop LangChain services"
	@echo "  langchain-test         Test LangChain endpoints"
	@echo ""
	@echo "LangGraph (Python, ports 8281-8285):"
	@echo "  langgraph-build        Build LangGraph services"
	@echo "  langgraph-up           Start LangGraph services"
	@echo "  langgraph-down         Stop LangGraph services"
	@echo "  langgraph-test         Test LangGraph endpoints"
	@echo ""
	@echo "Resource management:"
	@echo "  up-all-limited         Start all stacks WITH memory/CPU limits (recommended for local dev)"
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
	@echo "Spring AI UI:  http://localhost:5173"
	@echo "LangChain:     http://localhost:8181-8185"
	@echo "LangGraph:     http://localhost:8281-8285"

# Starts all stacks with per-container memory/CPU caps to prevent OOM on local machines
up-all-limited:
	@echo "Starting all stacks with resource limits..."
	@$(MAKE) check-tools setup-env
	docker compose -f docker-compose.master.yml -f docker-compose.resources.yml -f docker-compose.kong.yml up -d --remove-orphans
	@echo "✓ All stacks running with memory/CPU limits"

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
	@docker compose -f docker-compose.kafka.yml down >/dev/null 2>&1 || true
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
	@echo "Packaging spring-ai-neo4j-graph on host..."
	@cd spring-ai-neo4j-graph && mvn -q -DskipTests package
	@echo "Building spring-ai-neo4j-graph runtime image with latest host-built jar..."
	@docker build -t faq-assistance-spring-ai-neo4j-graph:latest -f spring-ai-neo4j-graph/Dockerfile.runtime spring-ai-neo4j-graph
	@echo "Recreating spring-ai-neo4j-graph with event producer/consumer enabled..."
	@APP_EVENTS_ENABLED=true APP_EVENTS_CONSUMER_ENABLED=true APP_EVENTS_TOPIC=graph.tasks.lifecycle.v1 APP_EVENTS_CONSUMER_GROUP_ID=phase3-smoke-consumer KAFKA_BOOTSTRAP_SERVERS=faq-kafka:29092 \
		docker compose -f docker-compose.master.yml up -d --force-recreate spring-ai-neo4j-graph
	@stats_json=""; \
		ready=0; \
		for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30; do \
			stats_json=$$(curl -fsS --max-time 3 http://localhost:8082/api/events/stats 2>/dev/null || true); \
			if [ -n "$$stats_json" ]; then ready=1; break; fi; \
			echo "  waiting for spring events endpoint ($$i/30)..."; \
			sleep 1; \
		done; \
		if [ "$$ready" -ne 1 ]; then echo "✗ Spring events endpoint not ready in time"; exit 1; fi; \
		initial_total=$$(echo "$$stats_json" | sed -n 's/.*"totalConsumed":\([0-9]*\).*/\1/p'); \
		if [ -z "$$initial_total" ]; then echo "✗ Could not read initial totalConsumed"; exit 1; fi; \
		echo "initial consumed events: $$initial_total"; \
		task_id=$$(curl -sS -X POST http://localhost:8082/api/index/rebuild | sed -n 's/.*"taskId":"\([^"]*\)".*/\1/p'); \
		if [ -z "$$task_id" ]; then echo "✗ Spring task id missing"; exit 1; fi; \
		echo "spring task: $$task_id"; \
		task_done=0; \
		for i in $$(seq 1 180); do \
			status=$$(curl -sS http://localhost:8082/api/tasks/$$task_id | awk -F'"status":"' 'NF>1{split($$2,a,"\""); print a[1]}'); \
			echo "  spring poll $$i: $$status"; \
			if [ "$$status" = "COMPLETE" ]; then task_done=1; break; fi; \
			if [ "$$status" = "FAILED" ]; then echo "✗ Spring task failed"; exit 1; fi; \
			sleep 1; \
		done; \
		if [ "$$task_done" -ne 1 ]; then echo "✗ Spring task did not reach COMPLETE in time"; exit 1; fi; \
		events_ok=0; \
		for i in $$(seq 1 30); do \
			current_total=$$(curl -sS http://localhost:8082/api/events/stats | sed -n 's/.*"totalConsumed":\([0-9]*\).*/\1/p'); \
			if [ -z "$$current_total" ]; then current_total=$$initial_total; fi; \
			echo "  event poll $$i: totalConsumed=$$current_total"; \
			if [ "$$current_total" -gt "$$initial_total" ]; then events_ok=1; break; fi; \
			sleep 1; \
		done; \
		if [ "$$events_ok" -ne 1 ]; then \
			echo "✗ Event stats did not increase after rebuild"; \
			curl -sS http://localhost:8082/api/events/stats; echo; \
			exit 1; \
		fi
	@echo "✓ Phase 3 event smoke check passed"

kafka-up:
	@echo "Starting Kafka infrastructure for Phase 3..."
	docker compose -f docker-compose.kafka.yml up -d
	@echo "✓ Kafka available on localhost:9092"

kafka-down:
	@echo "Stopping Kafka infrastructure..."
	docker compose -f docker-compose.kafka.yml down
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
	@for port in 8081 8082 8083 8084 8085; do \
		echo "  Port $$port..."; \
		curl -s -X POST http://localhost:$$port/api/index/rebuild > /dev/null 2>&1; \
	done

spring-test:
	@echo "Testing Spring AI endpoints..."
	@curl -s http://localhost:8081/actuator/health | grep -q "UP" && echo "✓ Port 8081" || echo "✗ Port 8081"
	@curl -s http://localhost:8082/actuator/health | grep -q "UP" && echo "✓ Port 8082" || echo "✗ Port 8082"
	@curl -s http://localhost:8083/actuator/health | grep -q "UP" && echo "✓ Port 8083" || echo "✗ Port 8083"
	@curl -s http://localhost:8084/actuator/health | grep -q "UP" && echo "✓ Port 8084" || echo "✗ Port 8084"
	@curl -s http://localhost:8085/actuator/health | grep -q "UP" && echo "✓ Port 8085" || echo "✗ Port 8085"

# === LangChain (Python) ===

langchain-build:
	@echo "Building LangChain services..."
	docker compose -f docker-compose.langchain.yml build

langchain-up:
	@echo "Starting LangChain services..."
	cp -n .env.langchain.example .env.langchain || true
	docker compose -f docker-compose.langchain.yml up -d

langchain-down:
	@echo "Stopping LangChain services..."
	docker compose -f docker-compose.langchain.yml down

langchain-rebuild-indexes:
	@echo "Rebuilding LangChain FAQ indexes..."
	@for port in 8181 8182 8183 8184 8185; do \
		echo "  Port $$port..."; \
		curl -s -X POST http://localhost:$$port/api/index/rebuild > /dev/null 2>&1; \
	done

langchain-test:
	@echo "Testing LangChain endpoints..."
	@curl -fsS http://localhost:8181/actuator/health | grep -q '"status"[[:space:]]*:[[:space:]]*"UP"' && echo "✓ Port 8181" || echo "✗ Port 8181"
	@curl -fsS http://localhost:8182/actuator/health | grep -q '"status"[[:space:]]*:[[:space:]]*"UP"' && echo "✓ Port 8182" || echo "✗ Port 8182"
	@curl -fsS http://localhost:8183/actuator/health | grep -q '"status"[[:space:]]*:[[:space:]]*"UP"' && echo "✓ Port 8183" || echo "✗ Port 8183"
	@curl -fsS http://localhost:8184/actuator/health | grep -q '"status"[[:space:]]*:[[:space:]]*"UP"' && echo "✓ Port 8184" || echo "✗ Port 8184"
	@curl -fsS http://localhost:8185/actuator/health | grep -q '"status"[[:space:]]*:[[:space:]]*"UP"' && echo "✓ Port 8185" || echo "✗ Port 8185"

# === LangGraph (Python) ===

langgraph-build:
	@echo "Building LangGraph services..."
	docker compose -f docker-compose.langgraph.yml build

langgraph-up:
	@echo "Starting LangGraph services..."
	cp -n .env.langgraph.example .env.langgraph || true
	docker compose -f docker-compose.langgraph.yml up -d

langgraph-down:
	@echo "Stopping LangGraph services..."
	docker compose -f docker-compose.langgraph.yml down

langgraph-rebuild-indexes:
	@echo "Rebuilding LangGraph FAQ indexes..."
	@for port in 8281 8282 8283 8284 8285; do \
		echo "  Port $$port..."; \
		curl -s -X POST http://localhost:$$port/api/index/rebuild > /dev/null 2>&1; \
	done

langgraph-test:
	@echo "Testing LangGraph endpoints..."
	@curl -fsS http://localhost:8281/actuator/health | grep -q '"status"[[:space:]]*:[[:space:]]*"UP"' && echo "✓ Port 8281" || echo "✗ Port 8281"
	@curl -fsS http://localhost:8282/actuator/health | grep -q '"status"[[:space:]]*:[[:space:]]*"UP"' && echo "✓ Port 8282" || echo "✗ Port 8282"
	@curl -fsS http://localhost:8283/actuator/health | grep -q '"status"[[:space:]]*:[[:space:]]*"UP"' && echo "✓ Port 8283" || echo "✗ Port 8283"
	@curl -fsS http://localhost:8284/actuator/health | grep -q '"status"[[:space:]]*:[[:space:]]*"UP"' && echo "✓ Port 8284" || echo "✗ Port 8284"
	@curl -fsS http://localhost:8285/actuator/health | grep -q '"status"[[:space:]]*:[[:space:]]*"UP"' && echo "✓ Port 8285" || echo "✗ Port 8285"
