package com.bryan.features.ingestion;

import com.bryan.features.ingestion.exceptions.UnsupportedDocumentTypeException;
import dev.langchain4j.data.document.Document;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.HexFormat;
import java.util.Objects;

@ApplicationScoped
public class FileDocumentIndexer {
    public enum Outcome { INDEXED, UPDATED, SKIPPED }
    @Inject DocumentLoaderFactory loader;
    @Inject EmbeddingIngestionService ingestion;
    @Inject DocumentIndexRepository repository;

    public Outcome index(Path root, Path source) throws IOException {
        String relative = relativePath(root, source);
        BasicFileAttributes before = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile()) throw new IOException("El origen no es un archivo regular");
        String name = source.getFileName().toString();
        String suffix = name.substring(name.lastIndexOf('.'));
        Path snapshot = Files.createTempFile("rag-snapshot-", suffix);
        try {
            try (var input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS)) {
                Files.copy(input, snapshot, StandardCopyOption.REPLACE_EXISTING);
            }
            BasicFileAttributes after = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!after.isRegularFile() || before.size() != after.size()
                    || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || !Objects.equals(before.fileKey(), after.fileKey())) {
                throw new IOException("El archivo cambio durante la copia; reintenta el escaneo");
            }
            String hash = sha256(snapshot);
            var previous = repository.findHash(relative);
            if (previous.filter(hash::equals).isPresent()) return Outcome.SKIPPED;
            EmbeddingIngestionService.PreparedDocument prepared;
            try {
                Document document = loader.load(snapshot);
                document.metadata().put("source_path", relative).put("source_sha256", hash);
                prepared = ingestion.prepare(document);
            } catch (RuntimeException e) {
                throw new UnsupportedDocumentTypeException("No se pudo procesar el documento: " + relative);
            }
            repository.replace(relative, hash, prepared);
            return previous.isPresent() ? Outcome.UPDATED : Outcome.INDEXED;
        } finally { Files.deleteIfExists(snapshot); }
    }

    static String relativePath(Path root, Path source) {
        var parts = new java.util.StringJoiner("/");
        for (Path part : root.relativize(source)) parts.add(part.toString());
        return parts.toString();
    }

    private String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192]; int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
