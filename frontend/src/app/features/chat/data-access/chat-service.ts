import { Injectable, inject, signal, effect, untracked } from "@angular/core";
import { HttpClient } from "@angular/common/http";
import { Subscription } from "rxjs";
import { AuthStore } from "../../../core/auth/auth";
import { ChatStore } from "../../../core/chat-history";
import {
  ChatTransport,
  ServerFrame,
} from "../../../core/websocket/chat-transport";
import { StreamState, contextFor, reduceFrame } from "../models/chat";
import { errorMessage } from "../../../core/http/api";
interface Job {
  key: string;
  state: StreamState;
  messageId: string;
  mode: "ws" | "rest";
  cancelling: boolean;
}
@Injectable({ providedIn: "root" })
export class ChatService {
  readonly history = inject(ChatStore);
  readonly auth = inject(AuthStore);
  readonly transport = inject(ChatTransport);
  private http = inject(HttpClient);
  readonly mode = signal<"ws" | "rest">("ws");
  readonly error = signal("");
  readonly busy = signal(false);
  private job?: Job;
  private rest?: Subscription;
  private cancelTimeout?: ReturnType<typeof setTimeout>;
  constructor() {
    this.transport.events$.subscribe((frame) => this.onFrame(frame));
    effect(() => {
      const key = this.auth.key();
      untracked(() => {
        if (this.job && this.job.key !== key) {
          this.rest?.unsubscribe();
          this.finish(
            "interrupted",
            "La clave cambió. Configura el acceso y reintenta la pregunta.",
          );
        }
      });
    });
    this.history.removing$.subscribe((id) => {
      if (!this.job || (id !== null && id !== this.job.state.conversationId))
        return;
      const mode = this.job.mode;
      this.rest?.unsubscribe();
      this.finish("cancelled");
      if (mode === "ws") this.transport.disconnect();
    });
  }
  async send(question: string) {
    question = question.trim();
    if (this.busy() || !question) return;
    if (!this.auth.key()) {
      this.error.set(
        "Configura una clave de acceso en Ajustes para enviar preguntas.",
      );
      return;
    }
    if (question.length > 4000) {
      this.error.set("La pregunta no puede superar los 4000 caracteres.");
      return;
    }
    const conversation = this.history.active() ?? this.history.create();
    const history = contextFor(conversation.messages);
    const requestId = crypto.randomUUID();
    const messageId = crypto.randomUUID();
    const now = new Date().toISOString();
    const frame = {
      v: 1,
      type: "chat.start",
      requestId,
      conversationId: conversation.id,
      question,
      history,
    };
    if (
      this.mode() === "ws" &&
      new TextEncoder().encode(JSON.stringify(frame)).length > 65536
    ) {
      this.error.set(
        "El mensaje con su contexto supera 64 KiB. Inicia una nueva conversación.",
      );
      return;
    }
    this.job = {
      key: this.auth.key(),
      state: {
        requestId,
        conversationId: conversation.id,
        seq: 0,
        text: "",
        terminal: false,
      },
      messageId,
      mode: this.mode(),
      cancelling: false,
    };
    this.busy.set(true);
    this.error.set("");
    this.history.append(conversation.id, [
      {
        id: crypto.randomUUID(),
        role: "user",
        content: question,
        status: "complete",
        createdAt: now,
      },
      {
        id: messageId,
        role: "assistant",
        content: "",
        status: "pending",
        createdAt: now,
        requestId,
      },
    ]);
    if (this.mode() === "ws") {
      try {
        await this.transport.send(frame);
      } catch (error) {
        if (this.job?.state.requestId === requestId)
          this.finish("error", errorMessage(error));
      }
    } else {
      this.rest = this.http
        .post<{ answer: string }>("/api/chat", { question })
        .subscribe({
          next: (response) => {
            if (this.job?.state.requestId !== requestId) return;
            this.job.state.text = response.answer;
            this.finish("complete");
          },
          error: (error) => {
            if (this.job?.state.requestId === requestId)
              this.finish("error", errorMessage(error));
          },
        });
    }
  }
  cancel() {
    const job = this.job;
    if (!job || job.cancelling) return;
    job.cancelling = true;
    if (job.mode === "rest") {
      this.rest?.unsubscribe();
      this.finish("cancelled");
      return;
    }
    if (this.transport.status() !== "Conectado") {
      this.finish("cancelled");
      this.transport.disconnect();
      return;
    }
    this.history.updateMessage(job.state.conversationId, job.messageId, {
      status: "cancelling",
    });
    this.transport.cancel(job.state.requestId);
    this.cancelTimeout = setTimeout(() => {
      if (this.job === job) {
        this.finish("cancelled");
        this.transport.disconnect();
      }
    }, 10000);
  }
  retry(messageId: string) {
    const messages = this.history.active()?.messages ?? [];
    const i = messages.findIndex((m) => m.id === messageId);
    if (i > 0 && messages[i - 1].role === "user")
      void this.send(messages[i - 1].content);
  }
  private onFrame(frame: ServerFrame) {
    if (frame.type.startsWith("transport.") && this.job?.mode === "rest")
      return;
    if (frame.type === "transport.error") {
      this.error.set(frame.message ?? "Error de conexión.");
      if (this.job) this.finish("interrupted", frame.message);
      return;
    }
    if (frame.type === "transport.closed") {
      if (this.job)
        this.finish(
          this.job.cancelling ? "cancelled" : "interrupted",
          frame.message,
        );
      return;
    }
    const job = this.job;
    if (!job || job.mode !== "ws") return;
    if (frame.type === "chat.error" && !frame.requestId) {
      this.finish("error", frame.message);
      return;
    }
    const state = reduceFrame(job.state, frame);
    if (state === job.state) return;
    job.state =
      job.cancelling && state.terminal
        ? { ...state, text: job.state.text }
        : state;
    if (state.terminal) {
      if (state.error) this.transport.cancel(state.requestId);
      this.finish(
        job.cancelling
          ? "cancelled"
          : state.status === "pending" || state.status === "cancelling"
            ? "interrupted"
            : (state.status ?? "complete"),
        state.error,
      );
    } else
      this.history.updateMessage(state.conversationId, job.messageId, {
        content: state.text,
      });
  }
  private finish(
    status: "complete" | "cancelled" | "interrupted" | "error",
    error?: string,
  ) {
    const job = this.job;
    if (!job) return;
    clearTimeout(this.cancelTimeout);
    this.history.updateMessage(job.state.conversationId, job.messageId, {
      content: job.state.text,
      status,
      error,
    });
    if (error) this.error.set(error);
    this.job = undefined;
    this.busy.set(false);
  }
}
