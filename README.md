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

# Launch everything with one command (fast path, no index rebuild)
./run-all-stacks.sh

# Optional: rebuild only selected indexes
./run-all-stacks.sh --rebuild spring
./run-all-stacks.sh --rebuild-services spring-agentic,langchain-agentic

# Optional overlays
./run-all-stacks.sh --with-kong
./run-all-stacks.sh --with-kong --with-consul --with-kong-consul
./run-all-stacks.sh --with-kong --with-konga
```

This script will:
- ✓ Start all 3 stacks (15 services + 3 Neo4j instances)
- ✓ Start FAQ ingestion services (ChromaDB + Spring ingestion API)
- ✓ Wait for services to initialize
- ✓ Skip index rebuild by default for faster startup
- ✓ Allow optional index rebuild by stack or specific service
- ✓ Run health checks
- ✓ Display UI access URL and quick test commands

### Quickstart Rebuild Controls

```bash
# Default (fast): no index rebuild
./quickstart.sh

# Rebuild by stack
./quickstart.sh --rebuild spring
./quickstart.sh --rebuild langchain
./quickstart.sh --rebuild langgraph
./quickstart.sh --rebuild all

# Rebuild only specific services (IDs or ports)
./quickstart.sh --rebuild-services spring-agentic,langgraph-neo4j
./quickstart.sh --rebuild-services 8081,8282

# Optional overlays
./quickstart.sh --with-kong
./quickstart.sh --with-kong --with-consul --with-kong-consul
./quickstart.sh --with-kong --with-konga
```

Supported service IDs:
- spring-agentic, spring-neo4j, spring-corrective, spring-multimodal, spring-hierarchical
- langchain-agentic, langchain-neo4j, langchain-corrective, langchain-multimodal, langchain-hierarchical
- langgraph-agentic, langgraph-neo4j, langgraph-corrective, langgraph-multimodal, langgraph-hierarchical

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

docker compose -f docker-compose.master.yml up --build -d
docker compose -f docker-compose.master.yml down
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

### FAQ Ingestion (Spring AI + Chroma) – Ports 9000 and 8000

**Status**: Upload and indexing validated (April 18, 2026)

**Services**:
- `8000` – ChromaDB (`chroma-faq`)
- `9000` – Spring AI FAQ ingestion API (`faq-ingestion`)

**Primary Endpoints**:
- `GET /api/faq-ingestion/health`
- `POST /api/faq-ingestion/customers`
- `POST /api/faq-ingestion/documents/upload`
- `GET /api/faq-ingestion/customers/{customerId}/documents`
- `POST /api/faq-ingestion/query`

**Quick Commands**:
```bash
docker compose -f docker-compose.master.yml up -d chroma-faq faq-ingestion
curl -s http://localhost:9000/api/faq-ingestion/health | jq .
```

### FAQ Ingestion Update (April 18, 2026)

#### What Changed

1. Chroma service configuration was aligned with the pinned image behavior.
2. Ingestion service now forces HTTP/1.1 for Chroma requests.
3. Collection operations now resolve and use collection UUID for add/query/delete/count routes.
4. Indexing path now fails fast when add-to-Chroma fails, preventing false "COMPLETED" states.

#### Why These Choices Were Chosen

1. **Pinned Chroma image (`0.5.23`)** was kept for API stability across local runs and compose stacks.
2. **Deprecated Chroma env settings were removed** because they caused startup failures in this image line.
3. **UUID-based collection routes were adopted** because Chroma v1 add/query/delete/count expect collection ID, not collection name.
4. **HTTP/1.1 was forced in Java HttpClient** because it removed intermittent malformed request failures observed during create/add calls.
5. **Fail-fast indexing was enforced** so ingestion status accurately reflects real indexing outcomes.

#### Current Known Limitation

- Document upload and indexing are now working.
- Query can still return empty sources until query-result parsing and embedding/query payload handling are completed in the ingestion service.

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

**Multimodal with optional image upload**:
```bash
curl -X POST http://localhost:8284/api/query/ask-with-image \
   -F 'question=Can I return this damaged monitor?' \
   -F 'imageDescription=Photo shows a cracked corner and dead pixels' \
   -F 'image=@/absolute/path/to/monitor.jpg'
```

When an image is uploaded, the service performs vision extraction and injects extracted visual signals into the RAG context.
If no file is provided, continue using JSON payload on `/api/query/ask`.

---

## Environment Setup

```bash
cp .env.example .env
export OPENAI_API_KEY="sk-..."

# Optional vision model override for Python multimodal services
export OPENAI_VISION_MODEL="gpt-4o-mini"

# Optional vision model override for Spring multimodal service
export OPENAI_VISION_MODEL="gpt-4o-mini"
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
├── docker-compose.faq-ingestion.yml # FAQ ingestion (standalone)
├── shared-data/
│   └── mytechstore-faq.md
├── spring-ai-{agentic,neo4j-graph,...}/
├── spring-ai-faq-ingestion/
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
