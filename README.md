# Rag-Quarkus

Sistema de **Retrieval-Augmented Generation (RAG)** construido con **Quarkus 3.37** y **LangChain4j**. Permite realizar preguntas en lenguaje natural sobre documentos indexados (PDF, DOCX, TXT, MD) y obtener respuestas fundamentadas exclusivamente en el contenido de esos documentos.

El modelo de lenguaje utilizado es **Llama 3.3 70B** a través de la API de **Groq**, y los embeddings se generan localmente con el modelo **AllMiniLmL6V2**.

---

## Arquitectura General

```
┌──────────────────────────────────────────────────────────────┐
│                     Capa REST API                            │
│                                                              │
│  POST /chat                                                  │
│    ├── ChatResource (JAX-RS)                                 │
│    │     ├── ChatRequest DTO  {"question": "..."}            │
│    │     └── ChatResponse DTO {"answer": "..."}              │
│    └── Hibernate Validator (@Valid, @NotBlank)               │
│                                                              │
├──────────────────────────────────────────────────────────────┤
│                    Capa de Servicio AI                        │
│                                                              │
│  RagAssistant (@RegisterAiService)                           │
│    ├── System prompt (español, responder solo con contexto)  │
│    ├── RetrievalAugmentor (vía RetrieverProducer)            │
│    └── Delega al LLM (Groq / Llama 3.3 70B)                 │
│                                                              │
├──────────────────────────────────────────────────────────────┤
│                  Infraestructura RAG                          │
│                                                              │
│  RetrieverProducer                                           │
│    ├── EmbeddingStoreContentRetriever                        │
│    │     ├── EmbeddingModel (AllMiniLmL6V2, LOCAL)           │
│    │     └── EmbeddingStore (InMemory, LOCAL)                │
│    └── DefaultRetrievalAugmentor                             │
│                                                              │
├──────────────────────────────────────────────────────────────┤
│                  Pipeline de Ingestión                        │
│                                                              │
│  DocumentIngestor (@Startup @PostConstruct)                  │
│    ├── DocumentLoaderFactory (PDF/DOCX/TXT/MD)               │
│    ├── DocumentSplitter (recursive, 300 chars, overlap 30)   │
│    ├── EmbeddingModel (AllMiniLmL6V2)                        │
│    └── EmbeddingStore (InMemoryEmbeddingStore)               │
│                                                              │
├──────────────────────────────────────────────────────────────┤
│                 Servicios Externos                            │
│                                                              │
│  Groq API ── https://api.groq.com/openai/v1                  │
│    Model: llama-3.3-70b-versatile                            │
│    API Key: GROQ_API_KEY                                     │
└──────────────────────────────────────────────────────────────┘
```

---

## Flujo de Trabajo

### 1. Ingesta de Documentos (al iniciar la aplicación)

Al arrancar, `DocumentIngestor` lee todos los documentos soportados del directorio configurado, los parsea, divide en fragmentos, genera embeddings y los almacena en memoria:

```java
@Startup
@ApplicationScoped
public class DocumentIngestor {

    @PostConstruct
    void ingest() {
        List<Path> files = loadSupportedFiles(ingestionConfig.documentsPath());
        for (Path file : files) {
            Document document = documentLoaderFactory.load(file);
            List<Document> chunks = splitter.split(document);
            embeddingStoreIngestor.ingest(chunks);
        }
    }
}
```

El enrutamiento por tipo de archivo se realiza en `DocumentLoaderFactory`:

```java
@ApplicationScoped
public class DocumentLoaderFactory {

    public DocumentParser getParser(Path path) {
        String fileName = path.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".pdf"))  return new ApachePdfBoxDocumentParser();
        if (fileName.endsWith(".docx") || fileName.endsWith(".doc"))
            return new ApachePoiDocumentParser();
        if (fileName.endsWith(".txt") || fileName.endsWith(".md"))
            return new TextDocumentParser();
        throw new DocumentLoadException("Tipo de archivo no soportado: " + fileName);
    }
}
```

### 2. Consulta RAG (por cada petición HTTP)

```
POST /chat  {"question": "¿Qué es MAAC?"}
        │
        ▼
  ChatResource.chat(@Valid ChatRequest)
        │
        ▼
  RagAssistant.chat(question)
        │
        ├── @RegisterAiService intercepta la llamada
        ├── RetrieverProducer construye:
        │     EmbeddingStoreContentRetriever
        │       ├── EmbeddingModel embebe la consulta
        │       ├── EmbeddingStore busca top-3 chunks similares
        │       └── minScore filter: 0.0 (sin filtrado)
        ├── DefaultRetrievalAugmentor inyecta contexto al prompt
        │
        ▼
  LLM call vía Groq API (OpenAI-compatible)
        │
        ▼
  ChatResponse(answer)
```

---

## Estructura del Proyecto

```
Rag-Quarkus/
├── build.gradle.kts                  # Build con Gradle (Kotlin DSL)
├── settings.gradle.kts               # Nombre del proyecto y plugin Quarkus
├── gradle.properties                 # Versiones: Quarkus 3.37.0
├── .env                              # API key de Groq (GROQ_API_KEY)
├── src/main/java/com/bryan/
│   ├── features/
│   │   ├── chat/
│   │   │   ├── ChatResource.java         # Endpoint REST POST /chat
│   │   │   ├── RagAssistant.java         # Interfaz AI con LangChain4j
│   │   │   └── records/
│   │   │       ├── ChatRequest.java      # DTO de entrada
│   │   │       └── ChatResponse.java     # DTO de salida
│   │   ├── ingestion/
│   │   │   ├── DocumentIngestor.java     # Ingesta al arrancar
│   │   │   ├── DocumentLoaderFactory.java # Enrutador de parsers
│   │   │   ├── IngestionConfig.java      # Config de chunks
│   │   │   └── exceptions/
│   │   │       └── DocumentLoadException.java
│   │   └── rerieval/
│   │       ├── RetrievalConfig.java      # Config de retriever
│   │       └── RetrieverProducer.java    # Productor CDI del augmentor
│   └── shared/
│       └── EmbeddingStoreProducer.java   # Beans CDI: Store + EmbeddingModel
├── src/main/resources/
│   ├── application.properties            # Configuración completa
│   └── documents/
│       └── deploy-maacdvet-subdominio.pdf
└── src/test/                             # Tests (Quarkus Test)
```

---

## Código Fuente Detallado

### Capa REST - `ChatResource.java`

```java
@Path("/chat")
@ApplicationScoped
public class ChatResource {

    @Inject
    RagAssistant ragAssistant;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public ChatResponse chat(@Valid ChatRequest request) {
        String answer = ragAssistant.chat(request.question());
        return new ChatResponse(answer);
    }
}
```

### Servicio AI - `RagAssistant.java`

La interfaz usa la anotación `@RegisterAiService` de LangChain4j. Quarkus genera automáticamente la implementación en tiempo de compilación:

```java
@RegisterAiService(retrievalAugmentor = RetrieverProducer.class)
public interface RagAssistant {

    @SystemMessage("""
        Eres un asistente experto. Responde ÚNICAMENTE basándote en el contexto
        de los documentos proporcionados. Si la información no se encuentra en
        el contexto, indica que no tienes información suficiente.
    """)
    String chat(@UserMessage String question);
}
```

El `RetrievalAugmentor` se inyecta via `RetrieverProducer`:

```java
@ApplicationScoped
public class RetrieverProducer implements Supplier<RetrievalAugmentor> {

    @Inject EmbeddingStore<TextSegment> embeddingStore;
    @Inject EmbeddingModel embeddingModel;
    @Inject RetrievalConfig retrievalConfig;

    @Override
    public RetrievalAugmentor get() {
        ContentRetriever retriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(retrievalConfig.maxResults())
                .minScore(retrievalConfig.minScore())
                .build();
        return new DefaultRetrievalAugmentor(retriever);
    }
}
```

### DTOs

```java
public record ChatRequest(
    @NotNull @NotBlank String question
) {}

public record ChatResponse(String answer) {}
```

### Productores CDI - `EmbeddingStoreProducer.java`

```java
@ApplicationScoped
public class EmbeddingStoreProducer {

    @Produces
    @Singleton
    EmbeddingStore<TextSegment> produceEmbeddingStore() {
        return new InMemoryEmbeddingStore<>();
    }

    @Produces
    @Singleton
    EmbeddingModel produceEmbeddingModel() {
        return new AllMiniLmL6V2EmbeddingModel();
    }
}
```

---

## Configuración

### `application.properties`

```properties
# === Modelo LLM (Groq API compatible con OpenAI) ===
quarkus.langchain4j.openai.base-url=https://api.groq.com/openai/v1
quarkus.langchain4j.openai.api-key=${GROQ_API_KEY}
quarkus.langchain4j.openai.chat-model.model-name=llama-3.3-70b-versatile
quarkus.langchain4j.openai.chat-model.temperature=0.3
quarkus.langchain4j.openai.timeout=30s

# Logging de requests/responses (debug)
quarkus.langchain4j.log-requests=true
quarkus.langchain4j.log-responses=true

# === Ingestión de documentos ===
rag.ingestion.documents-path=src/main/resources/documents
rag.ingestion.chunk-size=300
rag.ingestion.chunk-overlap=30

# === Recuperación (Retrieval) ===
rag.retrieval.max-results=3
rag.retrieval.min-score=0.0
```

### Configuración tipada

La configuración se mapea a interfaces con `@ConfigMapping` de SmallRye:

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

## Tecnologías y Dependencias

| Dependencia | Propósito |
|---|---|
| `quarkus-rest-jackson` | Serialización JSON para REST |
| `quarkus-langchain4j-openai` | Integración LangChain4j con APIs OpenAI-compatibles |
| `quarkus-arc` | Inyección de dependencias CDI |
| `quarkus-rest` | JAX-RS / RESTEasy Reactive |
| `quarkus-hibernate-validator` | Validación de beans (`@NotNull`, `@NotBlank`) |
| `langchain4j-embeddings-all-minilm-l6-v2` | Modelo de embeddings local (384 dimensiones) |
| `langchain4j-document-parser-apache-poi` | Parsing de archivos DOCX |
| `langchain4j-document-parser-apache-pdfbox` | Parsing de archivos PDF |

---

## Requisitos Previos

- **Java 21+**
- **Gradle** (wrapper incluido: `./gradlew`)
- **API Key de Groq** (configurar en `.env`)

### Variables de entorno

El archivo `.env` debe contener:

```
GROQ_API_KEY=tu_api_key_aqui
```

---

## Ejecución

### Modo desarrollo (con live coding)

```bash
./gradlew quarkusDev
```

La Dev UI está disponible en: http://localhost:8080/q/dev/

### Ejemplo de petición

```bash
curl -X POST http://localhost:8080/chat \
  -H "Content-Type: application/json" \
  -d '{"question": "¿Qué es MAAC?"}'
```

Respuesta:

```json
{
  "answer": "MAAC es un sistema de gestión de subdominios que permite..."
}
```

### Empaquetar como JAR

```bash
./gradlew build
# JAR en: build/quarkus-app/quarkus-run.jar
java -jar build/quarkus-app/quarkus-run.jar
```

### Empaquetar como Über-JAR

```bash
./gradlew build -Dquarkus.package.jar.type=uber-jar
java -jar build/*-runner.jar
```

### Ejecutable nativo (GraalVM)

```bash
# Con GraalVM instalado
./gradlew build -Dquarkus.native.enabled=true

# Sin GraalVM (via contenedor)
./gradlew build -Dquarkus.native.enabled=true -Dquarkus.native.container-build=true
```

---

## Docker

Se incluyen 4 Dockerfiles para distintos modos de despliegue:

| Archivo | Modo |
|---|---|
| `Dockerfile.jvm` | JVM estándar |
| `Dockerfile.legacy-jar` | JAR legacy |
| `Dockerfile.native` | GraalVM nativo |
| `Dockerfile.native-micro` | Nativo mínimo |

---

## Agregar Documentos

Coloca archivos PDF, DOCX, TXT o MD en el directorio:

```
src/main/resources/documents/
```

Al reiniciar la aplicación, los documentos se indexarán automáticamente. El proceso de ingesta:
1. Lee todos los archivos del directorio
2. Parsea según extensión (PDF → PDFBox, DOCX → POI, TXT/MD → Texto plano)
3. Divide en fragmentos de 300 caracteres con overlap de 30
4. Genera embeddings con AllMiniLmL6V2 (local, sin red)
5. Almacena en `InMemoryEmbeddingStore`

> **Nota:** El almacenamiento es en memoria. Al reiniciar se re-indexan todos los documentos. Para persistencia en producción, se recomienda integrar una base de vectores como PgVector, Chroma o Milvus.

---

## Diseño de los Chunks

- **Tamaño:** 300 caracteres por fragmento
- **Overlap:** 30 caracteres (para mantener contexto entre fragmentos)
- **Splitter:** Recursive (respeta párrafos y oraciones)

Esto es óptimo para preguntas cortas y respuestas precisas. Para resúmenes de documentos largos, se recomienda incrementar el `chunk-size`.

---

## Arquitectura de la Persistencia

```
┌─────────────────────────────────────────┐
│        InMemoryEmbeddingStore           │
│                                         │
│  TextSegment ──► Embedding (384 dims)   │
│  TextSegment ──► Embedding (384 dims)   │
│  TextSegment ──► Embedding (384 dims)   │
│         ...                             │
│                                         │
│  Almacén en JVM Heap.                   │
│  Se pierde al reiniciar la app.         │
│  Se re-indexa automáticamente.          │
└─────────────────────────────────────────┘
```

---

## Consideraciones para Producción

| Aspecto | Estado actual | Recomendación |
|---|---|---|
| Almacenamiento de vectores | `InMemoryEmbeddingStore` | Usar PgVector, Chroma o Milvus |
| LLM | Groq API (remoto) | Evaluar latencia y costos |
| Seguridad | Sin auth en endpoints | Agregar autenticación |
| Rate limiting | No configurado | Implementar con Quarkus |
| Logging | Habilitado (requests/responses) | Desactivar en producción |
| API Key | En `.env` | Usar vault o variables de entorno del sistema |
| Test del endpoint | Prueba desactualizada (`/hello`) | Actualizar test para `POST /chat` |
