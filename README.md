# Rag-Quarkus

Sistema **RAG (Retrieval-Augmented Generation)** con **Quarkus 3.37** y **LangChain4j**. Permite preguntas en lenguaje natural sobre documentos indexados (PDF, DOCX, TXT, MD) con respuestas basadas exclusivamente en el contenido de esos documentos.

- **LLM:** Llama 3.3 70B via API de **Groq** (compatible OpenAI)
- **Embeddings:** AllMiniLmL6V2 (local, 384 dimensiones, sin red)

---

## Arquitectura

```
POST /chat
  │  X-API-Key header requerido (solo /chat)
  ▼
ApiKeyFilter ──► ChatResource (@RateLimit: 10/min)
  │                  │
  │          @Valid ChatRequest { question }
  ▼                  ▼
RagAssistant.chat(question)
  │
  ├── @RegisterAiService (LangChain4j genera impl. en build time)
  ├── @SystemMessage: "Responde SOLO con contexto de documentos"
  ├── RetrieverProducer (Supplier<RetrievalAugmentor>):
  │     EmbeddingStoreContentRetriever
  │       ├── EmbeddingModel (AllMiniLmL6V2, local)
  │       ├── EmbeddingStore (InMemory)
  │       ├── maxResults=3, minScore=0.0
  │     └── DefaultRetrievalAugmentor inyecta contexto al prompt
  │
  ▼
Groq API (llama-3.3-70b-versatile, temp=0.3)
  │
  ▼
ChatResponse { answer }

Startup:
  DocumentIngestor (@PostConstruct)
    ├── Lee archivos del directorio configurado
    ├── DocumentLoaderFactory resuelve parser por extensión
    ├── DocumentSplitter recursive (300 chars, overlap 30)
    ├── Genera embeddings (AllMiniLmL6V2, local)
    └── Almacena en InMemoryEmbeddingStore
```

---

## Estructura del Proyecto

```
src/main/java/com/bryan/
├── features/
│   ├── chat/
│   │   ├── ChatResource.java           # POST /chat, @RateLimit(10/min)
│   │   ├── RagAssistant.java           # Interfaz AI (@RegisterAiService)
│   │   └── records/
│   │       ├── ChatRequest.java        # DTO entrada: { question }
│   │       └── ChatResponse.java       # DTO salida: { answer }
│   ├── ingestion/
│   │   ├── DocumentIngestor.java       # Ingesta al arrancar
│   │   ├── DocumentLoaderFactory.java  # Parser factory por extensión
│   │   ├── IngestionConfig.java        # @ConfigMapping rag.ingestion.*
│   │   └── exceptions/
│   │       └── DocumentLoadException.java
│   └── rerieval/
│       ├── RetrieverProducer.java      # Supplier<RetrievalAugmentor>
│       └── RetrievalConfig.java        # @ConfigMapping rag.retrieval.*
└── shared/
    ├── embedings/
    │   └── EmbeddingStoreProducer.java  # CDI: Store + EmbeddingModel
    ├── exceptions/
    │   ├── ErrorResponse.java           # Record { success, message, status }
    │   └── GlobalExceptionHandlers.java # RateLimit(429) + genérico(500)
    └── security/
        └── ApiKeyFilter.java           # Filtro X-API-Key en /chat
```

---

## Seguridad y Rate Limiting

### API Key (`ApiKeyFilter`)

Filtro JAX-RS que intercepta solo paths `/chat`. Requiere header `X-API-Key` que debe coincidir con la variable `API_KEY`. Otros endpoints pasan sin filtro.

```java
@Provider @Priority(Priorities.AUTHENTICATION)
public class ApiKeyFilter implements ContainerRequestFilter {
    @ConfigProperty(name = "app.api-key") String apiKey;

    public void filter(ContainerRequestContext ctx) {
        String path = ctx.getUriInfo().getPath();
        if (!path.startsWith("/chat")) return;
        // Valida X-API-Key header, aborta con 401 si es inválido
    }
}
```

### Rate Limiting (`ChatResource`)

SmallRye Fault Tolerance limita a **10 requests por minuto** por cliente. Exceso retorna HTTP 429.

```java
@RateLimit(value = 10, window = 1, windowUnit = ChronoUnit.MINUTES)
public ChatResponse chat(@Valid ChatRequest request) { ... }
```

### Manejo de Errores (`GlobalExceptionHandlers`)

Exception mappers globales via RESTEasy Reactive:

| Excepción | HTTP | Respuesta |
|---|---|---|
| `RateLimitException` | 429 | "Demasiadas solicitudes, intenta de nuevo en unos segundos" |
| `Exception` (genérica) | 500 | "Ocurrió un error inesperado" |

---

## Pipeline de Ingestión

Al iniciar la app, `DocumentIngestor` ejecuta el pipeline completo:

1. **Carga:** `DocumentLoaderFactory` selecciona parser por extensión:
   - `.pdf` → `ApachePdfBoxDocumentParser`
   - `.docx`/`.doc` → `ApachePoiDocumentParser`
   - `.txt`/`.md` → `TextDocumentParser`

2. **Splitting:** `DocumentSplitters.recursive(300, 30)` — fragmentos de 300 chars con overlap de 30 para mantener contexto entre chunks.

3. **Embedding:** `AllMiniLmL6V2EmbeddingModel` genera vectores de 384 dimensiones localmente (ONNX, sin llamadas a red).

4. **Almacenamiento:** `InMemoryEmbeddingStore` — se pierde al reiniciar, se re-indexa automáticamente.

---

## Configuración

```properties
# LLM (Groq API compatible OpenAI)
quarkus.langchain4j.openai.base-url=https://api.groq.com/openai/v1
quarkus.langchain4j.openai.api-key=${GROQ_API_KEY}
quarkus.langchain4j.openai.chat-model.model-name=llama-3.3-70b-versatile
quarkus.langchain4j.openai.chat-model.temperature=0.3
quarkus.langchain4j.openai.timeout=30s

# Debug
quarkus.langchain4j.log-requests=true
quarkus.langchain4j.log-responses=true

# Ingestión
rag.ingestion.documents-path=src/main/resources/documents
rag.ingestion.chunk-size=300
rag.ingestion.chunk-overlap=30

# Retrieval
rag.retrieval.max-results=3
rag.retrieval.min-score=0.0

# Seguridad
app.api-key=${API_KEY}
```

La configuración se mapea a interfaces tipadas con `@ConfigMapping` (SmallRye):

```java
@ConfigMapping(prefix = "rag.ingestion")
public interface IngestionConfig {
    String documentsPath();
    int chunkSize();
    int chunkOverlap();
}

@ConfigMapping(prefix = "rag.retrieval")
public interface RetrievalConfig {
    int maxResults();
    double minScore();
}
```

---

## Dependencias

| Dependencia | Propósito |
|---|---|
| `quarkus-rest-jackson` | Serialización JSON |
| `quarkus-rest` | JAX-RS / RESTEasy Reactive |
| `quarkus-langchain4j-openai` | Integración LLM (API OpenAI-compatible) |
| `quarkus-hibernate-validator` | Validación `@NotNull`, `@NotBlank` |
| `quarkus-smallrye-fault-tolerance` | `@RateLimit` en endpoints |
| `langchain4j-embeddings-all-minilm-l6-v2` | Embeddings locales (ONNX) |
| `langchain4j-document-parser-apache-poi` | Parsing DOCX |
| `langchain4j-document-parser-apache-pdfbox` | Parsing PDF |

---

## Ejecución

### Requisitos

- Java 21+
- Gradle (wrapper incluido)
- Variables de entorno en `.env`:
  ```
  GROQ_API_KEY=tu_api_key
  API_KEY=tu_api_key_de_acceso
  ```

### Desarrollo

```bash
./gradlew quarkusDev
# Dev UI: http://localhost:8080/q/dev/
```

### Ejemplo de petición

```bash
curl -X POST http://localhost:8080/chat \
  -H "Content-Type: application/json" \
  -H "X-API-Key: tu_api_key" \
  -d '{"question": "¿Qué es MAAC?"}'
```

### Empaquetado

```bash
# JAR
./gradlew build
java -jar build/quarkus-app/quarkus-run.jar

# Über-JAR
./gradlew build -Dquarkus.package.jar.type=uber-jar

# Nativo (GraalVM)
./gradlew build -Dquarkus.native.enabled=true
```

---

## Docker

| Dockerfile | Modo |
|---|---|
| `Dockerfile.jvm` | JVM estándar |
| `Dockerfile.legacy-jar` | JAR legacy |
| `Dockerfile.native` | GraalVM nativo |
| `Dockerfile.native-micro` | Nativo mínimo |

---

## Agregar Documentos

Coloca archivos en `src/main/resources/documents/`. Al reiniciar, se indexan automáticamente.

---

## Notas para Producción

| Aspecto | Actual | Recomendación |
|---|---|---|
| Store de vectores | `InMemoryEmbeddingStore` | PgVector, Chroma o Milvus |
| Seguridad | `X-API-Key` header | Evaluar OAuth2/JWT |
| API Key | Env var `API_KEY` | Vault o secrets manager |
| Logging | Requests/responses habilitados | Desactivar en prod |
| Test | Prueba desactualizada (`/hello`) | Actualizar para `POST /chat` con API key |
