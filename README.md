# FAQ Assistance - Spring AI RAG Patterns

This workspace contains 5 Java Spring AI microservices implementing major RAG patterns and one React UI:

- spring-ai-agentic (Agentic RAG)
- spring-ai-neo4j-graph (Graph RAG)
- spring-ai-corrective (Corrective RAG)
- spring-ai-multimodal (Multimodal RAG)
- spring-ai-hierarchical (Hierarchical RAG)
- faq-assistance-ui (React frontend)

## Shared FAQ corpus

The indexing pipeline in every service ingests:

- `shared-data/mytechstore-faq.md`

Every backend exposes both pipelines:

- Indexing Pipeline: `POST /api/index/rebuild`
- Query Pipeline: `POST /api/query/ask`

## Quick start

1. Copy env file:

   ```bash
   cp .env.example .env
   ```

2. Set `OPENAI_API_KEY` in `.env`.

3. Build and run with Docker Compose:

   ```bash
   docker compose up --build
   ```

4. Open UI:

   - http://localhost:5173

## Service ports

- Agentic RAG: `http://localhost:8081`
- Graph RAG (Neo4j): `http://localhost:8082`
- Corrective RAG (Guardrails): `http://localhost:8083`
- Multimodal RAG: `http://localhost:8084`
- Hierarchical RAG: `http://localhost:8085`
- React UI: `http://localhost:5173`

## Local build checks

Backend build (all services):

```bash
mvn -q -DskipTests -f spring-ai-agentic/pom.xml package
mvn -q -DskipTests -f spring-ai-neo4j-graph/pom.xml package
mvn -q -DskipTests -f spring-ai-corrective/pom.xml package
mvn -q -DskipTests -f spring-ai-multimodal/pom.xml package
mvn -q -DskipTests -f spring-ai-hierarchical/pom.xml package
```

Frontend build:

```bash
cd faq-assistance-ui
npm install
npm run build
```

## Notes on startup and Dockerfiles

- `docker compose up --build` uses each backend service's `Dockerfile`, which performs the Maven build inside Docker.
- `Dockerfile.native` is a runtime-only option and expects a prebuilt jar already present in `target/`.
- The current backend images use a multi-stage build to compile and then run the packaged Spring Boot jar.
