# Execution ledger: 2026-10-31-document-scan

Workspace: .worktrees/scan-documents. Existing user change to EmbeddingStoreProducer.java copied for testing and excluded from feature commits. Verified feature changes applied to the original checkout without changing its branch.
Pre-flight: scan/upload share file indexer, repository and lock. Vectors and registry use the same JDBC connection. Startup does not independently ingest untracked chunks.

Task 1: complete. Baseline 16/16; initial 8 scan tests fail with 404 before implementation.
Task 2: complete. Recursive scanner, snapshot/hash identity, atomic replacement, shared upload coordination and manual startup policy implemented and tested.
Task 3: complete. Local bind mount, new table, functional collection and usage guide. Original volume backed up to build/document-scan-backup in original checkout; three source files recovered without overwrites. Old volume retained.
Task 4: complete. Original checkout full Gradle build passes: 31 tests, 29 pass, 2 Windows platform skips. Newman 17 cases, 27 HTTP requests, 29 assertions, zero failures. Actual Docker RAG returns updated source value. Two skipped platform cases independently verified on Linux (literal backslash names, symlink omission). Post-restart scan indexes/updates zero and skips three original documents. New table has 72 vectors of 384 dimensions; old table still has its 72 original vectors. All owned test fixtures, records and secondary container/table cleaned.

Final review: independent read-only reviewer review_document_scan reviewed base 85b872b..ad48819.
Final: fixed Linux path collision and cross-platform upload basename handling. Linux API before fix: second unchanged scan updated 2 files; after fix: indexed=0, updated=0, skipped=3 (including symlink). Regression tests included.
Final: fixed Linux bind-mount UID mismatch through configurable RAG_APP_UID/RAG_APP_GID (defaults 1000:1000) and documented owner mapping. Reproduced upload 500 before mapping; after mapping actual endpoint returns 201. Full Gradle suite green after fix pass.
Review exclusions: multi-instance coordination and deletion/rename cleanup are explicitly out of scope; the user's existing producer edit was preserved. Adversarial ancestor-directory swaps require stronger filesystem guarantees than this trusted manual directory feature promises.
Follow-up: corrected Dockerfile's filesystem comment to describe scanning and uploads.
Follow-up: expanded individual-upload OpenAPI 409 description to cover duplicate names and concurrent ingestion. Verified focused diff with git diff --check; documentation-only changes.
