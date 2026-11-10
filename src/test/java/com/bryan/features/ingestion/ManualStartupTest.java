package com.bryan.features.ingestion;

import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(ManualStartupTest.Profile.class)
class ManualStartupTest {
    public static class Profile implements QuarkusTestProfile {
        @Override public Map<String,String> getConfigOverrides() {
            return Map.of("rag.ingestion.documents-path","src/test/resources/startup-documents",
                    "quarkus.langchain4j.pgvector.table","embeddings_manual_startup_test");
        }
    }
    @Inject DataSource dataSource;
    @Test void existingDocumentsAreNotAutomaticallyIndexedAtStartup() throws Exception {
        try(var c=dataSource.getConnection(); var s=c.createStatement();
                var rows=s.executeQuery("SELECT count(*) FROM embeddings_manual_startup_test")) {
            rows.next(); assertEquals(0,rows.getInt(1));
        }
    }
}
