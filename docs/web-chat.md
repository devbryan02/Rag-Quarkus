# Web RAG: Angular y Carbon

## Arranque con Docker

Con Docker y el archivo `.env` existente con `GROQ_API_KEY` y `API_KEY`:

```powershell
.\gradlew.bat build
docker compose up -d --build
```

- Web: http://localhost:8081
- API y Swagger: http://localhost:8080/q/swagger-ui/

Abre **Ajustes**, introduce el valor de `API_KEY` y pulsa **Guardar clave**. La clave vive solo en memoria; recargar requiere introducirla nuevamente. Las conversaciones permanecen en ese navegador. La clave de Groq pertenece exclusivamente al backend.

**Conversaciones** utiliza streaming real por WebSocket. Puedes detener una respuesta y reintentar explícitamente. Una desconexión no reenvía preguntas. El modo **Respuesta completa** utiliza el endpoint REST existente y responde preguntas independientes, sin contexto anterior.

**Documentos** permite cargar archivos secuencialmente. Para carpetas, colócalas en `src/main/resources/documents/` y ejecuta el escaneo desde la pantalla. Muestra los contadores y errores finales; no inventa porcentajes ni un inventario remoto.

**Estado del sistema** consulta salud y enlaza Swagger/OpenAPI. Borrar el historial local cancela el trabajo activo; no borra documentos ni vectores.

## Desarrollo

Backend, con las variables necesarias:

```powershell
.\gradlew.bat quarkusDev
```

Frontend en otra terminal, con Node 24 LTS:

```powershell
cd frontend
npm ci
npm start
```

Abre http://localhost:4200. El proxy reenvía `/api/` a Quarkus retirando el prefijo y conserva `/q/` para documentación. No necesita CORS general.

## Seguridad y despliegue

Angular 21 LTS y Carbon Angular estable quedan fijados en el lockfile. Carbon no se etiqueta como LTS sin una política oficial equivalente.

Para otro dominio configura `WEB_ALLOWED_ORIGINS` con orígenes completos separados por comas, por ejemplo `https://rag.ejemplo.com`, y recrea el backend. El frontend deriva WS/WSS del protocolo de la página. Un proxy TLS externo debe permitir Upgrade y tiempos de lectura de al menos 120 segundos.

Compose conserva API 8080 y PostgreSQL 5432 y añade web 8081. Se mantienen tablas, volúmenes y montaje de documentos. En Linux configura UID/GID como indica [document-scanning.md](document-scanning.md).

Los tickets duran 30 segundos, admiten un solo uso y están vinculados al Origin permitido. La API key no se transmite en URLs. Nginx registra rutas sin query string y suprime error logs en la ruta que porta tickets.

Para la cuota por IP solo se confía en `X-Real-IP` cuando la conexión proviene de las direcciones DNS del servicio Docker `web`. Cambia `app.web.trusted-proxy-host` si utilizas otro proxy confiable. Una cabecera enviada directamente a 8080 no cambia esa identidad.

La cuota del equipo es de diez preguntas por ventana móvil de 60 segundos, sumando REST y WebSocket. Los tickets tienen una cuota separada de treinta/minuto/IP. Se utiliza una instancia del backend, sin cuentas, broker ni historial en PostgreSQL.

## Protocolo y contexto

`POST /chat/ws-ticket`, con `X-API-Key` y `Origin`, devuelve `{ticket,expiresInSeconds:30}`. La conexión se abre en `/ws/chat?ticket=...`; el navegador usa el proxy `/api`.

Cliente: `chat.start` con `v:1`, UUIDs `requestId`/`conversationId`, `question` e `history:[{role,content}]`; o `chat.cancel` con requestId. Servidor: `ready`, `chat.started`, `chat.delta` con secuencia desde 1 y texto, `chat.completed`, `chat.cancelled`, `chat.error`. Los eventos de generación incluyen sus identificadores. Solo se admite una generación activa por conexión.

Límites: pregunta de 4.000 caracteres, contexto de cinco turnos completos y 24.000 caracteres, frame de 64 KiB. No se aceptan roles system/tool del cliente. Cada petición construye contexto propio y recupera documentos; no existe memoria compartida entre conversaciones.

La generación tiene un límite de 60 segundos, configurable con `app.chat.generation-timeout`. Cancelar elimina la suscripción y descarta tokens tardíos; la cancelación del proveedor se propaga cuando ofrece su handle de streaming.

El historial local utiliza formato versionado: veinte conversaciones, cien mensajes por conversación y 2 MiB. Al restaurar, las respuestas pendientes se marcan interrumpidas y se excluyen del contexto. Los errores de almacenamiento mantienen la sesión en RAM y muestran un aviso.

## Verificación

```powershell
.\gradlew.bat test
cd frontend
npm test
npm run build
npm run e2e
```

Los tests Java utilizan tokens alternativos definidos únicamente en `src/test`. Los tests de navegador simulan HTTP/WS y no consumen Groq. La comprobación real se ejecuta aparte con la clave local y datos identificables, retirados al terminar. Conserva los volúmenes al detener los servicios.
