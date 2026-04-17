# FAQ Assistance - Multi-Stack RAG Microservices

A comprehensive workspace demonstrating five RAG (Retrieval-Augmented Generation) patterns implemented across three separate technology stacks:

- **Spring AI** (Java) - Native-compiled microservices
- **LangChain** (Python) - Traditional Python stack
- **LangGraph** (Python) - Agentic workflow framework

Each stack contains 5 microservices implementing the same RAG patterns, indexed on the **MyTechStore FAQ** corpus.

---

## Quick Start (All Stacks + UI)

### Option 1: Unified Launcher (Recommended)
```bash
# Setup environment
cp .env.example .env
export OPENAI_API_KEY="your-key-here"

# Launch everything with one command
./run-all-stacks.sh
```

This script will:
- ✓ Start all 3 stacks (15 services + 3 Neo4j instances)
- ✓ Wait for services to initialize
- ✓ Rebuild FAQ indexes
- ✓ Run health checks
- ✓ Display UI access URL and quick test commands

### Option 2: Using Make
```bash
cp .env.example .env
export OPENAI_API_KEY="your-key-here"

make build-all      # Build all three stacks
make up-all         # Start all three stacks
make rebuild-indexes # Index FAQ corpus
make test-all       # Validate all endpoints
make down-all       # Stop all stacks
```

### Option 3: Using Master Docker Compose
```bash
cp .env.example .env
export OPENAI_API_KEY="your-key-here"

docker-compose -f docker-compose.master.yml up --build -d
docker-compose -f docker-compose.master.yml down
```

---

## React UI – Compare All Stacks & Patterns

**Access**: http://localhost:5173

### Features

The unified React UI allows you to:

1. **Select Framework**: Spring AI, LangChain, or LangGraph
2. **Select RAG Pattern**: Agentic, Graph, Corrective, Multimodal, or Hierarchical
3. **Compare Mode**: Run the same query across all 3 frameworks simultaneously
4. **Single Mode**: Run on a specific framework with specific pattern
5. **Multimodal Support**: Optional image description field (when multimodal pattern selected)
6. **Export Results**: Download query transcript as JSON

### How to Use

1. **Compare all frameworks** (single pattern):
   - Set Mode to "Compare all backends"
   - Select a RAG pattern (e.g., "Hierarchical RAG")
   - Ask a question
   - See all 3 frameworks' responses side-by-side with latency and metadata

2. **Test specific framework/pattern combo**:
   - Set Mode to "Single backend"
   - Select a framework (Spring AI, LangChain, LangGraph)
   - Select a pattern
   - Ask your question

3. **Multimodal queries**:
   - Select "Multimodal RAG" pattern
   - Provide image context (e.g., "laptop in sealed box")
   - Query will be processed with image signal

### Example Queries

```javascript
// Test return policy across all stacks
"What is your laptop return policy?"

// Test multimodal (with image context)
"Based on the image of this sealed laptop box, what's the return window?"

// Test shipping
"How long does standard delivery take?"

// Test locations
"Where are your branches?"
```

---

## Stack Overview

### Spring AI (Java) – Ports 8081–8085

**Status**: Production-ready, native-compiled images

**Services**:
- `8081` – Agent Orchestrator (agentic RAG)
- `8082` – Neo4j Graph Engine (graph RAG)
- `8083` – Advisor Guardrails (corrective RAG)
- `8084` – Vision Microservice (multimodal RAG)
- `8085` – Structured Retriever (hierarchical RAG)

**UI**: http://localhost:5173

**Quick Commands**:
```bash
make spring-build
make spring-up
make spring-test
make spring-down
```

### LangChain (Python) – Ports 8181–8185

**Status**: Fully functional, modular service architecture

**Services**:
- `8181` – Agentic RAG (multi-step routing)
- `8182` – Graph RAG (Neo4j traversal)
- `8183` – Corrective RAG (explicit retry flow)
- `8184` – Multimodal RAG (image + text)
- `8185` – Hierarchical RAG (section classification)

**Quick Commands**:
```bash
make langchain-build
make langchain-up
make langchain-test
make langchain-down
```

### LangGraph (Python) – Ports 8281–8285

**Status**: New, fully implemented with LangGraph state graphs

**Services**:
- `8281` – Agentic RAG (route → retrieve → answer)
- `8282` – Graph RAG (plan → traverse → answer)
- `8283` – Corrective RAG (retrieve → evaluate → retry/answer)
- `8284` – Multimodal RAG (extract → validate → branch → answer)
- `8285` – Hierarchical RAG (classify → retrieve → answer)

**Quick Commands**:
```bash
make langgraph-build
make langgraph-up
make langgraph-test
make langgraph-down
```

---

## Shared FAQ Corpus

All services index the same MyTechStore FAQ:

**File**: `shared-data/mytechstore-faq.md`

**Topics**:
- Products and Availability
- Pricing and Payments
- Shipping and Delivery
- Returns and Warranty
- Branches and Support

**Total**: ~100 indexed documents after parsing

---

## 5 RAG Patterns

### 1. Agentic RAG (Routing)
Dynamically route queries to specialized retrieval paths.

- **Spring**: Port 8081
- **LangChain**: Port 8181
- **LangGraph**: Port 8281

### 2. Graph RAG
Navigate structured knowledge graphs for precise retrieval.

- **Spring**: Port 8082
- **LangChain**: Port 8182
- **LangGraph**: Port 8282

### 3. Corrective RAG
Evaluate retrieval quality and auto-retry if needed.

- **Spring**: Port 8083
- **LangChain**: Port 8183
- **LangGraph**: Port 8283

### 4. Multimodal RAG
Blend image and text-based retrieval.

- **Spring**: Port 8084
- **LangChain**: Port 8184
- **LangGraph**: Port 8284

### 5. Hierarchical RAG
Classify queries into FAQ sections.

- **Spring**: Port 8085
- **LangChain**: Port 8185
- **LangGraph**: Port 8285

---

## API Endpoints

All services expose:

```bash
# Health check
GET /actuator/health

# Rebuild FAQ index
POST /api/index/rebuild

# Query FAQ
POST /api/query/ask
```

**Example Query**:
```bash
curl -X POST http://localhost:8281/api/query/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"What is your laptop return policy?"}'
```

---

## Environment Setup

```bash
cp .env.example .env
export OPENAI_API_KEY="sk-..."
```

---

## Docker Infrastructure

- **Neo4j (Spring)**: `localhost:7474`, `localhost:7687`
- **Neo4j (LangChain)**: `localhost:7475`, `localhost:7688`
- **Neo4j (LangGraph)**: `localhost:7476`, `localhost:7689`
- **UI**: `localhost:5173`

---

## Testing

```bash
# Test all stacks
make test-all

# Test specific stack
make spring-test
make langchain-test
make langgraph-test

# Rebuild all indexes
make rebuild-indexes
```

---

## Project Structure

```
FAQ-Assistance/
├── Makefile
├── docker-compose.yml              # Spring AI
├── docker-compose.langchain.yml    # LangChain
├── docker-compose.langgraph.yml    # LangGraph
├── shared-data/
│   └── mytechstore-faq.md
├── spring-ai-{agentic,neo4j-graph,...}/
├── langchain-{agentic,neo4j-graph,...}/
├── langgraph-{agentic,neo4j-graph,...}/
└── faq-assistance-ui/
```

---

## Advanced Builds

### Spring AI only:
```bash
mvn -q -DskipTests -f spring-ai-agentic/pom.xml package
mvn -q -DskipTests -f spring-ai-neo4j-graph/pom.xml package
mvn -q -DskipTests -f spring-ai-corrective/pom.xml package
mvn -q -DskipTests -f spring-ai-multimodal/pom.xml package
mvn -q -DskipTests -f spring-ai-hierarchical/pom.xml package
```

### Frontend:
```bash
cd faq-assistance-ui
npm install && npm run build
```

---

## Troubleshooting

```bash
# Check logs
docker compose -f docker-compose.langgraph.yml logs langgraph-agentic

# Rebuild
docker compose -f docker-compose.langgraph.yml up --build -d

# Clean slate
docker compose -f docker-compose.langgraph.yml down -v
docker system prune -a
```

---

## Next Steps

1. Compare RAG patterns by querying each stack
2. Extend FAQ corpus in `shared-data/mytechstore-faq.md`
3. Integrate UI with LangChain and LangGraph endpoints
4. Deploy to production with Kubernetes
