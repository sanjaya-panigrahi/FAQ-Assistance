#!/bin/bash
# run-mcp-stack.sh — Start the full FAQ-Assistance stack with MCP servers enabled
#
# This script:
#   1. Starts the core infrastructure (docker-compose.master.yml)
#   2. Starts the 6 MCP servers (docker-compose.mcp.yml)
#   3. Enables USE_MCP=true so framework services route through MCP
#
# Usage:
#   ./run-mcp-stack.sh          # Start everything with MCP enabled
#   ./run-mcp-stack.sh --no-mcp # Start everything WITHOUT MCP (direct mode)
#   ./run-mcp-stack.sh --down   # Stop everything

set -euo pipefail

cd "$(dirname "$0")"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

COMPOSE_MASTER="docker-compose.master.yml"
COMPOSE_MCP="docker-compose.mcp.yml"

case "${1:-}" in
  --down)
    echo -e "${YELLOW}Stopping all services (master + MCP)...${NC}"
    docker compose -f "$COMPOSE_MASTER" -f "$COMPOSE_MCP" down
    echo -e "${GREEN}All services stopped.${NC}"
    exit 0
    ;;
  --no-mcp)
    echo -e "${YELLOW}Starting in DIRECT mode (no MCP servers)...${NC}"
    export USE_MCP=false
    docker compose -f "$COMPOSE_MASTER" up -d
    echo -e "${GREEN}Direct mode started. Framework services use direct clients.${NC}"
    exit 0
    ;;
  *)
    echo -e "${GREEN}Starting in MCP mode...${NC}"
    export USE_MCP=true
    ;;
esac

echo ""
echo "================================================================"
echo "  FAQ-Assistance — MCP Mode"
echo "================================================================"
echo ""

# Step 1: Start infrastructure + framework services
echo -e "${YELLOW}[1/3] Starting core infrastructure...${NC}"
docker compose -f "$COMPOSE_MASTER" up -d \
  neo4j-unified redis-cache chroma-faq analytics-mysql zipkin

echo -e "${YELLOW}[2/3] Starting MCP servers...${NC}"
docker compose -f "$COMPOSE_MASTER" -f "$COMPOSE_MCP" up -d \
  mcp-chromadb mcp-neo4j mcp-llm-gateway mcp-web-search mcp-cache mcp-analytics

# Wait for MCP servers to be healthy
echo -e "${YELLOW}Waiting for MCP servers to become healthy...${NC}"
sleep 10

echo -e "${YELLOW}[3/3] Starting framework services with USE_MCP=true...${NC}"
USE_MCP=true docker compose -f "$COMPOSE_MASTER" -f "$COMPOSE_MCP" up -d

echo ""
echo "================================================================"
echo -e "  ${GREEN}MCP Stack is running!${NC}"
echo "================================================================"
echo ""
echo "Framework Services (MCP-enabled):"
echo "  LangChain Unified:  http://localhost:8180"
echo "  LangGraph Unified:  http://localhost:8280"
echo "  Spring AI Unified:  http://localhost:9000"
echo ""
echo "MCP Servers:"
echo "  ChromaDB MCP:       http://localhost:8301"
echo "  Neo4j MCP:          http://localhost:8302"
echo "  LLM Gateway MCP:    http://localhost:8303"
echo "  Web Search MCP:     http://localhost:8304"
echo "  Cache MCP:          http://localhost:8305"
echo "  Analytics MCP:      http://localhost:8306"
echo ""
echo "Infrastructure:"
echo "  ChromaDB:           http://localhost:8000"
echo "  Neo4j Browser:      http://localhost:7474"
echo "  Redis:              localhost:6379"
echo "  Zipkin:             http://localhost:9411"
echo "  Dozzle (logs):      http://localhost:9999"
echo ""
echo "To run MCP integration tests:"
echo "  pytest mcp-servers/tests/ -v"
echo ""
echo "To switch back to direct mode:"
echo "  ./run-mcp-stack.sh --no-mcp"
echo ""
