// @vitest-environment happy-dom
import "@angular/compiler";
import { beforeAll, beforeEach, afterEach, it, expect, vi } from "vitest";
import { TestBed } from "@angular/core/testing";
import {
  BrowserTestingModule,
  platformBrowserTesting,
} from "@angular/platform-browser/testing";
import { provideHttpClient } from "@angular/common/http";
import {
  provideHttpClientTesting,
  HttpTestingController,
} from "@angular/common/http/testing";
import { AuthStore } from "../src/app/core/auth/auth";
import { ChatStore } from "../src/app/core/chat-history";
import { ChatService } from "../src/app/features/chat/data-access/chat-service";
import { ChatPage } from "../src/app/features/chat/pages/chat-page";
import { ChatTransport } from "../src/app/core/websocket/chat-transport";
class Socket {
  static OPEN = 1;
  static instances: Socket[] = [];
  readyState = 0;
  sent: string[] = [];
  onmessage?: (event: any) => void;
  onclose?: () => void;
  constructor(_: any) {
    Socket.instances.push(this);
  }
  send(data: string) {
    this.sent.push(data);
  }
  close() {
    this.readyState = 3;
    this.onclose?.();
  }
  receive(frame: any) {
    this.readyState = 1;
    this.onmessage?.({ data: JSON.stringify(frame) });
  }
}
beforeAll(() =>
  TestBed.initTestEnvironment(BrowserTestingModule, platformBrowserTesting()),
);
beforeEach(() => {
  localStorage.clear();
  Socket.instances = [];
  vi.stubGlobal("WebSocket", Socket);
  TestBed.configureTestingModule({
    providers: [provideHttpClient(), provideHttpClientTesting()],
  });
  TestBed.inject(AuthStore).set("key");
  TestBed.inject(ChatService);
  TestBed.tick();
});
afterEach(() => {
  TestBed.inject(ChatTransport).disconnect();
  TestBed.resetTestingModule();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});
it("cancels a pending connection without sending a question after ready", async () => {
  const service = TestBed.inject(ChatService);
  const pending = service.send("Pregunta");
  TestBed.inject(HttpTestingController)
    .expectOne("/api/chat/ws-ticket")
    .flush({ ticket: "ticket" });
  await Promise.resolve();
  const socket = Socket.instances[0];
  service.cancel();
  socket.receive({ v: 1, type: "ready" });
  await pending;
  expect(socket.sent).toEqual([]);
  expect(service.busy()).toBe(false);
  expect(TestBed.inject(ChatStore).active()?.messages[1].status).toBe(
    "cancelled",
  );
});
it("keeps partial cancellation out of future context and preserves server completed text", async () => {
  const service = TestBed.inject(ChatService);
  const history = TestBed.inject(ChatStore);
  const pending = service.send("Pregunta inicial");
  TestBed.inject(HttpTestingController)
    .expectOne("/api/chat/ws-ticket")
    .flush({ ticket: "ticket" });
  await Promise.resolve();
  const socket = Socket.instances[0];
  socket.receive({ v: 1, type: "ready" });
  await pending;
  const start = JSON.parse(socket.sent[0]);
  socket.receive({ ...start, type: "chat.delta", seq: 1, text: "Parcial" });
  service.cancel();
  socket.receive({
    ...start,
    type: "chat.completed",
    answer: "Terminó mientras se cancelaba",
  });
  expect(history.active()?.messages[1].status).toBe("cancelled");
  expect(history.active()?.messages[1].content).toBe("Parcial");
  await service.send("Pregunta siguiente");
  const second = JSON.parse(socket.sent.at(-1)!);
  expect(second.history).toEqual([]);
  socket.receive({ ...second, type: "chat.completed", answer: "Final" });
  expect(history.active()?.messages.at(-1)?.content).toBe("Final");
});
it("stops reconnecting when ticket credentials are rejected", async () => {
  vi.useFakeTimers();
  const service = TestBed.inject(ChatService);
  const pending = service.send("Pregunta");
  TestBed.inject(HttpTestingController)
    .expectOne("/api/chat/ws-ticket")
    .flush(
      { message: "unauthorized" },
      { status: 401, statusText: "Unauthorized" },
    );
  await pending;
  await vi.advanceTimersByTimeAsync(60000);
  TestBed.inject(HttpTestingController).expectNone("/api/chat/ws-ticket");
  expect(service.error()).toContain("clave");
  expect(service.busy()).toBe(false);
});

it("keeps an explicit REST response alive when an old WebSocket closes", async () => {
  const service = TestBed.inject(ChatService);
  const http = TestBed.inject(HttpTestingController);
  const first = service.send("Primera");
  http.expectOne("/api/chat/ws-ticket").flush({ ticket: "ticket" });
  await Promise.resolve();
  const socket = Socket.instances[0];
  socket.receive({ v: 1, type: "ready" });
  await first;
  const frame = JSON.parse(socket.sent[0]);
  socket.receive({
    ...frame,
    type: "chat.completed",
    answer: "Primera respuesta",
  });
  service.mode.set("rest");
  await service.send("Pregunta independiente");
  const request = http.expectOne("/api/chat");
  socket.close();
  expect(service.busy()).toBe(true);
  request.flush({ answer: "Respuesta HTTP" });
  expect(TestBed.inject(ChatStore).active()?.messages.at(-1)?.content).toBe(
    "Respuesta HTTP",
  );
  expect(service.busy()).toBe(false);
});
it("cancels a REST subscription when the team key is removed", async () => {
  const service = TestBed.inject(ChatService);
  service.mode.set("rest");
  await service.send("Pregunta");
  const request = TestBed.inject(HttpTestingController).expectOne("/api/chat");
  TestBed.inject(AuthStore).set("");
  TestBed.tick();
  expect(request.cancelled).toBe(true);
  expect(service.busy()).toBe(false);
  expect(TestBed.inject(ChatStore).active()?.messages.at(-1)?.status).toBe(
    "interrupted",
  );
});
it("finishes the active generation before confirmed history clearing", async () => {
  const service = TestBed.inject(ChatService);
  const history = TestBed.inject(ChatStore);
  const pending = service.send("Pregunta");
  TestBed.inject(HttpTestingController)
    .expectOne("/api/chat/ws-ticket")
    .flush({ ticket: "ticket" });
  await Promise.resolve();
  const socket = Socket.instances[0];
  socket.receive({ v: 1, type: "ready" });
  await pending;
  history.clear();
  expect(service.busy()).toBe(false);
  expect(history.conversations()).toEqual([]);
  expect(socket.readyState).toBe(3);
});
it("reconnects again after a key change during an earlier backoff", async () => {
  vi.useFakeTimers();
  const service = TestBed.inject(ChatService);
  const http = TestBed.inject(HttpTestingController);
  const first = service.send("Uno");
  http.expectOne("/api/chat/ws-ticket").flush({ ticket: "first" });
  await Promise.resolve();
  let socket = Socket.instances[0];
  socket.receive({ v: 1, type: "ready" });
  await first;
  socket.close();
  TestBed.inject(AuthStore).set("replacement");
  TestBed.tick();
  const second = service.send("Dos");
  http.expectOne("/api/chat/ws-ticket").flush({ ticket: "second" });
  await Promise.resolve();
  socket = Socket.instances[1];
  socket.receive({ v: 1, type: "ready" });
  await second;
  socket.close();
  await vi.advanceTimersByTimeAsync(1000);
  const reconnect = http.expectOne("/api/chat/ws-ticket");
  reconnect.flush({ ticket: "third" });
  await Promise.resolve();
  Socket.instances[2].receive({ v: 1, type: "ready" });
});

it("deleting a selected idle conversation leaves another conversation streaming", async () => {
  const service = TestBed.inject(ChatService);
  const history = TestBed.inject(ChatStore);
  const pending = service.send("Activa");
  TestBed.inject(HttpTestingController)
    .expectOne("/api/chat/ws-ticket")
    .flush({ ticket: "ticket" });
  await Promise.resolve();
  const socket = Socket.instances[0];
  socket.receive({ v: 1, type: "ready" });
  await pending;
  const streamingId = history.activeId();
  const idle = history.create();
  const page = TestBed.runInInjectionContext(() => new ChatPage());
  page.remove(idle.id);
  expect(
    history
      .conversations()
      .find((c) => c.id === streamingId)
      ?.messages.at(-1)?.status,
  ).toBe("pending");
});
