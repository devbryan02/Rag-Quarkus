// @vitest-environment happy-dom
import "@angular/compiler";
import {
  beforeAll,
  beforeEach,
  afterEach,
  describe,
  expect,
  it,
  vi,
} from "vitest";
import { TestBed } from "@angular/core/testing";
import {
  BrowserTestingModule,
  platformBrowserTesting,
} from "@angular/platform-browser/testing";
import {
  provideHttpClient,
  withInterceptors,
  HttpClient,
} from "@angular/common/http";
import {
  provideHttpClientTesting,
  HttpTestingController,
} from "@angular/common/http/testing";
import { AuthStore, ownApi } from "../src/app/core/auth/auth";
import { apiKeyInterceptor } from "../src/app/core/http/api";
import { ChatStore, HISTORY_KEY } from "../src/app/core/chat-history";
import { MarkdownComponent } from "../src/app/shared/ui/markdown";
import { ChatTransport } from "../src/app/core/websocket/chat-transport";
beforeAll(() =>
  TestBed.initTestEnvironment(BrowserTestingModule, platformBrowserTesting()),
);
beforeEach(() => {
  localStorage.clear();
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([apiKeyInterceptor])),
      provideHttpClientTesting(),
    ],
  });
});
afterEach(() => {
  TestBed.resetTestingModule();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});
describe("credential boundary", () => {
  it("only recognizes same-origin API paths", () => {
    expect(ownApi("/api/chat", "https://nexo.test")).toBe(true);
    expect(ownApi("/q/health", "https://nexo.test")).toBe(true);
    expect(ownApi("https://evil.test/api/chat", "https://nexo.test")).toBe(
      false,
    );
    expect(ownApi("//evil.test/api/chat", "https://nexo.test")).toBe(false);
    expect(ownApi("/apiary", "https://nexo.test")).toBe(false);
  });
  it("adds the key only to own API and never persists it", () => {
    const auth = TestBed.inject(AuthStore);
    auth.set(" secret ");
    const http = TestBed.inject(HttpClient);
    const controller = TestBed.inject(HttpTestingController);
    http.get("/api/chat").subscribe();
    expect(
      controller.expectOne("/api/chat").request.headers.get("X-API-Key"),
    ).toBe("secret");
    http.get("https://evil.test/api/chat").subscribe();
    expect(
      controller
        .expectOne("https://evil.test/api/chat")
        .request.headers.has("X-API-Key"),
    ).toBe(false);
    expect(JSON.stringify(localStorage)).not.toContain("secret");
  });
});
describe("history persistence", () => {
  it("keeps a working RAM copy when storage rejects writes", () => {
    const store = TestBed.inject(ChatStore);
    vi.stubGlobal("localStorage", {
      getItem: () => null,
      setItem: () => {
        throw new Error("QuotaExceeded");
      },
    });
    const c = store.create();
    expect(store.active()?.id).toBe(c.id);
    expect(store.warning()).toContain("No se pudo guardar");
  });
  it("marks pending work interrupted after reloading", () => {
    const store = TestBed.inject(ChatStore);
    const c = store.create();
    store.append(c.id, [
      {
        id: "m",
        role: "assistant",
        content: "partial",
        status: "pending",
        createdAt: new Date().toISOString(),
      },
    ]);
    expect(localStorage.getItem(HISTORY_KEY)).toContain("partial");
    TestBed.resetTestingModule();
    const restored = TestBed.inject(ChatStore);
    expect(restored.active()?.messages[0].status).toBe("interrupted");
  });
});
describe("Markdown component", () => {
  it("renders emphasis while suppressing raw HTML and unsafe URLs", () => {
    const fixture = TestBed.createComponent(MarkdownComponent);
    fixture.componentRef.setInput(
      "content",
      "**Seguro** <script>alert(1)</script><img src=x onerror=alert(1)> [enlace](javascript:alert(1))",
    );
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector("strong")?.textContent).toBe("Seguro");
    expect(element.querySelector("script,img")).toBeNull();
    expect(element.querySelector("a")?.getAttribute("href")).not.toMatch(
      /^javascript:/,
    );
  });
});
class FakeSocket {
  static OPEN = 1;
  static instances: FakeSocket[] = [];
  readyState = 0;
  sent: string[] = [];
  onmessage?: (e: any) => void;
  onclose?: () => void;
  onerror?: () => void;
  constructor(readonly url: string | URL) {
    FakeSocket.instances.push(this);
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
describe("WebSocket lifecycle", () => {
  beforeEach(() => {
    FakeSocket.instances = [];
    vi.stubGlobal("WebSocket", FakeSocket);
  });
  afterEach(() => {
    TestBed.inject(ChatTransport).disconnect();
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });
  async function open() {
    const auth = TestBed.inject(AuthStore);
    auth.set("key");
    const transport = TestBed.inject(ChatTransport);
    TestBed.tick();
    const pending = transport.send({ v: 1, type: "chat.start" });
    TestBed.inject(HttpTestingController)
      .expectOne("/api/chat/ws-ticket")
      .flush({ ticket: "short-lived" });
    await Promise.resolve();
    return { transport, pending, socket: FakeSocket.instances[0] };
  }
  it("waits for ready and never exposes the key in the URL", async () => {
    const { pending, socket } = await open();
    expect(socket.sent).toEqual([]);
    expect(String(socket.url)).toContain("ticket=short-lived");
    expect(String(socket.url)).not.toContain("key");
    socket.receive({ v: 1, type: "ready" });
    await pending;
    expect(JSON.parse(socket.sent[0]).type).toBe("chat.start");
  });
  it("discards callbacks from a socket closed after a key change", async () => {
    const { transport, pending, socket } = await open();
    socket.receive({ v: 1, type: "ready" });
    await pending;
    let frames = 0;
    transport.events$.subscribe((f) => {
      if (f.type === "chat.delta") frames++;
    });
    TestBed.inject(AuthStore).set("new");
    TestBed.tick();
    socket.receive({ v: 1, type: "chat.delta", text: "stale" });
    expect(frames).toBe(0);
    expect(transport.status()).toBe("Desconectado");
  });
  it("rejects JSON null rather than leaving a pending generation hanging", async () => {
    const { transport, pending, socket } = await open();
    socket.receive({ v: 1, type: "ready" });
    await pending;
    let error = "";
    transport.events$.subscribe((f) => {
      if (f.type === "transport.error") error = f.message ?? "";
    });
    socket.receive(null);
    expect(error).toContain("mensaje");
  });
});
