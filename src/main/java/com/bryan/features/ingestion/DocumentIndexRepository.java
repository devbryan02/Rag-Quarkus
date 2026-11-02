package com.bryan.features.ingestion;

import com.bryan.features.ingestion.exceptions.IndexPersistenceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** Keeps vectors and their source identity on the same PostgreSQL transaction. */
@Startup
@ApplicationScoped
public class DocumentIndexRepository {
    @Inject DataSource dataSource;
    @Inject ObjectMapper objectMapper;
    @ConfigProperty(name = "quarkus.langchain4j.pgvector.table") String table;
    @ConfigProperty(name = "quarkus.langchain4j.pgvector.dimension") int dimension;

    @PostConstruct
    void initialize() {
        if (!table.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("La tabla pgvector debe ser un identificador SQL simple");
        }
        try (var c = dataSource.getConnection(); var s = c.createStatement()) {
            s.execute("CREATE EXTENSION IF NOT EXISTS vector");
            s.execute("CREATE TABLE IF NOT EXISTS " + table + " (embedding_id UUID PRIMARY KEY, "
                    + "embedding vector(" + dimension + "), text TEXT, metadata JSON)");
            s.execute("CREATE TABLE IF NOT EXISTS rag_indexed_documents (embedding_table TEXT NOT NULL, "
                    + "source_path TEXT NOT NULL, sha256 CHAR(64) NOT NULL, indexed_at TIMESTAMPTZ NOT NULL DEFAULT now(), "
                    + "PRIMARY KEY (embedding_table, source_path))");
        } catch (SQLException e) { throw new IndexPersistenceException(e); }
    }

    public Optional<String> findHash(String path) {
        try (var c = dataSource.getConnection(); var s = c.prepareStatement(
                "SELECT sha256 FROM rag_indexed_documents WHERE embedding_table = ? AND source_path = ?")) {
            s.setString(1, table); s.setString(2, path);
            try (var r = s.executeQuery()) { return r.next() ? Optional.of(r.getString(1)) : Optional.empty(); }
        } catch (SQLException e) { throw new IndexPersistenceException(e); }
    }

    public void replace(String path, String hash, EmbeddingIngestionService.PreparedDocument prepared) {
        List<TextSegment> segments = prepared.segments();
        List<Embedding> embeddings = prepared.embeddings();
        if (segments.isEmpty() || segments.size() != embeddings.size()) {
            throw new IllegalArgumentException("El documento debe tener fragmentos y vectores correspondientes");
        }
        List<String> metadata = segments.stream()
                .map(segment -> objectMapper.valueToTree(segment.metadata().toMap()).toString()).toList();
        try (var c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (var s = c.prepareStatement("DELETE FROM " + table + " WHERE metadata->>'source_path' = ?")) {
                    s.setString(1, path); s.executeUpdate();
                }
                try (var s = c.prepareStatement("INSERT INTO " + table
                        + " (embedding_id, embedding, text, metadata) VALUES (?, ?::vector, ?, ?::json)")) {
                    for (int i = 0; i < segments.size(); i++) {
                        s.setObject(1, UUID.randomUUID());
                        s.setString(2, Arrays.toString(embeddings.get(i).vector()));
                        s.setString(3, segments.get(i).text()); s.setString(4, metadata.get(i)); s.addBatch();
                    }
                    s.executeBatch();
                }
                try (var s = c.prepareStatement("INSERT INTO rag_indexed_documents (embedding_table, source_path, sha256) "
                        + "VALUES (?, ?, ?) ON CONFLICT (embedding_table, source_path) DO UPDATE SET "
                        + "sha256 = EXCLUDED.sha256, indexed_at = now()")) {
                    s.setString(1, table); s.setString(2, path); s.setString(3, hash); s.executeUpdate();
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                try { c.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
                throw e;
            }
        } catch (SQLException e) { throw new IndexPersistenceException(e); }
    }
}
