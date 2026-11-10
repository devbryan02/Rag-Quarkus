// @vitest-environment happy-dom
import "@angular/compiler";
import { beforeAll, beforeEach, afterEach, it, expect } from "vitest";
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
import { DocumentsService } from "../src/app/features/documents/data-access/documents-service";
import { HealthService } from "../src/app/features/system/data-access/health-service";
beforeAll(() =>
  TestBed.initTestEnvironment(BrowserTestingModule, platformBrowserTesting()),
);
beforeEach(() => {
  TestBed.configureTestingModule({
    providers: [provideHttpClient(), provideHttpClientTesting()],
  });
  TestBed.inject(AuthStore).set("key");
});
afterEach(() => {
  TestBed.inject(HttpTestingController).verify();
  TestBed.resetTestingModule();
});
it("processes files sequentially and retains per-file failures", async () => {
  const service = TestBed.inject(DocumentsService);
  const http = TestBed.inject(HttpTestingController);
  service.queue.set(
    ["first.txt", "second.txt"].map((name) => ({
      file: new File(["text"], name),
      status: "queued",
      message: "",
    })),
  );
  const pending = service.upload();
  const first = http.expectOne("/api/documents");
  expect((first.request.body as FormData).get("file")).toBeInstanceOf(File);
  expect(service.queue()[1].status).toBe("queued");
  first.flush(
    { message: "Archivo inválido" },
    { status: 400, statusText: "Bad Request" },
  );
  await Promise.resolve();
  const second = http.expectOne("/api/documents");
  expect(service.queue()[0].status).toBe("error");
  second.flush({ success: true, message: "Indexado" });
  await pending;
  expect(service.queue()[1].status).toBe("success");
  expect(service.busy()).toBe(false);
});
it("blocks scan during upload and reports scan conflicts afterward", async () => {
  const service = TestBed.inject(DocumentsService);
  const http = TestBed.inject(HttpTestingController);
  service.busy.set(true);
  await service.scan();
  http.expectNone("/api/documents/scan");
  service.busy.set(false);
  const pending = service.scan();
  http
    .expectOne("/api/documents/scan")
    .flush({}, { status: 409, statusText: "Conflict" });
  await pending;
  expect(service.scanError()).toContain("otra");
  expect(service.scanning()).toBe(false);
});
it("requires a key again before sending each queued file", async () => {
  const service = TestBed.inject(DocumentsService);
  const auth = TestBed.inject(AuthStore);
  const http = TestBed.inject(HttpTestingController);
  service.queue.set(
    ["a.txt", "b.txt"].map((name) => ({
      file: new File(["text"], name),
      status: "queued",
      message: "",
    })),
  );
  const pending = service.upload();
  const first = http.expectOne("/api/documents");
  auth.set("");
  first.flush({ success: true, message: "Indexado" });
  await pending;
  http.expectNone("/api/documents");
  expect(service.queue()[1].status).toBe("error");
  expect(service.queue()[1].message).toContain("clave");
});
it("renders four DOWN results from HTTP 503 health responses", async () => {
  const service = TestBed.inject(HealthService);
  const http = TestBed.inject(HttpTestingController);
  const pending = service.refresh();
  for (const item of service.checks())
    http
      .expectOne(item.path)
      .flush(
        { status: "DOWN", checks: [{ name: "database", status: "DOWN" }] },
        { status: 503, statusText: "Unavailable" },
      );
  await pending;
  expect(
    service
      .checks()
      .every((x) => x.status === "DOWN" && x.detail.includes("database")),
  ).toBe(true);
  expect(service.loading()).toBe(false);
});
