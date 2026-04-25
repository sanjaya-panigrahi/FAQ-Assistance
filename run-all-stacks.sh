#!/bin/bash

set -euo pipefail

# Color codes
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

SPRING_PORTS=(9000)
LANGCHAIN_PORTS=(8180)
LANGGRAPH_PORTS=(8280)

REBUILD_MODE="none"
REBUILD_SERVICES=""
MINIMAL_DEMO="false"
WITH_KONG="false"
WITH_CONSUL="false"
WITH_KONG_CONSUL="false"
WITH_KONGA="false"
WITH_ELK="false"

usage() {
    cat <<'EOF'
Usage: ./run-all-stacks.sh [options]

Options:
  --rebuild <mode>         Rebuild index mode: none|all|spring|langchain|langgraph
                           Default: none
  --rebuild-services <csv> Rebuild only specific services (by id or port).
                           Example: --rebuild-services spring-agentic,langgraph-neo4j,8183
    --minimal-demo          Start only demo-critical services (Agentic + Pipeline per framework)
                                                    Services: chroma-faq, faq-ingestion, spring-ai-unified, spring-ai-faq-retrieval,
                                                                        langchain-unified, langchain-unified-worker,
                                                                        langgraph-unified, langgraph-unified-worker, faq-ui
    --with-kong             Include Kong gateway overlay
    --with-consul           Include Consul discovery overlay
    --with-kong-consul      Use Kong with Consul DNS discovery (requires --with-kong --with-consul)
    --with-konga            Include Konga UI overlay (requires --with-kong)
    --with-elk              Include Elasticsearch + Kibana + Logstash overlay
  -h, --help               Show this help message

Service IDs:
  spring (unified port 9000), langchain (unified port 8180), langgraph (unified port 8280)

Examples:
  ./run-all-stacks.sh
  ./run-all-stacks.sh --rebuild spring
  ./run-all-stacks.sh --rebuild-services spring,langchain
    ./run-all-stacks.sh --with-kong
    ./run-all-stacks.sh --with-kong --with-consul --with-kong-consul --with-konga
    ./run-all-stacks.sh --with-elk
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --rebuild)
            if [[ $# -lt 2 ]]; then
                echo -e "${RED}Missing value for --rebuild${NC}"
                usage
                exit 1
            fi
            REBUILD_MODE="$2"
            shift 2
            ;;
        --rebuild-services)
            if [[ $# -lt 2 ]]; then
                echo -e "${RED}Missing value for --rebuild-services${NC}"
                usage
                exit 1
            fi
            REBUILD_SERVICES="$2"
            shift 2
            ;;
        --with-kong)
            WITH_KONG="true"
            shift
            ;;
        --minimal-demo)
            MINIMAL_DEMO="true"
            shift
            ;;
        --with-consul)
            WITH_CONSUL="true"
            shift
            ;;
        --with-kong-consul)
            WITH_KONG_CONSUL="true"
            shift
            ;;
        --with-konga)
            WITH_KONGA="true"
            shift
            ;;
        --with-elk)
            WITH_ELK="true"
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

append_port_once() {
    local target="$1"
    for existing in "${REBUILD_PORTS[@]-}"; do
        if [[ "$existing" == "$target" ]]; then
            return
        fi
    done
    REBUILD_PORTS+=("$target")
}

collect_rebuild_ports() {
    REBUILD_PORTS=()

    case "$REBUILD_MODE" in
        none)
            ;;
        all)
            for p in "${SPRING_PORTS[@]}" "${LANGCHAIN_PORTS[@]}" "${LANGGRAPH_PORTS[@]}"; do
                append_port_once "$p"
            done
            ;;
        spring)
            for p in "${SPRING_PORTS[@]}"; do
                append_port_once "$p"
            done
            ;;
        langchain)
            for p in "${LANGCHAIN_PORTS[@]}"; do
                append_port_once "$p"
            done
            ;;
        langgraph)
            for p in "${LANGGRAPH_PORTS[@]}"; do
                append_port_once "$p"
            done
            ;;
        *)
            echo -e "${RED}Invalid --rebuild mode: $REBUILD_MODE${NC}"
            usage
            exit 1
            ;;
    esac

    if [[ -n "$REBUILD_SERVICES" ]]; then
        IFS=',' read -r -a requested <<< "$REBUILD_SERVICES"
        for raw in "${requested[@]}"; do
            svc="$(echo "$raw" | xargs)"
            case "$svc" in
                spring) append_port_once 9000 ;;
                langchain) append_port_once 8180 ;;
                langgraph) append_port_once 8280 ;;
                9000|8180|8280)
                    append_port_once "$svc"
                    ;;
                *)
                    echo -e "${RED}Invalid service in --rebuild-services: $svc${NC}"
                    usage
                    exit 1
                    ;;
            esac
        done
    fi
}

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}FAQ Assistance - All Stacks Launcher${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

# Check for required tools
echo -e "${YELLOW}Checking prerequisites...${NC}"
if ! command -v docker &> /dev/null; then
    echo -e "${RED}✗ Docker not found. Please install Docker.${NC}"
    exit 1
fi
echo -e "${GREEN}✓ Docker found${NC}"

if docker compose version &> /dev/null; then
    COMPOSE_CMD="docker compose"
    COMPOSE_BIN=(docker compose)
elif command -v docker-compose &> /dev/null; then
    COMPOSE_CMD="docker-compose"
    COMPOSE_BIN=(docker-compose)
else
    echo -e "${RED}✗ Docker Compose not found. Please install Docker Compose.${NC}"
    exit 1
fi
echo -e "${GREEN}✓ Docker Compose found (${COMPOSE_CMD})${NC}"

COMPOSE_FILES=(-f docker-compose.master.yml -f docker-compose.infra.yml)
COMPOSE_PROFILES=()

if [[ "$WITH_CONSUL" == "true" ]]; then
    COMPOSE_PROFILES+=(discovery)
fi

if [[ "$WITH_KONG" == "true" ]]; then
    COMPOSE_PROFILES+=(gateway)
fi

if [[ "$WITH_KONG_CONSUL" == "true" ]]; then
    if [[ "$WITH_KONG" != "true" || "$WITH_CONSUL" != "true" ]]; then
        echo -e "${RED}--with-kong-consul requires both --with-kong and --with-consul${NC}"
        exit 1
    fi
    COMPOSE_FILES+=(-f docker-compose.kong-consul.yml)
fi

if [[ "$WITH_KONGA" == "true" ]]; then
    if [[ "$WITH_KONG" != "true" ]]; then
        echo -e "${RED}--with-konga requires --with-kong${NC}"
        exit 1
    fi
    COMPOSE_PROFILES+=(konga)
fi

if [[ "$WITH_ELK" == "true" ]]; then
    COMPOSE_PROFILES+=(elk)
fi

if [[ ${#COMPOSE_PROFILES[@]} -gt 0 ]]; then
    export COMPOSE_PROFILES=$(IFS=,; echo "${COMPOSE_PROFILES[*]}")
fi

echo -e "${BLUE}Compose overlays:${NC} ${COMPOSE_FILES[*]}"
if [[ "$MINIMAL_DEMO" == "true" ]]; then
    echo -e "${YELLOW}Minimal demo mode enabled: starting only Agentic + Pipeline services${NC}"
fi

if ! command -v curl &> /dev/null; then
    echo -e "${RED}✗ curl not found. Please install curl.${NC}"
    exit 1
fi
echo -e "${GREEN}✓ curl found${NC}"
echo ""

# Copy .env if needed
if [ ! -f .env ]; then
    if [ -f .env.example ]; then
        echo -e "${YELLOW}Creating .env from .env.example${NC}"
        cp .env.example .env
        echo -e "${YELLOW}⚠ Please set OPENAI_API_KEY in .env before proceeding${NC}"
        echo ""
    fi
fi

# Start all services
echo -e "${BLUE}Starting selected stack overlays...${NC}"
if [[ "$MINIMAL_DEMO" == "true" ]]; then
    MINIMAL_SERVICES=(
        chroma-faq
        faq-ingestion
        spring-ai-unified
        spring-ai-faq-retrieval
        langchain-unified
        langchain-unified-worker
        langgraph-unified
        langgraph-unified-worker
        faq-ui
    )

    "${COMPOSE_BIN[@]}" "${COMPOSE_FILES[@]}" up --build -d "${MINIMAL_SERVICES[@]}"

    if [[ "$WITH_KONG" == "true" ]]; then
        # Start Kong without dependency expansion to avoid bringing non-demo stacks up.
        "${COMPOSE_BIN[@]}" "${COMPOSE_FILES[@]}" up --build -d --no-deps kong
    fi

    if [[ "$WITH_KONGA" == "true" ]]; then
        "${COMPOSE_BIN[@]}" "${COMPOSE_FILES[@]}" up --build -d konga-db konga-prepare konga
    fi
else
    "${COMPOSE_BIN[@]}" "${COMPOSE_FILES[@]}" up --build -d
fi

if [ $? -ne 0 ]; then
    echo -e "${RED}✗ Failed to start containers${NC}"
    exit 1
fi

echo -e "${GREEN}✓ Containers started${NC}"
echo ""

# Wait for services
echo -e "${YELLOW}Waiting 30 seconds for services to initialize...${NC}"
sleep 30
echo ""

# Health checks
if [[ "$MINIMAL_DEMO" == "true" ]]; then
    SPRING_HEALTH_PORTS=(9000)
    LANGCHAIN_HEALTH_PORTS=(8180)
    LANGGRAPH_HEALTH_PORTS=(8280)
else
    SPRING_HEALTH_PORTS=(9000)
    LANGCHAIN_HEALTH_PORTS=(8180)
    LANGGRAPH_HEALTH_PORTS=(8280)
fi

echo -e "${BLUE}Health Check - Spring AI Stack (ports ${SPRING_HEALTH_PORTS[*]}):${NC}"
for port in "${SPRING_HEALTH_PORTS[@]}"; do
    response=$(curl -s --max-time 5 http://localhost:$port/actuator/health 2>/dev/null)
    if echo "$response" | grep -q "UP"; then
        echo -e "${GREEN}✓ Port $port: UP${NC}"
    else
        echo -e "${RED}✗ Port $port: DOWN${NC}"
    fi
done
echo ""

echo -e "${BLUE}Health Check - LangChain Stack (ports ${LANGCHAIN_HEALTH_PORTS[*]}):${NC}"
for port in "${LANGCHAIN_HEALTH_PORTS[@]}"; do
    response=$(curl -s --max-time 5 http://localhost:$port/actuator/health 2>/dev/null)
    if echo "$response" | grep -q "UP"; then
        echo -e "${GREEN}✓ Port $port: UP${NC}"
    else
        echo -e "${RED}✗ Port $port: DOWN${NC}"
    fi
done
echo ""

echo -e "${BLUE}Health Check - LangGraph Stack (ports ${LANGGRAPH_HEALTH_PORTS[*]}):${NC}"
for port in "${LANGGRAPH_HEALTH_PORTS[@]}"; do
    response=$(curl -s --max-time 5 http://localhost:$port/actuator/health 2>/dev/null)
    if echo "$response" | grep -q "UP"; then
        echo -e "${GREEN}✓ Port $port: UP${NC}"
    else
        echo -e "${RED}✗ Port $port: DOWN${NC}"
    fi
done
echo ""

echo -e "${BLUE}Health Check - FAQ Ingestion Stack:${NC}"
response=$(curl -s --max-time 5 http://localhost:8000/api/v2/heartbeat 2>/dev/null)
if [ -z "$response" ]; then
    response=$(curl -s --max-time 5 http://localhost:8000/api/v1/heartbeat 2>/dev/null)
fi
if [ -n "$response" ]; then
    echo -e "${GREEN}✓ ChromaDB (port 8000): UP${NC}"
else
    echo -e "${RED}✗ ChromaDB (port 8000): DOWN${NC}"
fi
response=$(curl -s --max-time 5 http://localhost:9020/api/faq-ingestion/health 2>/dev/null)
if echo "$response" | grep -qE '"status":"(UP|DEGRADED)"'; then
    echo -e "${GREEN}✓ FAQ Ingestion (port 9020): UP${NC}"
else
    echo -e "${RED}✗ FAQ Ingestion (port 9020): DOWN${NC}"
fi

response=$(curl -s --max-time 5 http://localhost:9010/actuator/health 2>/dev/null)
if echo "$response" | grep -q '"status":"UP"'; then
    echo -e "${GREEN}✓ FAQ Retrieval (port 9010): UP${NC}"
else
    echo -e "${RED}✗ FAQ Retrieval (port 9010): DOWN${NC}"
fi

if [[ "$WITH_KONG" == "true" ]]; then
    response=$(curl -s --max-time 5 http://localhost:9081/services 2>/dev/null)
    if [[ -n "$response" ]]; then
        echo -e "${GREEN}✓ Kong Admin (port 9081): UP${NC}"
    else
        echo -e "${RED}✗ Kong Admin (port 9081): DOWN${NC}"
    fi

    response=$(curl -s --max-time 5 http://localhost:9080/spring/agentic/actuator/health 2>/dev/null)
    if echo "$response" | grep -q '"status":"UP"'; then
        echo -e "${GREEN}✓ Kong Proxy (port 9080): UP${NC}"
    else
        echo -e "${RED}✗ Kong Proxy (port 9080): DOWN${NC}"
    fi
fi

if [[ "$WITH_CONSUL" == "true" ]]; then
    response=$(curl -s --max-time 5 http://localhost:8500/v1/status/leader 2>/dev/null)
    if [[ -n "$response" ]]; then
        echo -e "${GREEN}✓ Consul (port 8500): UP${NC}"
    else
        echo -e "${RED}✗ Consul (port 8500): DOWN${NC}"
    fi
fi

if [[ "$WITH_KONGA" == "true" ]]; then
    code=$(curl -s -o /dev/null --max-time 5 -w '%{http_code}' http://localhost:1337 2>/dev/null || true)
    if [[ "$code" == "200" || "$code" == "302" ]]; then
        echo -e "${GREEN}✓ Konga (port 1337): UP${NC}"
    else
        echo -e "${RED}✗ Konga (port 1337): DOWN${NC}"
    fi
fi
echo ""

collect_rebuild_ports
if [[ ${#REBUILD_PORTS[@]} -eq 0 ]]; then
    echo -e "${YELLOW}Skipping FAQ index rebuild (mode: none).${NC}"
    echo -e "${YELLOW}Tip: use --rebuild spring or --rebuild-services spring-agentic${NC}"
    echo ""
else
    echo -e "${BLUE}Rebuilding FAQ indexes on selected services...${NC}"
    for port in "${REBUILD_PORTS[@]}"; do
        curl -s -X POST "http://localhost:${port}/api/index/rebuild" > /dev/null 2>&1
    done
    echo -e "${GREEN}✓ Indexes rebuilt on ports: ${REBUILD_PORTS[*]}${NC}"
    echo ""
fi

# Summary
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}✓ All stacks are running!${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo -e "${BLUE}📊 Stack Overview:${NC}"
if [[ "$MINIMAL_DEMO" == "true" ]]; then
    echo -e "  Spring AI   (Port 9000): Unified (all patterns)"
    echo -e "  LangChain   (Port 8180): Unified (all patterns)"
    echo -e "  LangGraph   (Port 8280): Unified (all patterns)"
else
    echo -e "  Spring AI   (Port 9000): Unified — Agentic, Graph, Corrective, Multimodal, Hierarchical"
    echo -e "  LangChain   (Port 8180): Unified — Agentic, Graph, Corrective, Multimodal, Hierarchical, Retrieval"
    echo -e "  LangGraph   (Port 8280): Unified — Agentic, Graph, Corrective, Multimodal, Hierarchical, Retrieval"
fi
echo -e "  FAQ Services   (Ports 8000, 9020, 9010): ChromaDB, Ingestion API, Retrieval API"
echo ""
echo -e "${BLUE}🌐 UI Access:${NC}"
echo -e "  Main UI:              ${GREEN}http://localhost:5173${NC}"
echo -e "  FAQ Ingestion API:    ${GREEN}http://localhost:9020/api/faq-ingestion/health${NC}"
echo -e "  FAQ Retrieval API:    ${GREEN}http://localhost:9010/actuator/health${NC}"
echo -e "  H2 Console:           ${GREEN}http://localhost:9020/h2-console${NC}"
if [[ "$WITH_KONG" == "true" ]]; then
    echo -e "  Kong Proxy:           ${GREEN}http://localhost:9080${NC}"
    echo -e "  Kong Admin API:       ${GREEN}http://localhost:9081${NC}"
fi
if [[ "$WITH_CONSUL" == "true" ]]; then
    echo -e "  Consul UI/API:        ${GREEN}http://localhost:8500${NC}"
fi
if [[ "$WITH_KONGA" == "true" ]]; then
    echo -e "  Konga UI:             ${GREEN}http://localhost:1337${NC}"
fi
echo ""
echo -e "${BLUE}📖 Quick Test Commands:${NC}"
echo -e "  Compare all backends:"
echo -e "    ${YELLOW}curl -X POST http://localhost:9000/agentic/api/query/ask -H 'Content-Type: application/json' -d '{\"question\":\"What is your return policy?\"}'${NC}"
echo ""
echo -e "  Test LangChain (agentic):"
echo -e "    ${YELLOW}curl -X POST http://localhost:8180/agentic/api/query/ask -H 'Content-Type: application/json' -d '{\"question\":\"What is your return policy?\"}'${NC}"
echo ""
echo -e "  Test LangGraph (graph):"
echo -e "    ${YELLOW}curl -X POST http://localhost:8280/graph/api/query/ask -H 'Content-Type: application/json' -d '{\"question\":\"What is your return policy?\"}'${NC}"
echo ""
echo -e "  Test LangChain Retrieval:"
echo -e "    ${YELLOW}curl -X POST http://localhost:8180/retrieval/api/retrieval/query -H 'Content-Type: application/json' -d '{\"tenantId\":\"smoke_tenant\",\"question\":\"What is your return policy?\"}'${NC}"
echo ""
echo -e "  Test LangGraph Retrieval:"
echo -e "    ${YELLOW}curl -X POST http://localhost:8280/retrieval/api/retrieval/query -H 'Content-Type: application/json' -d '{\"tenantId\":\"smoke_tenant\",\"question\":\"What is your return policy?\"}'${NC}"
echo ""
echo -e "${BLUE}🛑 To stop all services:${NC}"
echo -e "  ${YELLOW}${COMPOSE_CMD} ${COMPOSE_FILES[*]} down${NC}"
echo ""
