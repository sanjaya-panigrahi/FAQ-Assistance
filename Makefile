.PHONY: help build-all up-all down-all down-rebuild-up rebuild-indexes test-all clean clean-orphans
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
	@echo "  clean-orphans          Remove orphaned containers"
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

# === All Stacks ===

build-all: spring-build langchain-build langgraph-build
	@echo "✓ All stacks built"

up-all: spring-up langchain-up langgraph-up
	@echo "✓ All stacks running"
	@echo ""
	@echo "Spring AI UI:  http://localhost:5173"
	@echo "LangChain:     http://localhost:8181-8185"
	@echo "LangGraph:     http://localhost:8281-8285"

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

clean-orphans:
	docker compose -f docker-compose.master.yml down --remove-orphans
	@echo "✓ Orphaned containers removed"

clean:
	docker compose -f docker-compose.master.yml down --remove-orphans
	@echo "✓ Containers removed"

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
	@curl -s http://localhost:8181/actuator/health | grep -q "UP" && echo "✓ Port 8181" || echo "✗ Port 8181"
	@curl -s http://localhost:8182/actuator/health | grep -q "UP" && echo "✓ Port 8182" || echo "✗ Port 8182"
	@curl -s http://localhost:8183/actuator/health | grep -q "UP" && echo "✓ Port 8183" || echo "✗ Port 8183"
	@curl -s http://localhost:8184/actuator/health | grep -q "UP" && echo "✓ Port 8184" || echo "✗ Port 8184"
	@curl -s http://localhost:8185/actuator/health | grep -q "UP" && echo "✓ Port 8185" || echo "✗ Port 8185"

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
	@curl -s http://localhost:8281/actuator/health | grep -q "UP" && echo "✓ Port 8281" || echo "✗ Port 8281"
	@curl -s http://localhost:8282/actuator/health | grep -q "UP" && echo "✓ Port 8282" || echo "✗ Port 8282"
	@curl -s http://localhost:8283/actuator/health | grep -q "UP" && echo "✓ Port 8283" || echo "✗ Port 8283"
	@curl -s http://localhost:8284/actuator/health | grep -q "UP" && echo "✓ Port 8284" || echo "✗ Port 8284"
	@curl -s http://localhost:8285/actuator/health | grep -q "UP" && echo "✓ Port 8285" || echo "✗ Port 8285"
