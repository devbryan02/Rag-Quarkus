# Document scan implementation plan

Use superpowers:executing-plans task by task; final independent review.

Goal: add manual recursive incremental indexing with atomic updates.
Architecture: walker -> shared file indexer -> transactional PostgreSQL repository. Existing model and retriever remain.
Tech stack: Java 21, Quarkus, LangChain4j, ONNX, JDBC, PostgreSQL/pgvector.
Spec: ../specs/2026-10-31-document-scan-design.md

## Constraints
Preserve existing user edit in EmbeddingStoreProducer.java. No destructive deletion of old tables or volumes. Single instance. No automatic file deletion synchronization. No automatic startup ingestion.

## Tasks
- [x] 1. Add API/integration tests for recursion, repeat scans, modifications and failure preservation; observe failure before implementation.
- [x] 2. Implement document registry and atomic replacement; integrate snapshots, walker, API, upload lock and manual startup policy. Run tests.
- [x] 3. Back up volume documents, mount host directory, switch to new table; extend functional collection and usage documentation.
- [x] 4. Run full tests, package, exercise actual Docker API/database/RAG and clean only test fixtures. Independent review, address important findings, transfer changes to original workspace.

## Review focus
Filesystem links/unstable copies, per-document rollback, upload/scan race, registry namespacing, safe migration of existing files.
