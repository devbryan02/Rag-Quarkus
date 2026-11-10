# Nexo frontend

Angular 21 + Carbon, con interfaz en español. Requiere Node 24.

```sh
npm ci
npm start
npm test
npm run build
npx playwright install chromium
npm run e2e
```

Desarrollo: `http://localhost:4200`; `/api/*` se envía a Quarkus en `http://localhost:8080/*`, incluyendo WebSocket. `/q/*` mantiene la ruta original. El contenedor sirve la SPA en el puerto 80; Docker Compose la expone en 8081.

La clave de equipo existe solo en memoria y debe introducirse nuevamente después de recargar. Se agrega a solicitudes del API propio; nunca se envía en la URL del WebSocket. El chat obtiene un ticket de un solo uso antes de abrir la conexión.

El historial local usa el esquema `nexo.conversations.v1`. Los límites se aplican al guardar; el contexto solo contiene pares completos (máximo cinco turnos y 24000 caracteres). Cancelaciones, errores e interrupciones no forman parte del contexto. No se reenvían preguntas automáticamente al reconectar.

Pruebas unitarias: límites de contexto, historial, estados del protocolo y seguridad de origen de credenciales. Las pruebas Playwright interceptan HTTP y WebSocket y no necesitan un backend ni un proveedor de modelos.
