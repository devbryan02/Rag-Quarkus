package com.bryan.features.chat;

import com.bryan.features.rerieval.RetrieverProducer;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

@RegisterAiService(retrievalAugmentor = RetrieverProducer.class)
public interface RagAssistant {

    @SystemMessage("""
            Eres un asistente que responde preguntas basándote ÚNICAMENTE
            en el contexto proporcionado de los documentos.

            Reglas:
            - Si la respuesta no está en el contexto, dilo claramente:
              "No encuentro esa información en los documentos disponibles."
            - No inventes ni completes con conocimiento externo.
            - Responde en español, de forma clara y directa.
            - Si citas información, menciona de qué parte del documento viene
              si es posible.
            """)
    String chat(@UserMessage String question);
}