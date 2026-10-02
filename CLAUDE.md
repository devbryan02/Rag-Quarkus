# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

RAG (Retrieval-Augmented Generation) service built with Quarkus 3.37 + LangChain4j. Answers natural-language questions strictly from indexed documents (PDF, DOCX, TXT, MD).

- **LLM:** `openai/gpt-oss-120b` via Groq API (OpenAI-compatible), overridable with the `GROQ_MODEL` env var
- **Embeddings:** AllMiniLmL6V2, local/ONNX, 384 dims, no network calls

## Commands

```bash
# Dev mode with live reload (Dev UI at http://localhost:8080/q/dev/)
./gradlew quarkusDev

# Run tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.bryan.ExampleResourceTest"

# Build (JAR)
./gradlew build
java -jar build/quarkus-app/quarkus-run.jar

# Über-JAR
./gradlew build -Dquarkus.package.jar.type=uber-jar

# Native (GraalVM)
./gradlew build -Dquarkus.native.enabled=true
```

Requires `.env` with `GROQ_API_KEY` and `API_KEY` (used by `app.api-key` / Groq client config). Optional `GROQ_MODEL` overrides the chat model (default `openai/gpt-oss-120b`).

Example request:
```bash
curl -X POST http://localhost:8080/chat \
  -H "Content-Type: application/json" \
  -H "X-API-Key: tu_api_key" \
  -d '{"question": "..."}'
```

## Architecture

Request flow:
```
POST /chat (X-API-Key required)
  → ApiKeyFilter (JAX-RS filter, only intercepts /chat paths)
  → ChatResource (@RateLimit 10/min, @Valid ChatRequest)
  → RagAssistant (LangChain4j @RegisterAiService — implementation generated at build time)
      - @SystemMessage forces answers to use only retrieved document context
      - RetrievalAugmentor supplied by RetrieverProducer:
          EmbeddingStoreContentRetriever(EmbeddingModel, EmbeddingStore, maxResults=3, minScore=0.0)
  → Groq API (${GROQ_MODEL:openai/gpt-oss-120b}, temp=0.3)
  → ChatResponse { answer }
```

Startup ingestion (`DocumentIngestor`, `@PostConstruct`):
1. Reads files from `rag.ingestion.documents-path` (`src/main/resources/documents/`)
2. `DocumentLoaderFactory` picks a parser by extension (`.pdf` → PDFBox, `.docx`/`.doc` → POI, `.txt`/`.md` → plain text)
3. `DocumentSplitters.recursive(chunkSize, chunkOverlap)` splits into chunks (default 300 chars, 30 overlap)
4. Embeds locally with AllMiniLmL6V2 and stores in `InMemoryEmbeddingStore` (in-memory — lost on restart, re-indexed automatically on every startup)

Package layout (`src/main/java/com/bryan/`): feature-based, not layer-based.
- `features/chat/` — `ChatResource` (endpoint), `RagAssistant` (AI service interface), `records/` (request/response DTOs)
- `features/ingestion/` — `DocumentIngestor`, `DocumentLoaderFactory`, `IngestionConfig` (`@ConfigMapping rag.ingestion.*`)
- `features/rerieval/` (note: typo in package name, kept for consistency — don't "fix" it in isolation) — `RetrieverProducer` (`Supplier<RetrievalAugmentor>` CDI bean), `RetrievalConfig` (`@ConfigMapping rag.retrieval.*`)
- `shared/embedings/` — `EmbeddingStoreProducer` (CDI producers for `EmbeddingStore`/`EmbeddingModel`)
- `shared/exceptions/` — `GlobalExceptionHandlers` (maps `RateLimitException` → 429, generic `Exception` → 500), `ErrorResponse` record
- `shared/security/` — `ApiKeyFilter`

Config is exposed via typed `@ConfigMapping` interfaces (`IngestionConfig`, `RetrievalConfig`) rather than raw `@ConfigProperty` injection — follow this pattern for new config groups.

Known gap: tests in `src/test` and `src/native-test` are stale (`ExampleResourceTest`/`ExampleResourceIT` test a `/hello` endpoint that no longer exists); real coverage for `/chat` is still missing.

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).
