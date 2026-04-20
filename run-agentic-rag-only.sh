#!/bin/bash

set -euo pipefail

# FAQ Assistance - Agentic + RAG only launcher
# Starts only the required services for Agentic and Retrieval pipelines.

GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

usage() {
    cat <<'EOF'
Usage: ./run-agentic-rag-only.sh [options]

Options:
  --down       Stop and remove only these Agentic + RAG services
  -h, --help   Show this help message

Services started:
  consul
  chroma-faq
  faq-ingestion
  spring-ai-agentic
  spring-ai-faq-retrieval
  langchain-agentic
  langchain-retrieval-service
  langgraph-agentic
  langgraph-retrieval-service
  analytics-mysql
  rag-analytics
  kong
  konga-db
  konga-prepare
  konga
  faq-ui
EOF
}

DOWN_MODE="false"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --down)
            DOWN_MODE="true"
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo -e "${RED}Unknown option: $1${NC}"
            usage
            exit 1
            ;;
    esac
done

if ! command -v docker >/dev/null 2>&1; then
    echo -e "${RED}Docker not found. Please install Docker.${NC}"
    exit 1
fi

if docker compose version >/dev/null 2>&1; then
    COMPOSE_BIN=(docker compose)
    COMPOSE_CMD="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then
    COMPOSE_BIN=(docker-compose)
    COMPOSE_CMD="docker-compose"
else
    echo -e "${RED}Docker Compose not found. Please install Docker Compose.${NC}"
    exit 1
fi

SERVICES=(
    consul
    chroma-faq
    faq-ingestion
    spring-ai-agentic
    spring-ai-faq-retrieval
    langchain-agentic
    langchain-retrieval-service
    langgraph-agentic
    langgraph-retrieval-service
    analytics-mysql
    rag-analytics
)

COMPOSE_FILES=(
    -f docker-compose.master.yml
    -f docker-compose.consul.yml
    -f docker-compose.kong.yml
    -f docker-compose.konga.yml
)

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}Agentic + RAG Only Launcher${NC}"
echo -e "${BLUE}========================================${NC}"
echo -e "${YELLOW}Compose:${NC} ${COMPOSE_CMD} ${COMPOSE_FILES[*]}"
echo -e "${YELLOW}Services:${NC} ${SERVICES[*]}"
echo ""

if [[ "$DOWN_MODE" == "true" ]]; then
    echo -e "${BLUE}Stopping Agentic + RAG services...${NC}"
    "${COMPOSE_BIN[@]}" "${COMPOSE_FILES[@]}" stop konga konga-prepare konga-db kong faq-ui || true
    "${COMPOSE_BIN[@]}" "${COMPOSE_FILES[@]}" rm -f konga konga-prepare konga-db kong faq-ui || true
    "${COMPOSE_BIN[@]}" "${COMPOSE_FILES[@]}" stop "${SERVICES[@]}"
    "${COMPOSE_BIN[@]}" "${COMPOSE_FILES[@]}" rm -f "${SERVICES[@]}"
    echo -e "${GREEN}Done.${NC}"
    exit 0
fi

if [[ ! -f .env && -f .env.example ]]; then
    echo -e "${YELLOW}Creating .env from .env.example${NC}"
    cp .env.example .env
    echo -e "${YELLOW}Set OPENAI_API_KEY in .env if required before calling inference endpoints.${NC}"
fi

# List of services NOT needed for Agentic + RAG only
UNWANTED_SERVICES=(
    spring-ai-neo4j-graph
    spring-ai-corrective
    spring-ai-multimodal
    spring-ai-hierarchical
    langchain-neo4j-graph
    langchain-corrective
    langchain-multimodal
    langchain-hierarchical
    langgraph-neo4j-graph
    langgraph-corrective
    langgraph-multimodal
    langgraph-hierarchical
)

UNWANTED_CONTAINERS=(
    neo4j-spring
    neo4j-langchain
    neo4j-langgraph
)

echo -e "${BLUE}Cleaning up unwanted services...${NC}"
for service in "${UNWANTED_SERVICES[@]}"; do
    "${COMPOSE_BIN[@]}" "${COMPOSE_FILES[@]}" stop "$service" 2>/dev/null || true
    "${COMPOSE_BIN[@]}" "${COMPOSE_FILES[@]}" rm -f "$service" 2>/dev/null || true
done

# Also remove standalone Neo4j containers that might be running
for container in "${UNWANTED_CONTAINERS[@]}"; do
    docker stop "$container" 2>/dev/null || true
    docker rm "$container" 2>/dev/null || true
done

echo -e "${BLUE}Starting required containers only (Agentic + RAG pipelines)...${NC}"
# Start core data/ingestion services
"${COMPOSE_BIN[@]}" "${COMPOSE_FILES[@]}" up --build -d --no-deps chroma-faq
"${COMPOSE_BIN[@]}" "${COMPOSE_FILES[@]}" up --build -d --no-deps faq-ingestion

# Start Agentic + Retrieval services for each framework
"${COMPOSE_BIN[@]}" "${COMPOSE_FILES[@]}" up --build -d --no-deps spring-ai-agentic
"${COMPOSE_BIN[@]}" "${COMPOSE_FILES[@]}" up --build -d --no-deps spring-ai-faq-retrieval
"${COMPOSE_BIN[@]}" "${COMPOSE_FILES[@]}" up --build -d --no-deps langchain-agentic
"${COMPOSE_BIN[@]}" "${COMPOSE_FILES[@]}" up --build -d --no-deps langchain-retrieval-service
"${COMPOSE_BIN[@]}" "${COMPOSE_FILES[@]}" up --build -d --no-deps langgraph-agentic
"${COMPOSE_BIN[@]}" "${COMPOSE_FILES[@]}" up --build -d --no-deps langgraph-retrieval-service

# Start infrastructure services
"${COMPOSE_BIN[@]}" "${COMPOSE_FILES[@]}" up --build -d --no-deps consul
"${COMPOSE_BIN[@]}" "${COMPOSE_FILES[@]}" up --build -d --no-deps analytics-mysql
"${COMPOSE_BIN[@]}" "${COMPOSE_FILES[@]}" up --build -d --no-deps rag-analytics

# Start gateway and UI without pulling in non-target stacks through depends_on expansion.
"${COMPOSE_BIN[@]}" "${COMPOSE_FILES[@]}" up --build -d --no-deps kong faq-ui

# Start Konga stack without dependency expansion.
# If konga starts without --no-deps, Compose can traverse konga -> kong -> kong depends_on,
# which recreates non-target stacks from the master compose file.
"${COMPOSE_BIN[@]}" "${COMPOSE_FILES[@]}" up --build -d --no-deps konga-db
"${COMPOSE_BIN[@]}" "${COMPOSE_FILES[@]}" up --build -d konga-prepare
"${COMPOSE_BIN[@]}" "${COMPOSE_FILES[@]}" up --build -d --no-deps konga

# Safety net: if unwanted services slip in through future compose changes,
# immediately stop/remove them so this launcher stays Agentic + RAG focused.
for service in "${UNWANTED_SERVICES[@]}"; do
    "${COMPOSE_BIN[@]}" "${COMPOSE_FILES[@]}" stop "$service" 2>/dev/null || true
    "${COMPOSE_BIN[@]}" "${COMPOSE_FILES[@]}" rm -f "$service" 2>/dev/null || true
done

for container in "${UNWANTED_CONTAINERS[@]}"; do
    docker stop "$container" 2>/dev/null || true
    docker rm "$container" 2>/dev/null || true
done

echo ""
echo -e "${GREEN}Services are starting in background.${NC}"
echo -e "${BLUE}Useful checks:${NC}"
echo -e "  ${YELLOW}docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'${NC}"
echo -e "  ${YELLOW}curl -s http://localhost:8500/v1/status/leader${NC}"
echo -e "  ${YELLOW}curl -s http://localhost:9081/services${NC}"
echo -e "  ${YELLOW}curl -s http://localhost:9080/spring/agentic/actuator/health${NC}"
echo -e "  ${YELLOW}curl -s -o /dev/null -w '%{http_code}\n' http://localhost:1337${NC}"
echo -e "  ${YELLOW}curl -s http://localhost:8081/actuator/health${NC}"
echo -e "  ${YELLOW}curl -s http://localhost:8186/actuator/health${NC}"
echo -e "  ${YELLOW}curl -s http://localhost:8286/actuator/health${NC}"
echo -e "  ${YELLOW}open http://localhost:5173${NC}"
echo -e "  ${YELLOW}open http://localhost:1337${NC}"
