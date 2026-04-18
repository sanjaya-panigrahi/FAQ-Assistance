#!/bin/bash

# Color codes
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

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
elif command -v docker-compose &> /dev/null; then
    COMPOSE_CMD="docker-compose"
else
    echo -e "${RED}✗ Docker Compose not found. Please install Docker Compose.${NC}"
    exit 1
fi
echo -e "${GREEN}✓ Docker Compose found (${COMPOSE_CMD})${NC}"

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
echo -e "${BLUE}Starting all stacks (Spring AI, LangChain, LangGraph)...${NC}"
$COMPOSE_CMD -f docker-compose.master.yml up --build -d

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
echo -e "${BLUE}Health Check - Spring AI Stack (ports 8081-8085):${NC}"
for port in 8081 8082 8083 8084 8085; do
    response=$(curl -s --max-time 5 http://localhost:$port/actuator/health 2>/dev/null)
    if echo "$response" | grep -q "UP"; then
        echo -e "${GREEN}✓ Port $port: UP${NC}"
    else
        echo -e "${RED}✗ Port $port: DOWN${NC}"
    fi
done
echo ""

echo -e "${BLUE}Health Check - LangChain Stack (ports 8181-8185):${NC}"
for port in 8181 8182 8183 8184 8185; do
    response=$(curl -s --max-time 5 http://localhost:$port/actuator/health 2>/dev/null)
    if echo "$response" | grep -q "UP"; then
        echo -e "${GREEN}✓ Port $port: UP${NC}"
    else
        echo -e "${RED}✗ Port $port: DOWN${NC}"
    fi
done
echo ""

echo -e "${BLUE}Health Check - LangGraph Stack (ports 8281-8285):${NC}"
for port in 8281 8282 8283 8284 8285; do
    response=$(curl -s --max-time 5 http://localhost:$port/actuator/health 2>/dev/null)
    if echo "$response" | grep -q "UP"; then
        echo -e "${GREEN}✓ Port $port: UP${NC}"
    else
        echo -e "${RED}✗ Port $port: DOWN${NC}"
    fi
done
echo ""

echo -e "${BLUE}Health Check - FAQ Ingestion Stack:${NC}"
response=$(curl -s --max-time 5 http://localhost:8000/api/v1/heartbeat 2>/dev/null)
if [ -n "$response" ]; then
    echo -e "${GREEN}✓ ChromaDB (port 8000): UP${NC}"
else
    echo -e "${RED}✗ ChromaDB (port 8000): DOWN${NC}"
fi
response=$(curl -s --max-time 5 http://localhost:9000/api/faq-ingestion/health 2>/dev/null)
if echo "$response" | grep -qE '"status":"(UP|DEGRADED)"'; then
    echo -e "${GREEN}✓ FAQ Ingestion (port 9000): UP${NC}"
else
    echo -e "${RED}✗ FAQ Ingestion (port 9000): DOWN${NC}"
fi
echo ""

# Rebuild indexes
echo -e "${BLUE}Rebuilding FAQ indexes...${NC}"
for port in 8081 8082 8083 8084 8085 8181 8182 8183 8184 8185 8281 8282 8283 8284 8285; do
    curl -s -X POST http://localhost:$port/api/index/rebuild > /dev/null 2>&1
done
echo -e "${GREEN}✓ Indexes rebuilt${NC}"
echo ""

# Summary
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}✓ All stacks are running!${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo -e "${BLUE}📊 Stack Overview:${NC}"
echo -e "  Spring AI   (Ports 8081-8085): Agentic, Graph, Corrective, Multimodal, Hierarchical"
echo -e "  LangChain   (Ports 8181-8185): Agentic, Graph, Corrective, Multimodal, Hierarchical"
echo -e "  LangGraph      (Ports 8281-8285): Agentic, Graph, Corrective, Multimodal, Hierarchical"
echo -e "  FAQ Ingestion  (Ports 8000, 9000): ChromaDB, Ingestion API"
echo ""
echo -e "${BLUE}🌐 UI Access:${NC}"
echo -e "  Main UI:              ${GREEN}http://localhost:5173${NC}"
echo -e "  FAQ Ingestion API:    ${GREEN}http://localhost:9000/api/faq-ingestion/health${NC}"
echo -e "  H2 Console:           ${GREEN}http://localhost:9000/h2-console${NC}"
echo ""
echo -e "${BLUE}📖 Quick Test Commands:${NC}"
echo -e "  Compare all backends:"
echo -e "    ${YELLOW}curl -X POST http://localhost:8081/api/query/ask -H 'Content-Type: application/json' -d '{\"question\":\"What is your return policy?\"}'${NC}"
echo ""
echo -e "  Test LangChain (agentic):"
echo -e "    ${YELLOW}curl -X POST http://localhost:8181/api/query/ask -H 'Content-Type: application/json' -d '{\"question\":\"What is your return policy?\"}'${NC}"
echo ""
echo -e "  Test LangGraph (neo4j-graph):"
echo -e "    ${YELLOW}curl -X POST http://localhost:8282/api/query/ask -H 'Content-Type: application/json' -d '{\"question\":\"What is your return policy?\"}'${NC}"
echo ""
echo -e "${BLUE}🛑 To stop all services:${NC}"
echo -e "  ${YELLOW}${COMPOSE_CMD} -f docker-compose.master.yml down${NC}"
echo ""
