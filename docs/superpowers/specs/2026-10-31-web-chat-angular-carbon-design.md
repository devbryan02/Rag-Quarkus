# Web RAG: Angular, Carbon and streaming WebSocket chat

Approved specification from the conversation (2026-10-31).

## Intent and choices
Spanish web UI covering all current operations. Team access by shared API key, no accounts. True streaming chat over native WebSocket subscribed through RxJS, local browser history across reloads and recent conversation context. Upload and scan remain HTTP with final results, no ingestion progress WS. No mockups requested.

Considered approaches: native WS for chat plus existing HTTP document API (chosen: smallest compatible change); WS also for ingestion progress (deferred: expands pipeline).

## Stack and structure
frontend/ in this repository; Angular21.2.25 framework + CLI/build21.2.29 (verified v21-lts), TypeScript5.9, RxJS7.8, Node24 LTS, carbon-components-angular5.72.3, @carbon/styles1.118.0 stable. Carbon has no verified equivalent LTS channel; use the compatible stable release, never unpublished @carbon/angular. Standalone routing with Carbon NgModules if needed and compatible Zone.js. Signals state, RxJS events; no NgRx. Features chat/documents/system/settings each pages/ui/data-access/models; core auth/http/websocket/layout; shared/ui. Carbon g10, Spanish responsive accessible UI, sanitized Markdown with raw HTML disabled.

## Screens and endpoints
/chat: conversation list/new/delete, composer, live messages/cancel, explicit full-response mode POST /chat (stateless legacy semantics, never automatic fallback).
/documents: sequential upload queue POST /documents field file; POST /documents/scan waits for summary indexed/updated/skipped/failed/errors. No fake remote inventory or PC-path input.
/system: /q/health, /live, /ready, /started; links Swagger/OpenAPI.
/settings: masked API key RAM only, remove key disconnects but keeps history, clear history control.

## Security/protocol
REST interceptor sends X-API-Key to own API only. Key never in localStorage/build/URL. POST /chat/ws-ticket authenticated returns {ticket,expiresInSeconds:30}, random32bytes one-use atomic consume. Upgrade /ws/chat?ticket= validates ticket and allowed Origin explicitly; REST filter alone is insufficient. Redact tickets from logs. Shared corpus across team, no server history lookup by client IDs.
JSON protocol v1: client chat.start {requestId,conversationId,question,history:[{role:user|assistant,content}]}, chat.cancel {requestId}. Server ready {connectionId}, chat.started, chat.delta {seq starting1,text}, chat.completed {answer}, chat.cancelled, chat.error {code,message,retryAfterSeconds?}; generation events include IDs. One active generation per connection, ordered deltas, at most one terminal, duplicate IDs rejected, no broadcast. Question max4000chars; history max5completepairs/10messages/24000chars; frame64KiB. No client system/tool roles.
Streaming model + fresh per-request validated memory + trusted system prompt + retrieved documents; retrieval considers question and recent conversation, never mix conversations. ONNX/JDBC outside eventloop. Cancellation/close unsubscribes, suppresses late chunks. Generation timeout60s, ping30s, proxytimeout>=120s, bounded outgoing128events. Shared rolling10generations/min across REST+WS; ticketquota30/min/IP; single backend instance in-memory limits/tickets.
Reconnect unexpected failures5attempts1/2/4/8/16s with fresh ticket, no prompt replay, no retry on explicit authfailure. Mark partial answers interrupted and exclude from context. Browser history versioned localStorage max20conversations100messages each2MiB; evict oldest complete inactive; failures retainRAM and display warning; restored pending becomes interrupted; API key reentry on reload.

## Deploy and tests
Dev4200 proxies/api stripped to Quarkus8080, /q unchanged. Nginx Docker frontend8081 serves SPA and proxies/api including Upgrade and /q unchanged. WSS when HTTPS. Keep existing backend8080, models/document mount/UID and all existing PG data.
Test deterministic token source: true chunk-before-complete, context isolation, cancel/close, ticket expiry/reuse/origin, malformed/size/rate/concurrent requests. Existing Java tests stay green. Frontend unit/component and Playwright tests cover streaming, history reload/key, docs, health, reconnect/no replay, safe Markdown and production proxy/deep links. Separate real Groq smoke. No broker, login, DB conversations, delete synchronization, document CRUD/inventory or SSR.

Sources: https://angular.dev/reference/releases ; https://angular.dev/reference/versions ; https://github.com/carbon-design-system/carbon-components-angular/releases/tag/v5.72.3 ; https://quarkus.io/guides/websockets-next-reference/ ; https://docs.quarkiverse.io/quarkus-langchain4j/dev/ai-services.html
