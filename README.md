# FAQ Assistance - Multi-Stack RAG Microservices

---

## 1. Problem Statement

Customer-facing support teams at retail and technology companies manage thousands of FAQ queries daily. Traditional keyword-based search systems struggle to return accurate, contextually relevant answers — especially when questions are phrased differently from the stored FAQ text, require multi-step reasoning, combine image and text signals, or span multiple knowledge categories.

**This project addresses the following core problems:**

- **Inconsistent answer quality**: Keyword search cannot understand semantic intent, leading to irrelevant or incomplete answers.
- **No comparison baseline**: Teams adopting LLM-based retrieval lack a consistent framework to compare different RAG strategies side-by-side.
- **Technology fragmentation**: Spring AI (Java), LangChain (Python), and LangGraph (Python) each have different strengths but are rarely benchmarked against identical workloads.
- **Pattern selection uncertainty**: It is unclear whether Agentic, Graph, Corrective, Multimodal, or Hierarchical RAG is best suited for a given query type without running them against real data.

**Solution**: A unified workspace implementing five RAG patterns across three technology stacks, all indexed on the same **MyTechStore FAQ** corpus. This enables direct, apples-to-apples comparison of retrieval accuracy, latency, and answer quality — giving engineering teams the evidence they need to make informed architecture decisions.

---

## 2. Prerequisites

| Requirement | Minimum Version | Notes |
|-------------|----------------|-------|
| Docker | 24.x | Required for all services |
| Docker Compose | v2.x (plugin) | `docker compose` (not `docker-compose`) |
| OpenAI API Key | — | Set as `OPENAI_API_KEY` env variable |
| Java (JDK) | 21 LTS | Required only for local Spring AI builds |
| Maven | 3.9.x | Required only for local Spring AI builds |
| Python | 3.11+ | Required only for local LangChain/LangGraph runs |
| Node.js | 18+ | Required only for local UI development |
| curl | any | Used by smoke tests and Makefile health checks |
| make | any | Used to run orchestration targets |

**Verify tools before starting:**
```bash
make check-tools
```

**Bootstrap environment files:**
```bash
make setup-env
# Then edit .env and set: OPENAI_API_KEY=sk-...
```

---

## 3. Project Structure

```
FAQ-Assistance/
├── Makefile                          # Primary operator interface (build/up/test/clean)
├── docker-compose.master.yml         # Full-stack unified compose
├── docker-compose.yml                # Spring AI stack
├── docker-compose.langchain.yml      # LangChain stack
├── docker-compose.langgraph.yml      # LangGraph stack
├── docker-compose.kafka.yml          # Kafka + Zookeeper (event bus)
├── docker-compose.resources.yml      # Shared infrastructure (Redis, ChromaDB, Neo4j)
├── docker-compose.kong.yml           # Kong API gateway
├── docker-compose.elk.yml            # ELK logging overlay (optional)
├── .env.example                      # Environment template
├── shared-data/
│   └── mytechstore-faq.md            # Shared FAQ corpus (indexed by all services)
├── shared-patterns/
│   └── patterns_config.yaml          # Shared pattern registry (Python services)
├── spring-ai-agentic/                # Spring AI – Agentic RAG (port 8081)
├── spring-ai-neo4j-graph/            # Spring AI – Graph RAG (port 8082)
├── spring-ai-corrective/             # Spring AI – Corrective RAG (port 8083)
├── spring-ai-multimodal/             # Spring AI – Multimodal RAG (port 8084)
├── spring-ai-hierarchical/           # Spring AI – Hierarchical RAG (port 8085)
├── spring-ai-faq-ingestion/          # Spring AI – FAQ ingestion API (port 9000)
├── spring-ai-faq-retrieval/          # Spring AI – Retrieval microservice (port 9010)
├── langchain-agentic/                # LangChain – Agentic RAG (port 8181)
├── langchain-neo4j-graph/            # LangChain – Graph RAG (port 8182)
├── langchain-corrective/             # LangChain – Corrective RAG (port 8183)
├── langchain-multimodal/             # LangChain – Multimodal RAG (port 8184)
├── langchain-hierarchical/           # LangChain – Hierarchical RAG (port 8185)
├── langchain-retrieval-service/      # LangChain – Retrieval microservice (port 8190)
├── langgraph-agentic/                # LangGraph – Agentic RAG (port 8281)
├── langgraph-neo4j-graph/            # LangGraph – Graph RAG (port 8282)
├── langgraph-corrective/             # LangGraph – Corrective RAG (port 8283)
├── langgraph-multimodal/             # LangGraph – Multimodal RAG (port 8284)
├── langgraph-hierarchical/           # LangGraph – Hierarchical RAG (port 8285)
├── langgraph-retrieval-service/      # LangGraph – Retrieval microservice (port 8290)
├── faq-assistance-ui/                # React UI – compare all stacks (port 5173)
├── kong/                             # Kong declarative config (kong.yml)
├── consul/                           # Consul service discovery config
├── k8s/                              # Kubernetes manifests and deploy script
└── helm/                             # Helm charts
```

---

## 4. Technology Used in Each Project

### Spring AI Services (`spring-ai-*`)
| Layer | Technology |
|-------|-----------|
| Language | Java 21 |
| Framework | Spring Boot 3.x, Spring AI |
| LLM Integration | OpenAI (via Spring AI OpenAI starter) |
| Vector Store | ChromaDB (via Spring AI Chroma integration) |
| Graph Database | Neo4j (via Spring Data Neo4j) |
| HTTP Client | Spring WebFlux `WebClient` |
| Caching | Spring Cache + Redis (via `spring-boot-starter-data-redis`) |
| Resilience | Resilience4j circuit breaker |
| Build | Maven 3.9, multi-stage Docker build |
| Testing | JUnit 5, Mockito |
| Async tasks | `@Async` + Redis-backed `TaskService` (neo4j-graph) |
| Event bus | Apache Kafka (`spring-kafka`) – optional overlay |

### LangChain Services (`langchain-*`)
| Layer | Technology |
|-------|-----------|
| Language | Python 3.11 |
| Framework | FastAPI, Uvicorn |
| LLM Integration | OpenAI (via `langchain-openai`) |
| Orchestration | LangChain (`langchain`, `langchain-core`) |
| Vector Store | ChromaDB (`langchain-chroma`, `chromadb`) |
| Graph Database | Neo4j (`langchain-neo4j`) |
| Caching | Redis (`redis-py`) |
| Async tasks | Celery + Redis broker (neo4j-graph worker) |
| Resilience | `tenacity` retry library |
| Auth | JWT (`python-jose`) |
| Build | Docker multi-stage, `requirements.txt` |

### LangGraph Services (`langgraph-*`)
| Layer | Technology |
|-------|-----------|
| Language | Python 3.11 |
| Framework | FastAPI, Uvicorn |
| LLM Integration | OpenAI (via `langchain-openai`) |
| Orchestration | LangGraph (`langgraph`) state graph workflows |
| Vector Store | ChromaDB (`langchain-chroma`, `chromadb`) |
| Graph Database | Neo4j (`langchain-neo4j`) |
| Caching | Redis (`redis-py`) |
| Async tasks | Celery + Redis broker (neo4j-graph worker) |
| Resilience | `tenacity` retry library |
| Auth | JWT (`python-jose`) |
| Build | Docker multi-stage, `requirements.txt` |

### FAQ Ingestion Service (`spring-ai-faq-ingestion`)
| Layer | Technology |
|-------|-----------|
| Language | Java 21 |
| Framework | Spring Boot 3.x, Spring AI |
| Storage | PostgreSQL (JPA/Hibernate) for document metadata |
| Vector Store | ChromaDB (HTTP client, UUID-based collection API) |
| File Parsing | Apache Tika, Apache PDFBox |
| LLM | OpenAI (customer detection from document content) |
| Build | Maven 3.9, multi-stage Docker build |

### React UI (`faq-assistance-ui`)
| Layer | Technology |
|-------|-----------|
| Language | TypeScript |
| Framework | React 18, Vite |
| Styling | Tailwind CSS |
| HTTP | Fetch API |
| Build | Node.js 18+, `npm run build` → served by Nginx |

### Shared Infrastructure
| Component | Technology | Port(s) |
|-----------|-----------|---------|
| Vector database | ChromaDB 0.5.23 | 8000 |
| Graph database (Spring) | Neo4j 5.x | 7474 / 7687 |
| Graph database (LangChain) | Neo4j 5.x | 7475 / 7688 |
| Graph database (LangGraph) | Neo4j 5.x | 7476 / 7689 |
| Cache / Celery broker | Redis 7 | 6379 |
| API Gateway | Kong 3.x (DB-less) | 9080 / 9443 |
| Event bus | Apache Kafka + Zookeeper | 9092 |
| Log aggregation | Elasticsearch + Logstash + Kibana | 9200 / 5601 |
| Container logs | Dozzle | 9999 |

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
./run-all-stacks.sh --with-elk
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
./quickstart.sh --with-elk
```

Default local logging is intentionally lightweight now:
- **Dozzle** at `http://localhost:9999` for live Docker container logs
- **ELK** is available only when explicitly requested with `--with-elk`

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

# Optional full ELK overlay
docker compose -f docker-compose.master.yml -f docker-compose.elk.yml up --build -d
```

### Option 4: Domain-Grouped Compose Stacks
```bash
# 1) Start shared/common infra first
docker compose -f docker-compose.common.yml up -d

# 2) Start one or more functional groups
docker compose -f docker-compose.common.yml -f docker-compose.rag-pipeline.yml up -d
docker compose -f docker-compose.common.yml -f docker-compose.agentic.yml up -d
docker compose -f docker-compose.common.yml -f docker-compose.graph.yml up -d
docker compose -f docker-compose.common.yml -f docker-compose.corrective.yml up -d
docker compose -f docker-compose.common.yml -f docker-compose.multimodal.yml up -d
docker compose -f docker-compose.common.yml -f docker-compose.hierarchical.yml up -d
docker compose -f docker-compose.common.yml -f docker-compose.retrieval.yml up -d
docker compose -f docker-compose.common.yml -f docker-compose.ingestion.yml up -d

# 3) Start everything by group composition
docker compose \
   -f docker-compose.common.yml \
   -f docker-compose.rag-pipeline.yml \
   -f docker-compose.agentic.yml \
   -f docker-compose.ingestion.yml up -d
```

Grouped files:
- `docker-compose.common.yml` (Consul, Kong, Konga, Neo4j, Chroma, Redis, analytics, UI)
- `docker-compose.rag-pipeline.yml` (Spring AI + LangChain + LangGraph retrieval services)
- `docker-compose.agentic.yml` (Spring AI + LangChain + LangGraph agentic services)
- `docker-compose.graph.yml` (Spring AI + LangChain + LangGraph graph services)
- `docker-compose.corrective.yml` (Spring AI + LangChain + LangGraph corrective services)
- `docker-compose.multimodal.yml` (Spring AI + LangChain + LangGraph multimodal services)
- `docker-compose.hierarchical.yml` (Spring AI + LangChain + LangGraph hierarchical services)
- `docker-compose.retrieval.yml` (Spring AI + LangChain + LangGraph retrieval services)
- `docker-compose.ingestion.yml` (FAQ ingestion service)

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
