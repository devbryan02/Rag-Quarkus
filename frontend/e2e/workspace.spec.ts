import { test, expect } from "@playwright/test";
test.beforeEach(async ({ page }) => {
  await page.route("**/api/chat/ws-ticket", (r) =>
    r.fulfill({ json: { ticket: "one-use", expiresInSeconds: 30 } }),
  );
  await page.routeWebSocket("**/api/ws/chat?*", (ws) => {
    ws.send(JSON.stringify({ v: 1, type: "ready", connectionId: "test" }));
    ws.onMessage((raw) => {
      const f = JSON.parse(String(raw));
      if (f.type === "chat.start") {
        ws.send(
          JSON.stringify({
            v: 1,
            type: "chat.started",
            requestId: f.requestId,
            conversationId: f.conversationId,
          }),
        );
        ws.send(
          JSON.stringify({
            v: 1,
            type: "chat.delta",
            requestId: f.requestId,
            conversationId: f.conversationId,
            seq: 1,
            text: 'Respuesta **documentada** <img src=x onerror="alert(1)">',
          }),
        );
        if (!f.question.includes("cancelar"))
          setTimeout(
            () =>
              ws.send(
                JSON.stringify({
                  v: 1,
                  type: "chat.completed",
                  requestId: f.requestId,
                  conversationId: f.conversationId,
                  answer:
                    'Respuesta **documentada** <img src=x onerror="alert(1)">',
                }),
              ),
            100,
          );
      } else if (f.type === "chat.cancel")
        ws.send(
          JSON.stringify({
            v: 1,
            type: "chat.cancelled",
            requestId: f.requestId,
          }),
        );
    });
  });
  await page.goto("/settings");
  await page.getByLabel("Clave de acceso").fill("secret-test");
  await page.getByRole("button", { name: "Guardar clave" }).click();
});
test("streaming, sanitized Markdown, cancellation and local reload", async ({
  page,
}) => {
  await page.getByRole("link", { name: "Conversaciones", exact: true }).click();
  await page.getByLabel("Tu pregunta").fill("¿Qué dicen los documentos?");
  await page.getByRole("button", { name: "Enviar pregunta" }).click();
  await expect(
    page.locator(".message.assistant .message-body strong"),
  ).toHaveText("documentada");
  await expect(page.locator(".message.assistant img")).toHaveCount(0);
  await page.getByLabel("Tu pregunta").fill("cancelar respuesta");
  await page.getByRole("button", { name: "Enviar pregunta" }).click();
  await page.getByRole("button", { name: "Detener respuesta" }).click();
  await expect(page.getByText("Cancelada", { exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByText("Cancelada", { exact: true })).toBeVisible();
  await expect(
    page.getByText("Sin clave de acceso", { exact: true }),
  ).toBeVisible();
  expect(await page.evaluate(() => JSON.stringify(localStorage))).not.toContain(
    "secret-test",
  );
});
test("documents upload and synchronous scan summary", async ({ page }) => {
  await page.route("**/api/documents", (r) =>
    r.fulfill({
      status: 201,
      json: {
        success: true,
        filename: "notes.txt",
        message: "Documento indexado",
      },
    }),
  );
  await page.route("**/api/documents/scan", (r) =>
    r.fulfill({
      json: {
        indexed: 2,
        updated: 1,
        skipped: 3,
        failed: 1,
        errors: [{ path: "bad.pdf", reason: "No legible" }],
      },
    }),
  );
  await page.getByRole("link", { name: "Documentos", exact: true }).click();
  await page.locator("input[type=file]").setInputFiles({
    name: "notes.txt",
    mimeType: "text/plain",
    buffer: Buffer.from("Document text"),
  });
  await page.getByRole("button", { name: "Subir documentos" }).click();
  await expect(
    page.getByText("Documento indexado", { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Escanear documentos" }).click();
  await expect(page.getByText("bad.pdf: No legible")).toBeVisible();
  await expect(page.getByText("2", { exact: true })).toBeVisible();
});
test("health handles 503 and exposes documentation links", async ({ page }) => {
  await page.route("**/q/health**", (r) =>
    r.fulfill({
      status: 503,
      json: { status: "DOWN", checks: [{ name: "database", status: "DOWN" }] },
    }),
  );
  await page.goto("/system");
  await expect(page.getByText("DOWN", { exact: true })).toHaveCount(4);
  await expect(page.getByRole("link", { name: "Swagger UI" })).toHaveAttribute(
    "href",
    "/q/swagger-ui/",
  );
});
test("explicit complete-response mode reports HTTP errors without fallback", async ({
  page,
}) => {
  await page.route("**/api/chat", (r) =>
    r.fulfill({ status: 429, json: { message: "rate limit" } }),
  );
  await page.getByRole("link", { name: "Conversaciones", exact: true }).click();
  await page.getByLabel("Modo de respuesta").selectOption("rest");
  await page.getByLabel("Tu pregunta").fill("una pregunta");
  await page.getByRole("button", { name: "Enviar pregunta" }).click();
  await expect(page.getByText(/Demasiadas solicitudes/)).toBeVisible();
});

test("ticket authorization failures are actionable and never fall back to REST", async ({
  page,
}) => {
  let restRequests = 0;
  await page.route("**/api/chat", (route) => {
    restRequests++;
    return route.fulfill({ json: { answer: "unexpected" } });
  });
  await page.route("**/api/chat/ws-ticket", (route) =>
    route.fulfill({ status: 401, json: { message: "Unauthorized" } }),
  );
  await page.getByRole("link", { name: "Conversaciones", exact: true }).click();
  await page.getByLabel("Tu pregunta").fill("Una pregunta protegida");
  await page.getByRole("button", { name: "Enviar pregunta" }).click();
  await expect(page.getByRole("alert")).toContainText("clave");
  await expect(
    page.getByRole("button", { name: "Enviar pregunta" }),
  ).toBeVisible();
  expect(restRequests).toBe(0);
});

test("a failed upload does not prevent the next file from being indexed", async ({
  page,
}) => {
  let count = 0;
  await page.route("**/api/documents", (route) => {
    count++;
    expect(route.request().headers()["x-api-key"]).toBe("secret-test");
    return count === 1
      ? route.fulfill({
          status: 400,
          json: { message: "Formato no compatible" },
        })
      : route.fulfill({
          status: 201,
          json: {
            success: true,
            filename: "second.txt",
            message: "Segundo indexado",
          },
        });
  });
  await page.getByRole("link", { name: "Documentos", exact: true }).click();
  await page.locator("input[type=file]").setInputFiles([
    { name: "first.txt", mimeType: "text/plain", buffer: Buffer.from("one") },
    { name: "second.txt", mimeType: "text/plain", buffer: Buffer.from("two") },
  ]);
  await page.getByRole("button", { name: "Subir documentos" }).click();
  await expect(
    page.getByText("Formato no compatible", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText("Segundo indexado", { exact: true }),
  ).toBeVisible();
  expect(count).toBe(2);
});

test("streaming continues across navigation and closing the key interrupts it", async ({
  page,
}) => {
  await page.getByRole("link", { name: "Conversaciones", exact: true }).click();
  await page.getByLabel("Tu pregunta").fill("cancelar desde ajustes");
  await page.getByRole("button", { name: "Enviar pregunta" }).click();
  await expect(page.locator(".message.assistant .message-body")).toContainText(
    "Respuesta",
  );
  await page.getByRole("link", { name: "Documentos", exact: true }).click();
  await page.getByRole("link", { name: "Conversaciones", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Detener respuesta" }),
  ).toBeVisible();
  await page.getByRole("link", { name: "Ajustes", exact: true }).click();
  await page.getByRole("button", { name: "Eliminar clave" }).click();
  await page.getByRole("link", { name: "Conversaciones", exact: true }).click();
  await expect(page.getByText("Interrumpida", { exact: true })).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Enviar pregunta" }),
  ).toBeDisabled();
});

test("desktop and mobile layouts keep the composer and navigation accessible", async ({
  page,
}) => {
  await page.getByRole("link", { name: "Conversaciones", exact: true }).click();
  await page.setViewportSize({ width: 1440, height: 1000 });
  expect(
    await page
      .getByRole("button", { name: "Nueva conversación" })
      .evaluate((el) => el.getBoundingClientRect().height),
  ).toBeGreaterThanOrEqual(40);
  await expect(page.getByLabel("Tu pregunta")).toBeVisible();
  await page.screenshot({
    path: "test-results/nexo-desktop.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(
    page.getByRole("button", { name: "Nueva conversación" }),
  ).toBeVisible();
  await expect(page.getByLabel("Tu pregunta")).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: "test-results/nexo-mobile.png",
    fullPage: true,
  });
});
