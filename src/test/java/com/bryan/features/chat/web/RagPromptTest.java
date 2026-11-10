package com.bryan.features.chat.web;

import dev.langchain4j.data.message.*;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RagPromptTest {
    @Test void keepsTrustedSystemAndClientHistorySeparateAndNeverCachesConversations() {
        var history=List.of(new ChatCommand.HistoryMessage("user","Primer turno"),new ChatCommand.HistoryMessage("assistant","Respuesta anterior"));
        var messages=RagPrompt.compose("Pregunta actual",history,List.of(TextSegment.from("El codigo documental es AZUL-7391.")));
        assertInstanceOf(SystemMessage.class,messages.get(0));
        assertEquals("Primer turno",((UserMessage)messages.get(1)).singleText());
        assertInstanceOf(AiMessage.class,messages.get(2));
        String prompt=((UserMessage)messages.get(3)).singleText();
        assertTrue(prompt.contains("AZUL-7391"));assertTrue(prompt.contains("Pregunta actual"));
        var independent=RagPrompt.compose("Nueva conversacion",List.of(),List.of());
        assertEquals(2,independent.size());assertFalse(independent.toString().contains("Primer turno"));
        assertTrue(RagPrompt.retrievalQuery("Y quien?",history).startsWith("Y quien?"));
        assertTrue(RagPrompt.retrievalQuery("Y quien?",history).contains("Primer turno"));
    }
}
