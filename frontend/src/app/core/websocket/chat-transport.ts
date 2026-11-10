import { Injectable, inject, signal, effect } from "@angular/core";
import { HttpClient } from "@angular/common/http";
import { firstValueFrom, Subject } from "rxjs";
import { AuthStore } from "../auth/auth";
import { errorMessage } from "../http/api";
export interface ServerFrame {
  v: number;
  type: string;
  requestId?: string;
  conversationId?: string;
  seq?: number;
  text?: string;
  answer?: string;
  code?: number;
  message?: string;
  retryAfterSeconds?: number;
}
@Injectable({ providedIn: "root" })
export class ChatTransport {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthStore);
  private readonly stream = new Subject<ServerFrame>();
  readonly events$ = this.stream.asObservable();
  readonly status = signal("Desconectado");
  private socket?: WebSocket;
  private epoch = 0;
  private retry = 0;
  private timer?: ReturnType<typeof setTimeout>;
  private connecting?: Promise<void>;
  private readyResolve?: () => void;
  private readyReject?: (e: Error) => void;
  private readyTimer?: ReturnType<typeof setTimeout>;
  private intentional = false;
  constructor() {
    effect(() => {
      this.auth.key();
      this.disconnect();
    });
  }
  async send(frame: object) {
    await this.connect();
    const encoded = JSON.stringify(frame);
    if (new TextEncoder().encode(encoded).length > 65536)
      throw new Error(
        "El mensaje supera el límite de 64 KiB. Reduce la pregunta o inicia una conversación.",
      );
    if (this.socket?.readyState !== WebSocket.OPEN)
      throw new Error("La conexión se cerró. Reintenta la pregunta.");
    this.socket.send(encoded);
  }
  cancel(requestId: string) {
    if (this.socket?.readyState === WebSocket.OPEN)
      this.socket.send(
        JSON.stringify({ v: 1, type: "chat.cancel", requestId }),
      );
  }
  disconnect() {
    this.intentional = true;
    this.epoch++;
    clearTimeout(this.timer);
    this.timer = undefined;
    clearTimeout(this.readyTimer);
    this.readyReject?.(new Error("La clave cambió o se cerró la conexión."));
    this.readyReject = undefined;
    this.readyResolve = undefined;
    this.socket?.close();
    this.socket = undefined;
    this.connecting = undefined;
    this.retry = 0;
    this.status.set("Desconectado");
    this.stream.next({
      v: 1,
      type: "transport.closed",
      message: "Conexión cerrada. Puedes reintentar con tu clave actual.",
    });
  }
  private connect(): Promise<void> {
    if (
      this.socket?.readyState === WebSocket.OPEN &&
      this.status() === "Conectado"
    )
      return Promise.resolve();
    if (this.connecting) return this.connecting;
    if (!this.auth.key())
      return Promise.reject(
        new Error("Configura una clave de acceso en Ajustes."),
      );
    this.intentional = false;
    const epoch = ++this.epoch;
    this.status.set("Conectando");
    const attempt = this.open(epoch);
    this.connecting = attempt;
    void attempt
      .finally(() => {
        if (epoch === this.epoch) this.connecting = undefined;
      })
      .catch(() => {});
    return attempt;
  }
  private async open(epoch: number) {
    try {
      const result = await firstValueFrom(
        this.http.post<{ ticket: string }>("/api/chat/ws-ticket", {}),
      );
      if (epoch !== this.epoch) throw new Error("La conexión ha cambiado.");
      await new Promise<void>((resolve, reject) => {
        this.readyResolve = resolve;
        this.readyReject = reject;
        const url = new URL("/api/ws/chat", location.origin);
        url.protocol = location.protocol === "https:" ? "wss:" : "ws:";
        url.searchParams.set("ticket", result.ticket);
        const socket = new WebSocket(url);
        this.socket = socket;
        this.readyTimer = setTimeout(() => {
          if (epoch === this.epoch) {
            reject(new Error("El servidor no confirmó la conexión."));
            socket.close();
          }
        }, 15000);
        socket.onmessage = (event) => {
          if (epoch !== this.epoch) return;
          let frame: ServerFrame;
          try {
            frame = JSON.parse(event.data);
            if (
              !frame ||
              typeof frame !== "object" ||
              Array.isArray(frame) ||
              typeof frame.type !== "string"
            )
              throw new Error("Invalid frame");
          } catch {
            this.stream.next({
              v: 1,
              type: "transport.error",
              message: "El servidor envió un mensaje inválido.",
            });
            socket.close();
            return;
          }
          if (frame.v !== 1) return;
          if (frame.type === "ready") {
            clearTimeout(this.readyTimer);
            this.readyReject = undefined;
            this.readyResolve = undefined;
            this.status.set("Conectado");
            this.retry = 0;
            resolve();
          } else this.stream.next(frame);
        };
        socket.onerror = () => {
          if (epoch === this.epoch) this.status.set("Error de conexión");
        };
        socket.onclose = () => {
          if (epoch !== this.epoch) return;
          clearTimeout(this.readyTimer);
          this.socket = undefined;
          this.connecting = undefined;
          reject(new Error("No se pudo mantener la conexión."));
          this.stream.next({
            v: 1,
            type: "transport.closed",
            message:
              "La conexión se interrumpió. La pregunta no se reenviará automáticamente.",
          });
          if (!this.intentional) this.scheduleReconnect();
        };
      });
    } catch (error) {
      if (epoch !== this.epoch) throw error;
      this.status.set("Sin conexión");
      const status = (error as { status?: number }).status;
      if (status === 401 || status === 403) {
        this.intentional = true;
        this.stream.next({
          v: 1,
          type: "transport.error",
          message: errorMessage(error),
        });
      } else if (!this.intentional && !this.timer) this.scheduleReconnect();
      throw new Error(errorMessage(error));
    }
  }
  private scheduleReconnect() {
    if (this.timer || !this.auth.key()) return;
    if (this.retry >= 5) {
      this.status.set("Sin conexión");
      this.stream.next({
        v: 1,
        type: "transport.error",
        message: "Se agotaron los intentos de conexión. Reintenta manualmente.",
      });
      return;
    }
    const delay = 1000 * 2 ** this.retry++;
    this.status.set(`Reconectando (${this.retry}/5)`);
    this.timer = setTimeout(() => {
      this.timer = undefined;
      void this.connect().catch(() => {});
    }, delay);
  }
}
