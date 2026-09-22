# Rag-Quarkus

Sistema **RAG (Retrieval-Augmented Generation)** con **Quarkus 3.37** y **LangChain4j**. Permite preguntas en lenguaje natural sobre documentos indexados (PDF, DOCX, TXT, MD) con respuestas basadas exclusivamente en el contenido de esos documentos.

- **LLM:** Llama 3.3 70B via API de **Groq** (compatible OpenAI)
- **Embeddings:** AllMiniLmL6V2 (local, 384 dimensiones, sin red)

---

## Arquitectura

```
POST /chat
  │  X-API-Key header requerido
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

POST /documents
  │  X-API-Key header requerido, multipart/form-data (campo "file")
  ▼
ApiKeyFilter ──► DocumentUploadResource
  │                  ├── Valida extensión, nombre y contenido (DocumentLoaderFactory.isSupported)
  │                  ├── Sanea el nombre de archivo (getFileName, evita path traversal)
  │                  ├── Guarda en rag.ingestion.documents-path (rechaza duplicados con 409)
  │                  ├── DocumentLoaderFactory parsea el archivo guardado
  │                  ├── Si falla el parseo/indexado: borra el archivo y responde 400 (sin huérfanos)
  │                  └── EmbeddingIngestionService.ingest(...) indexa de inmediato
  ▼
DocumentUploadResponse { success, filename, message }  (201)

Startup:
  DocumentIngestor (@PostConstruct)
    ├── Si la tabla de PgVector ya tiene datos: omite la ingestión (evita duplicados en reinicios)
    ├── Lee archivos del directorio configurado
    ├── DocumentLoaderFactory resuelve parser por extensión
    ├── DocumentSplitter recursive (300 chars, overlap 30)
    ├── Genera embeddings (AllMiniLmL6V2, local)
    └── Almacena en PgVector (persistente, servicio "pgvector" en docker-compose)

EmbeddingIngestionService centraliza el splitter + embed + store: lo usan
tanto DocumentIngestor (arranque) como DocumentUploadResource (POST /documents).
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
│   │   ├── DocumentIngestor.java          # Ingesta al arrancar
│   │   ├── DocumentUploadResource.java    # POST /documents (carga + indexado en caliente)
│   │   ├── DocumentLoaderFactory.java     # Parser factory + isSupported(fileName) por extensión
│   │   ├── EmbeddingIngestionService.java # Splitter + embed + store, compartido por arranque y upload
│   │   ├── IngestionConfig.java           # @ConfigMapping rag.ingestion.*
│   │   ├── records/
│   │   │   └── DocumentUploadResponse.java # DTO salida: { success, filename, message }
│   │   └── exceptions/
│   │       ├── DocumentLoadException.java
│   │       ├── UnsupportedDocumentTypeException.java  # → 400
│   │       └── DuplicateDocumentException.java        # → 409
│   └── rerieval/
│       ├── RetrieverProducer.java      # Supplier<RetrievalAugmentor>
│       └── RetrievalConfig.java        # @ConfigMapping rag.retrieval.*
└── shared/
    ├── embedings/
    │   └── EmbeddingStoreProducer.java  # CDI: Store + EmbeddingModel
    ├── exceptions/
    │   ├── ErrorResponse.java           # Record { success, message, status }
    │   └── GlobalExceptionHandlers.java # WebApplicationException + RateLimit(429) + tipos de documento (400/409) + genérico(500)
    └── security/
        └── ApiKeyFilter.java           # Filtro X-API-Key en /chat y /documents
```

---

## Seguridad y Rate Limiting

### API Key (`ApiKeyFilter`)

Filtro JAX-RS que intercepta paths `/chat` y `/documents`. Requiere header `X-API-Key` que debe coincidir con la variable `API_KEY`. Otros endpoints pasan sin filtro (es un allowlist por prefijo: cada endpoint nuevo que deba protegerse tiene que agregarse aquí explícitamente).

```java
@Provider @Priority(Priorities.AUTHENTICATION)
public class ApiKeyFilter implements ContainerRequestFilter {
    @ConfigProperty(name = "app.api-key") String apiKey;

    public void filter(ContainerRequestContext ctx) {
        String path = ctx.getUriInfo().getPath();
        if (!path.startsWith("/chat") && !path.startsWith("/documents")) return;
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
| `WebApplicationException` | (la que traiga) | Pasa tal cual la respuesta del framework (404, 405, 415, etc.) en vez de aplanarla a 500 |
| `RateLimitException` | 429 | "Demasiadas solicitudes, intenta de nuevo en unos segundos" |
| `UnsupportedDocumentTypeException` | 400 | Archivo faltante, sin nombre, extensión no soportada, vacío/en blanco, o no procesable |
| `DuplicateDocumentException` | 409 | "Ya existe un documento llamado: ..." |
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

4. **Almacenamiento:** `PgVector` (Postgres con extensión pgvector) — persiste entre reinicios. `DocumentIngestor` solo re-indexa desde disco si la tabla está vacía.

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
# Swagger UI: http://localhost:8080/q/swagger-ui
# OpenAPI (JSON): http://localhost:8080/q/openapi
```

### Colección funcional (Postman / Bruno)

`test/functional/postman.json` — un solo archivo con variables (`baseUrl`,
`apiKey`), flujos exitosos, de error y de borde para `/chat` y
`/documents`, con pre-request y test scripts (incluye armado manual de
`multipart/form-data` para los casos de subida de documentos y una ráfaga
de solicitudes para forzar el rate limit de `/chat`).

- **Postman:** Import → File → `test/functional/postman.json` → completar
  la variable de colección `apiKey` con tu `API_KEY` real.
- **Bruno:** Import Collection → Postman v2.1 → seleccionar el mismo
  archivo.
- **CLI (Newman):**
  ```bash
  newman run test/functional/postman.json --env-var apiKey=tu_api_key
  ```

### Ejemplo de petición

```bash
curl -X POST http://localhost:8080/chat \
  -H "Content-Type: application/json" \
  -H "X-API-Key: tu_api_key" \
  -d '{"question": "¿Qué es MAAC?"}'
```

### Cargar un documento en caliente

```bash
curl -X POST http://localhost:8080/documents \
  -H "X-API-Key: tu_api_key" \
  -F "file=@/ruta/a/mi-documento.pdf"
```

Respuesta (`201 Created`):

```json
{ "success": true, "filename": "mi-documento.pdf", "message": "Documento indexado correctamente" }
```

El archivo se guarda en `rag.ingestion.documents-path` y se indexa de inmediato — no hace falta reiniciar. Un nombre de archivo repetido responde `409`; un archivo no soportado, vacío o no procesable responde `400` (y no deja archivos huérfanos en disco).

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

Dos formas de agregar documentos al índice:

1. **Manual:** coloca archivos en `src/main/resources/documents/` (o el path configurado en `rag.ingestion.documents-path`). Al reiniciar, `DocumentIngestor` los indexa automáticamente.
2. **API:** `POST /documents` (ver ejemplo arriba) — persiste el archivo en la misma carpeta y lo indexa sin reiniciar. También sobrevive un reinicio, porque queda en `documents-path` igual que los archivos agregados manualmente.

---

## Notas para Producción

| Aspecto | Actual | Recomendación |
|---|---|---|
| Store de vectores | `PgVector` (persistente, vía `quarkus-langchain4j-pgvector`) | — |
| Seguridad | `X-API-Key` header | Evaluar OAuth2/JWT |
| API Key | Env var `API_KEY` | Vault o secrets manager |
| Logging | Requests/responses habilitados | Desactivar en prod |
| Test | Prueba desactualizada (`/hello`) | Actualizar para `POST /chat` con API key |
| Rate limiting en `/documents` | Sin límite (solo `/chat` lo tiene) | Agregar `@RateLimit` dimensionado al throughput de carga esperado; ojo con el contador compartido de SmallRye si se prueba con muchas peticiones seguidas |
