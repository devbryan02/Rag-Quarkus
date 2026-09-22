# Endpoint de carga de documentos — Diseño

**Fecha:** 2026-09-22
**Estado:** Aprobado, pendiente de implementación
**Alcance:** Bounded (extiende el flujo de ingestión existente, no introduce subsistemas nuevos)

## Contexto

Hoy la única forma de agregar documentos al índice es colocarlos manualmente en
`rag.ingestion.documents-path` (`src/main/resources/documents/`) antes de arrancar
la aplicación: `DocumentIngestor` (`@PostConstruct`) los lee, parsea, trocea y
embebe en el `InMemoryEmbeddingStore` en cada arranque.

Este feature agrega un endpoint HTTP para subir un documento nuevo en caliente,
sin reiniciar la aplicación.

## Decisiones (confirmadas con el usuario)

| Pregunta | Decisión |
|---|---|
| ¿Persistir en disco o solo indexar en memoria? | Persistir en `documents-path` **y** indexar de inmediato. Así sobrevive un reinicio (re-indexado automático por `DocumentIngestor`), igual que los documentos actuales. |
| ¿Un archivo o varios por petición? | Un solo archivo por petición (`multipart/form-data`, campo `file`). |
| ¿Requiere `X-API-Key`? | Sí, la misma API key que protege `/chat`. |
| ¿Qué pasa si el nombre de archivo ya existe? | Rechazar con `409 Conflict`, sin sobrescribir. |

## Componentes

### Nuevo: `DocumentUploadResource` (`features/ingestion/`)

```
POST /documents
Content-Type: multipart/form-data (campo "file")
Header: X-API-Key (igual que /chat)
```

Flujo:
1. Extrae el nombre de archivo y valida la extensión contra
   `DocumentLoaderFactory.isSupported(fileName)` (ver refactor abajo).
   Si no es soportada → `UnsupportedDocumentTypeException` (400).
2. Verifica si `documents-path/<fileName>` ya existe.
   Si existe → `DuplicateDocumentException` (409).
3. Copia el archivo subido a `documents-path/<fileName>`.
4. Lo parsea con `DocumentLoaderFactory.load(path)` (sin cambios en su lógica de parseo).
5. Lo indexa vía `EmbeddingIngestionService.ingest(List.of(document))` (ver abajo).
6. Responde `201 Created` con `DocumentUploadResponse`.

### Nuevo: `DocumentUploadResponse` (`features/ingestion/records/`)

```java
record DocumentUploadResponse(boolean success, String filename, String message)
```

Sigue el mismo estilo que `ErrorResponse` (`shared/exceptions/`) y `ChatResponse`.

### Nuevo: `EmbeddingIngestionService` (`features/ingestion/`)

Extrae la construcción del `EmbeddingStoreIngestor` (splitter + `EmbeddingModel` +
`EmbeddingStore`) que hoy vive inline dentro de `DocumentIngestor.ingest()`, para
que tanto la ingesta de arranque como el nuevo endpoint compartan la misma
lógica de troceo/indexado en vez de duplicarla.

```java
@ApplicationScoped
class EmbeddingIngestionService {
    void ingest(List<Document> documents) { ... } // splitter + EmbeddingStoreIngestor.ingest(...)
}
```

`DocumentIngestor` pasa a delegar en este servicio en lugar de construir el
`EmbeddingStoreIngestor` él mismo.

### Modificado: `DocumentLoaderFactory`

Se agrega un método público `isSupported(String fileName)` que extrae la misma
lógica de extensiones que ya usa `resolveParser` internamente. Elimina la
necesidad de que el endpoint nuevo duplique por tercera vez la lista de
extensiones soportadas (hoy ya está repetida en `DocumentIngestor.isSupportedFile`
y en `resolveParser`).

`DocumentIngestor.isSupportedFile` se elimina y pasa a llamar a
`loaderFactory.isSupported(...)`.

### Modificado: `ApiKeyFilter`

El filtro solo intercepta rutas que empiezan con `/chat`. Se extiende para que
también intercepte `/documents`, protegiendo el nuevo endpoint con la misma
`X-API-Key`.

### Nuevas excepciones (`features/ingestion/exceptions/`)

- `UnsupportedDocumentTypeException` — mapeada a `400 Bad Request`.
- `DuplicateDocumentException` — mapeada a `409 Conflict`.

Ambas con su `@ServerExceptionMapper` en `GlobalExceptionHandlers`, siguiendo el
mismo patrón ya usado ahí para `RateLimitException` (log + `ErrorResponse`).

## Manejo de errores — resumen

| Caso | Status | Excepción |
|---|---|---|
| Extensión no soportada | 400 | `UnsupportedDocumentTypeException` |
| Archivo con nombre ya existente | 409 | `DuplicateDocumentException` |
| `X-API-Key` inválida o ausente | 401 | (ya cubierto por `ApiKeyFilter`) |
| Error de parseo/lectura del archivo | 500 | `DocumentLoadException` (ya existente, cae en el handler genérico) |

## Testing

- Test de integración: subir un `.txt`/`.md` válido → `201`, archivo aparece en
  `documents-path`, y una pregunta subsecuente a `/chat` puede recuperar contenido
  del documento subido.
- Extensión no soportada → `400`.
- Nombre de archivo duplicado → `409`.
- Sin `X-API-Key` o key inválida → `401`.
- Nota: como ya documenta `CLAUDE.md`, la suite de tests actual está obsoleta
  (`ExampleResourceTest`/`ExampleResourceIT` prueban un `/hello` que no existe).
  Los tests de este feature son cobertura nueva, no una corrección de los existentes.

## Fuera de alcance / caveats conocidos

- **Ruta relativa de `documents-path`:** está configurada como
  `src/main/resources/documents`, una ruta relativa al directorio de trabajo del
  proceso. En `quarkusDev` esto ya funciona hoy para la ingesta de arranque, pero
  escribir ahí en caliente probablemente dispare un live-reload (Quarkus vigila
  `src/main/resources`). En un JAR empaquetado, esa ruta relativa no apunta a los
  recursos embebidos en el jar sino a una carpeta nueva relativa a donde se
  ejecute el jar. Es una limitación preexistente de la configuración de ingesta,
  no algo que este feature introduzca — no se resuelve aquí.
- **Múltiples archivos por petición:** no soportado en esta iteración; el cliente
  hace una petición por archivo.
- **Borrado/actualización de documentos vía API:** fuera de alcance; este feature
  solo agrega carga.
