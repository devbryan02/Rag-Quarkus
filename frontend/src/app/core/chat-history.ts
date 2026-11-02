import { Injectable, signal, computed } from "@angular/core";
import { Subject } from "rxjs";
import {
  Conversation,
  Message,
  boundedHistory,
  restoreHistory,
} from "./history-models";
export const HISTORY_KEY = "nexo.conversations.v1";
@Injectable({ providedIn: "root" })
export class ChatStore {
  private readonly removing = new Subject<string | null>();
  readonly removing$ = this.removing.asObservable();
  readonly conversations = signal<Conversation[]>([]);
  readonly activeId = signal("");
  readonly warning = signal("");
  readonly active = computed(() =>
    this.conversations().find((x) => x.id === this.activeId()),
  );
  constructor() {
    try {
      const saved = localStorage.getItem(HISTORY_KEY);
      if (saved)
        this.conversations.set(boundedHistory(restoreHistory(saved), ""));
    } catch {
      this.warning.set(
        "El almacenamiento local no está disponible. El historial se conservará solo durante esta sesión.",
      );
    }
    this.activeId.set(this.conversations()[0]?.id ?? "");
  }
  create() {
    const c: Conversation = {
      id: crypto.randomUUID(),
      title: "Nueva conversación",
      messages: [],
      updatedAt: new Date().toISOString(),
    };
    this.activeId.set(c.id);
    this.conversations.update((list) => [c, ...list]);
    this.save();
    return c;
  }
  select(id: string) {
    this.activeId.set(id);
  }
  delete(id: string) {
    this.removing.next(id);
    this.conversations.update((list) => list.filter((x) => x.id !== id));
    if (this.activeId() === id)
      this.activeId.set(this.conversations()[0]?.id ?? "");
    this.save();
  }
  clear() {
    this.removing.next(null);
    this.conversations.set([]);
    this.activeId.set("");
    this.save();
  }
  append(id: string, messages: Message[]) {
    this.conversations.update((list) =>
      list.map((c) =>
        c.id === id
          ? {
              ...c,
              messages: [...c.messages, ...messages],
              title: c.messages.length
                ? c.title
                : messages[0].content.slice(0, 56),
              updatedAt: new Date().toISOString(),
            }
          : c,
      ),
    );
    this.save();
  }
  updateMessage(id: string, messageId: string, change: Partial<Message>) {
    this.conversations.update((list) =>
      list.map((c) =>
        c.id === id
          ? {
              ...c,
              messages: c.messages.map((m) =>
                m.id === messageId ? { ...m, ...change } : m,
              ),
              updatedAt: new Date().toISOString(),
            }
          : c,
      ),
    );
    this.save();
  }
  private save() {
    const bounded = boundedHistory(this.conversations(), this.activeId());
    this.conversations.set(bounded);
    try {
      localStorage.setItem(
        HISTORY_KEY,
        JSON.stringify({ version: 1, conversations: bounded }),
      );
    } catch {
      this.warning.set(
        "No se pudo guardar el historial local. Tus conversaciones siguen disponibles en esta sesión.",
      );
    }
  }
}
