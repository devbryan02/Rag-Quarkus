export type MessageStatus =
  "pending" | "cancelling" | "complete" | "cancelled" | "interrupted" | "error";
export interface Message {
  id: string;
  role: "user" | "assistant";
  content: string;
  status: MessageStatus;
  createdAt: string;
  requestId?: string;
  error?: string;
}
export interface Conversation {
  id: string;
  title: string;
  messages: Message[];
  updatedAt: string;
}
export interface StreamState {
  requestId: string;
  conversationId: string;
  seq: number;
  text: string;
  terminal: boolean;
  status?: MessageStatus;
  error?: string;
}
export function contextFor(
  messages: Message[],
): { role: "user" | "assistant"; content: string }[] {
  const pairs: { role: "user" | "assistant"; content: string }[][] = [];
  for (let i = 0; i < messages.length - 1; i++) {
    const u = messages[i],
      a = messages[i + 1];
    if (
      u.role === "user" &&
      a.role === "assistant" &&
      u.status === "complete" &&
      a.status === "complete"
    ) {
      pairs.push([
        { role: "user", content: u.content },
        { role: "assistant", content: a.content },
      ]);
      i++;
    }
  }
  const result: { role: "user" | "assistant"; content: string }[] = [];
  let size = 0;
  for (const pair of pairs.slice(-5).reverse()) {
    const chars = pair[0].content.length + pair[1].content.length;
    if (size + chars > 24000) break;
    result.unshift(...pair);
    size += chars;
  }
  return result;
}
export function restoreHistory(raw: string): Conversation[] {
  try {
    const parsed = JSON.parse(raw);
    if (parsed.version !== 1 || !Array.isArray(parsed.conversations)) return [];
    return parsed.conversations
      .filter(
        (c: any) =>
          c &&
          typeof c.id === "string" &&
          typeof c.title === "string" &&
          Array.isArray(c.messages),
      )
      .map((c: any) => ({
        ...c,
        title: c.title.slice(0, 120),
        updatedAt:
          typeof c.updatedAt === "string"
            ? c.updatedAt
            : new Date().toISOString(),
        messages: c.messages
          .filter(
            (m: any) =>
              m &&
              ["user", "assistant"].includes(m.role) &&
              typeof m.content === "string",
          )
          .map((m: any) => ({
            ...m,
            id: typeof m.id === "string" ? m.id : crypto.randomUUID(),
            createdAt:
              typeof m.createdAt === "string"
                ? m.createdAt
                : new Date().toISOString(),
            status: ["pending", "cancelling"].includes(m.status)
              ? "interrupted"
              : ["complete", "cancelled", "interrupted", "error"].includes(
                    m.status,
                  )
                ? m.status
                : "interrupted",
          })),
      }));
  } catch {
    return [];
  }
}
export function boundedHistory(
  conversations: Conversation[],
  activeId: string,
): Conversation[] {
  let result = conversations.map((c) => ({
    ...c,
    messages: c.messages.slice(-100),
  }));
  const removeOldest = () => {
    let index = result.length - 1;
    const inactive = result
      .map((c, i) => ({ c, i }))
      .filter(
        (x) =>
          !x.c.messages.some(
            (m) => m.status === "pending" || m.status === "cancelling",
          ),
      );
    const unselected = inactive.filter((x) => x.c.id !== activeId);
    const candidates = unselected.length ? unselected : inactive;
    if (candidates.length) {
      candidates.sort((a, b) =>
        (a.c.updatedAt ?? "").localeCompare(b.c.updatedAt ?? ""),
      );
      index = candidates[0].i;
    }
    result.splice(index, 1);
  };
  while (result.length > 20) removeOldest();
  const bytes = () =>
    new TextEncoder().encode(
      JSON.stringify({ version: 1, conversations: result }),
    ).length;
  while (bytes() > 2 * 1024 * 1024 && result.length > 1) removeOldest();
  if (result.length) {
    let current = result[0];
    while (bytes() > 2 * 1024 * 1024 && current.messages.length > 1)
      current.messages.shift();
    if (bytes() > 2 * 1024 * 1024 && current.messages.length)
      current.messages[0] = {
        ...current.messages[0],
        content: current.messages[0].content.slice(-100000),
      };
  }
  return result;
}
export function reduceFrame(state: StreamState, frame: any): StreamState {
  if (
    frame.v !== 1 ||
    state.terminal ||
    frame.requestId !== state.requestId ||
    (frame.conversationId !== undefined &&
      frame.conversationId !== state.conversationId)
  )
    return state;
  switch (frame.type) {
    case "chat.delta":
      if (
        !Number.isInteger(frame.seq) ||
        frame.seq !== state.seq + 1 ||
        typeof frame.text !== "string"
      )
        return {
          ...state,
          terminal: true,
          status: "interrupted",
          error:
            "La secuencia de la respuesta se interrumpió. Reintenta la pregunta.",
        };
      return { ...state, seq: frame.seq, text: state.text + frame.text };
    case "chat.completed":
      return typeof frame.answer === "string"
        ? { ...state, text: frame.answer, terminal: true, status: "complete" }
        : {
            ...state,
            terminal: true,
            status: "error",
            error: "La respuesta recibida no es válida.",
          };
    case "chat.cancelled":
      return { ...state, terminal: true, status: "cancelled" };
    case "chat.error":
      return {
        ...state,
        terminal: true,
        status: "error",
        error: `${frame.message || "No se pudo generar la respuesta."}${frame.retryAfterSeconds ? ` Reintenta en ${frame.retryAfterSeconds} segundos.` : ""}`,
      };
    default:
      return state;
  }
}
