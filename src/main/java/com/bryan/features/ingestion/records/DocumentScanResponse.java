package com.bryan.features.ingestion.records;

import java.util.List;

public record DocumentScanResponse(int indexed, int updated, int skipped, int failed, List<FileError> errors) {
    public record FileError(String path, String reason) { }
}
