package com.bryan.features.chat.web;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds focused retrieval searches when a question explicitly names several documents. */
final class RetrievalQueryPlanner {
    private static final Pattern DOCUMENT_REFERENCE = Pattern.compile(
            "(?<![A-Za-z0-9])(?=[A-Za-z0-9-]*\\d)[A-Z][A-Za-z0-9]*(?:-[A-Za-z0-9]+)+(?![A-Za-z0-9])");
    private static final int MAX_REFERENCES = 4;

    private RetrievalQueryPlanner() { }

    record SearchQuery(String text, String documentReference) { }

    static List<SearchQuery> queries(String question, List<ChatCommand.HistoryMessage> history) {
        String base = RagPrompt.retrievalQuery(question, history);
        Matcher matcher = DOCUMENT_REFERENCE.matcher(question);
        List<String> references = new ArrayList<>();
        while (matcher.find() && references.size() < MAX_REFERENCES) {
            references.add(matcher.group());
        }
        LinkedHashSet<SearchQuery> queries = new LinkedHashSet<>();
        queries.add(new SearchQuery(base, null));
        for (String reference : references) queries.add(new SearchQuery(base, reference));
        return List.copyOf(queries);
    }
}
