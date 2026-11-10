import { describe, it, expect } from "vitest";
import {
  contextFor,
  restoreHistory,
  boundedHistory,
  reduceFrame,
} from "../src/app/features/chat/models/chat";
describe("conversation safeguards", () => {
  it("only includes complete pairs within five turns and 24000 characters", () => {
    const m: any[] = [];
    for (let i = 0; i < 8; i++)
      m.push(
        { role: "user", content: "q" + i, status: "complete" },
        { role: "assistant", content: "a" + i, status: "complete" },
      );
    m.push(
      { role: "user", content: "last", status: "complete" },
      { role: "assistant", content: "partial", status: "cancelled" },
    );
    expect(contextFor(m)).toHaveLength(10);
    expect(contextFor(m)[0].content).toBe("q3");
    expect(
      contextFor([
        { role: "user", content: "x".repeat(24001), status: "complete" },
        { role: "assistant", content: "a", status: "complete" },
      ] as any),
    ).toEqual([]);
  });
  it("interrupts reloaded pending messages and rejects unknown schemas", () => {
    expect(
      restoreHistory(
        JSON.stringify({
          version: 1,
          conversations: [
            {
              id: "a",
              title: "a",
              messages: [
                { role: "assistant", content: "text", status: "pending" },
              ],
            },
          ],
        }),
      )[0].messages[0].status,
    ).toBe("interrupted");
    expect(restoreHistory('{"version":2}')).toEqual([]);
  });
  it("bounds saved history by count, messages and encoded bytes", () => {
    const c = Array.from({ length: 25 }, (_, i) => ({
      id: String(i),
      title: "title",
      messages: Array.from({ length: 110 }, () => ({
        role: "assistant",
        content: "x".repeat(2000),
        status: "complete",
      })),
    }));
    const result = boundedHistory(c as any, "24");
    expect(result.length).toBeLessThanOrEqual(20);
    expect(result.find((x) => x.id === "24")).toBeDefined();
    expect(result.every((x) => x.messages.length <= 100)).toBe(true);
    expect(
      new TextEncoder().encode(
        JSON.stringify({ version: 1, conversations: result }),
      ).length,
    ).toBeLessThanOrEqual(2 * 1024 * 1024);
  });
  it("discards foreign IDs, sequence gaps and duplicate terminal events", () => {
    let s: any = {
      requestId: "r",
      conversationId: "c",
      seq: 0,
      text: "",
      terminal: false,
    };
    expect(
      reduceFrame(s, {
        v: 1,
        type: "chat.delta",
        requestId: "other",
        conversationId: "c",
        seq: 1,
        text: "bad",
      }),
    ).toEqual(s);
    s = reduceFrame(s, {
      v: 1,
      type: "chat.delta",
      requestId: "r",
      conversationId: "c",
      seq: 1,
      text: "yes",
    });
    expect(s.text).toBe("yes");
    expect(
      reduceFrame(s, {
        v: 1,
        type: "chat.delta",
        requestId: "r",
        conversationId: "c",
        seq: 3,
        text: "bad",
      }).error,
    ).toBeTruthy();
    s = reduceFrame(s, {
      v: 1,
      type: "chat.completed",
      requestId: "r",
      conversationId: "c",
      answer: "done",
    });
    expect(
      reduceFrame(s, {
        v: 1,
        type: "chat.delta",
        requestId: "r",
        conversationId: "c",
        seq: 2,
        text: "bad",
      }),
    ).toEqual(s);
  });
});

it("protects a streaming conversation from eviction after selecting a different one", () => {
  const c = Array.from({ length: 21 }, (_, i) => ({
    id: String(i),
    title: "t",
    updatedAt: String(i).padStart(2, "0"),
    messages: [
      {
        id: "m" + i,
        role: "assistant",
        content: "x",
        status: i === 0 ? "pending" : "complete",
        createdAt: "now",
      },
    ],
  }));
  const saved = boundedHistory(c as any, "20");
  expect(saved.find((x) => x.id === "0")).toBeDefined();
  expect(saved.find((x) => x.id === "20")).toBeDefined();
  expect(saved.length).toBe(20);
});
