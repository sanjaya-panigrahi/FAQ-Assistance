#!/bin/bash

# FAQ Assistance - Quick Start Script
# Start all three RAG stacks, rebuild indexes, and run smoke tests

set -e

RESET='\033[0m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'

echo -e "${BLUE}╔════════════════════════════════════════════════════════════╗${RESET}"
echo -e "${BLUE}║  FAQ Assistance - Multi-Stack RAG Quick Start             ║${RESET}"
echo -e "${BLUE}╚════════════════════════════════════════════════════════════╝${RESET}"
echo ""

# Check for required tools
command -v docker &> /dev/null || { echo "❌ Docker not found"; exit 1; }
command -v curl &> /dev/null || { echo "❌ curl not found"; exit 1; }

# Setup environment
echo -e "${YELLOW}📋 Setting up environment files...${RESET}"
cp -n .env.example .env 2>/dev/null || true
cp -n .env.langchain.example .env.langchain 2>/dev/null || true
cp -n .env.langgraph.example .env.langgraph 2>/dev/null || true
echo -e "${GREEN}✓ Environment files ready${RESET}"
echo ""

# Start all stacks
echo -e "${YELLOW}🐳 Building and starting all stacks...${RESET}"
make build-all > /dev/null 2>&1 &
PID=$!
echo "   Building in progress (PID: $PID)..."
wait $PID
echo -e "${GREEN}✓ All stacks built${RESET}"
echo ""

echo -e "${YELLOW}🚀 Starting services...${RESET}"
make up-all
echo ""

# Wait for services to be ready
echo -e "${YELLOW}⏳ Waiting for services to be ready (30s)...${RESET}"
sleep 30
echo ""

# Rebuild indexes
echo -e "${YELLOW}📚 Rebuilding FAQ indexes...${RESET}"
make rebuild-indexes > /dev/null 2>&1
echo -e "${GREEN}✓ All indexes rebuilt${RESET}"
echo ""

# Run smoke tests
echo -e "${YELLOW}✅ Running smoke tests...${RESET}"
echo ""

test_endpoint() {
    local port=$1
    local name=$2
    local response=$(curl -s --max-time 10 http://localhost:$port/actuator/health 2>/dev/null || echo "FAILED")
    
    if echo "$response" | grep -q '"status":"UP"'; then
        echo -e "  ${GREEN}✓${RESET} $name (port $port)"
        return 0
    else
        echo -e "  ${YELLOW}⚠${RESET} $name (port $port) - check logs"
        return 1
    fi
}

echo "Spring AI Stack (Java, ports 8081-8085):"
test_endpoint 8081 "Agent Orchestrator"
test_endpoint 8082 "Neo4j Graph Engine"
test_endpoint 8083 "Advisor Guardrails"
test_endpoint 8084 "Vision Microservice"
test_endpoint 8085 "Structured Retriever"
echo ""

echo "LangChain Stack (Python, ports 8181-8185):"
test_endpoint 8181 "Agentic RAG"
test_endpoint 8182 "Graph RAG"
test_endpoint 8183 "Corrective RAG"
test_endpoint 8184 "Multimodal RAG"
test_endpoint 8185 "Hierarchical RAG"
echo ""

echo "LangGraph Stack (Python, ports 8281-8285):"
test_endpoint 8281 "Agentic RAG"
test_endpoint 8282 "Graph RAG"
test_endpoint 8283 "Corrective RAG"
test_endpoint 8284 "Multimodal RAG"
test_endpoint 8285 "Hierarchical RAG"
echo ""

# Show endpoints
echo -e "${BLUE}════════════════════════════════════════════════════════════${RESET}"
echo -e "${GREEN}🎉 All systems ready!${RESET}"
echo ""
echo -e "${BLUE}📍 Access URLs:${RESET}"
echo "   React UI:      http://localhost:5173"
echo ""
echo -e "${BLUE}🧪 Test a query (Spring AI):${RESET}"
echo "   curl -X POST http://localhost:8081/api/query/ask \\"
echo "     -H 'Content-Type: application/json' \\"
echo "     -d '{\"question\":\"What is your laptop return policy?\"}'"
echo ""
echo -e "${BLUE}📖 View Makefile targets:${RESET}"
echo "   make help"
echo ""
echo -e "${BLUE}🛑 Shutdown all stacks:${RESET}"
echo "   make down-all"
echo ""
echo -e "${BLUE}════════════════════════════════════════════════════════════${RESET}"
