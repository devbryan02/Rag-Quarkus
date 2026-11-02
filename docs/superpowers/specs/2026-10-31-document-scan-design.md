# Manual recursive document scanning

Approved in conversation on 2026-10-31.

Files are placed in src/main/resources/documents and scanned manually through POST /documents/scan, authenticated with X-API-Key. Files.walkFileTree visits nested directories without following symbolic links. Supported formats reuse the existing parsers. The synchronous result contains indexed, updated, skipped, failed and errors (relative path and reason). File errors do not stop other files; database errors abort with HTTP 500. Unavailable root returns 500; concurrent ingestion returns 409.

Document identity is relative path with forward slashes. SHA-256 detects changes. Unchanged files are skipped; changed files replace old chunks atomically, preserving old content if processing or persistence fails. Generate vectors before starting a per-document JDBC transaction. Both upload and scan share this pipeline and an ingestion lock. Removing a source file does not delete vectors. No automatic startup ingestion, periodic watcher or background jobs.

Approaches considered: initial-only recursive ingestion (misses new documents), incremental ingestion on startup (requires restart), manual endpoint (chosen for explicit control from Swagger), periodic scanning (unneeded background work).

Docker bind-mounts the local document directory. Back up and recover existing volume documents before switching. Use embeddings_multilingual_minilm_files_v1, preserve the old table and volume, and rebuild using a first scan. Single app instance. A temporary snapshot is parsed and hashed consistently; reject source changes observed during copying.

Verify nested directories, equal filenames, repeated scans, updates, rollback, unsupported/corrupt/blank files, symlinks, authentication, missing root, concurrent upload/scan, upload followed by scan, database dimensions and RAG retrieval.
