# Indexar carpetas y subcarpetas

1. Coloca los archivos dentro de `src/main/resources/documents/`. Puedes crear cualquier cantidad de subcarpetas. Formatos: PDF, DOCX, DOC, TXT y Markdown.
2. Abre `http://localhost:8080/q/swagger-ui/`, pulsa **Authorize** e introduce `API_KEY`.
3. Ejecuta **POST /documents/scan**, sin cuerpo. La respuesta espera hasta terminar y contiene `indexed`, `updated`, `skipped`, `failed` y `errors` (ruta y motivo).

El escaneo usa `Files.walkFileTree`, no sigue enlaces simbolicos y procesa un documento a la vez. La ruta relativa distingue archivos con nombres iguales. SHA-256 permite omitir los que no cambiaron. Una actualizacion reemplaza sus fragmentos anteriores en una transaccion: si falla, se conserva la version anterior. Los errores de archivo permiten continuar; un error de base de datos aborta la peticion con 500. Un directorio inexistente devuelve 500 y una ingesta concurrente devuelve 409.

La aplicacion no indexa automaticamente al arrancar. Despues del primer inicio, ejecuta el escaneo para dar contexto al chat. Retirar un archivo no elimina sus embeddings; renombrarlo lo trata como nuevo. Completa las copias antes de escanear: los cambios observados durante la copia temporal se reportan para reintentar. La carga individual tambien registra ruta y huella.

## Docker y datos anteriores

Docker Compose monta la carpeta local en `/deployments/documents`, con escritura. Los archivos agregados se ven sin reconstruir la imagen; para cambiar el codigo del pipeline si debes reconstruirla. Las cargas individuales aparecen en esa misma carpeta local.

Antes de sustituir el volumen anterior, guarda una copia:

```powershell
New-Item -ItemType Directory -Force build/document-scan-backup
docker cp rag-app:/deployments/documents/. build/document-scan-backup
```

Recupera los archivos que falten en `src/main/resources/documents/`, comparando contenido antes de sobrescribir nombres iguales. Conserva el respaldo y el volumen `documents_data`.

La tabla nueva es `embeddings_multilingual_minilm_files_v1`. La anterior, `embeddings_multilingual_minilm`, se conserva intacta. `rag_indexed_documents` registra ruta, SHA-256 y fecha por tabla activa. Tras cambiar el montaje y arrancar, ejecuta el escaneo completo; durante la reconstruccion el chat solo ve los documentos incorporados a la tabla nueva. Para volver a la version anterior, restaura su imagen/configuracion, el montaje `documents_data:/deployments/documents` y el nombre de tabla anterior. No uses `docker compose down -v`.

## Permisos del directorio en Linux

Compose ejecuta Java con UID/GID `1000:1000` por defecto. En Linux, ajustalos al propietario del directorio para que `POST /documents` pueda escribir:

```sh
RAG_APP_UID=$(id -u) RAG_APP_GID=$(id -g) docker compose up -d --build
```

Tambien puedes guardar `RAG_APP_UID` y `RAG_APP_GID` en `.env`. Si el directorio pertenece a otra cuenta, usa sus IDs y concede los permisos correspondientes. No hace falta ejecutar Java como root. Docker Desktop en Windows no suele requerir este ajuste. Con `docker run`, utiliza `--user UID:GID` con los mismos criterios.

## Verificacion

Ejecuta `.\gradlew.bat test` con Docker disponible y `GROQ_API_KEY` configurada (los tests automatizados no invocan al LLM; admiten un valor ficticio). La coleccion `test/functional/postman.json` incluye el escaneo autenticado, rechazo sin clave y repeticion sin duplicados. La consulta real al chat requiere una clave Groq valida.

Esta version admite una sola instancia de la aplicacion y procesa cada escaneo de forma sincrona. Un fallo global puede ocurrir despues de que otros documentos ya hayan quedado indexados; repetir la peticion continua de forma incremental.
