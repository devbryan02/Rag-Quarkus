package com.bryan.features.chat.web;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RetrievalQueryPlannerTest {
    @Test
    void scopesAdditionalSearchesToEveryExplicitlyReferencedDocument() {
        var queries = RetrievalQueryPlanner.queries(
                "¿Qué temas cubren ASR-001 y K8S-101-L1-lab?",
                List.of());

        assertEquals(List.of(
                new RetrievalQueryPlanner.SearchQuery("¿Qué temas cubren ASR-001 y K8S-101-L1-lab?", null),
                new RetrievalQueryPlanner.SearchQuery("¿Qué temas cubren ASR-001 y K8S-101-L1-lab?", "ASR-001"),
                new RetrievalQueryPlanner.SearchQuery("¿Qué temas cubren ASR-001 y K8S-101-L1-lab?", "K8S-101-L1-lab")), queries);
    }

    @Test
    void keepsSingleDocumentAndFollowUpSearchBehavior() {
        var history = List.of(new ChatCommand.HistoryMessage("user", "Pregunta anterior"),
                new ChatCommand.HistoryMessage("assistant", "Respuesta anterior"));

        assertEquals(List.of(
                        new RetrievalQueryPlanner.SearchQuery("¿Qué cubre ASR-001?", null),
                        new RetrievalQueryPlanner.SearchQuery("¿Qué cubre ASR-001?", "ASR-001")),
                RetrievalQueryPlanner.queries("¿Qué cubre ASR-001?", List.of()));
        var followUp = RetrievalQueryPlanner.queries("¿Y ese documento?", history);
        assertEquals(1, followUp.size());
        var query = followUp.get(0);
        assertEquals(null, query.documentReference());
        assertTrueContains(query.text(), "¿Y ese documento?", "Pregunta anterior", "Respuesta anterior");
    }

    private static void assertTrueContains(String actual, String... expectedParts) {
        for (String part : expectedParts) {
            org.junit.jupiter.api.Assertions.assertTrue(actual.contains(part),
                    () -> "Expected query to contain: " + part);
        }
    }
}
