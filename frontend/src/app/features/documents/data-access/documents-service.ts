import { Injectable, inject, signal } from "@angular/core";
import { HttpClient } from "@angular/common/http";
import { firstValueFrom } from "rxjs";
import { AuthStore } from "../../../core/auth/auth";
import { errorMessage } from "../../../core/http/api";
import { Upload, Scan } from "../models/documents";
@Injectable({ providedIn: "root" })
export class DocumentsService {
  readonly auth = inject(AuthStore);
  private http = inject(HttpClient);
  queue = signal<Upload[]>([]);
  busy = signal(false);
  scanning = signal(false);
  result = signal<Scan | null>(null);
  scanError = signal("");
  readonly labels = {
    queued: "En espera",
    uploading: "Procesando",
    success: "Listo",
    error: "Error",
  };
  readonly metrics: {
    key: "indexed" | "updated" | "skipped" | "failed";
    label: string;
  }[] = [
    { key: "indexed", label: "Indexados" },
    { key: "updated", label: "Actualizados" },
    { key: "skipped", label: "Omitidos" },
    { key: "failed", label: "Fallidos" },
  ];
  hasQueued() {
    return this.queue().some((x) => x.status === "queued");
  }
  select(event: Event) {
    const files = Array.from((event.target as HTMLInputElement).files ?? []);
    this.queue.update((q) => [
      ...q,
      ...files.map((file) => ({
        file,
        status: "queued" as const,
        message: "",
      })),
    ]);
    (event.target as HTMLInputElement).value = "";
  }
  formatSize(size: number) {
    return size < 1024 ? `${size} B` : `${(size / 1024).toFixed(1)} KB`;
  }
  async upload() {
    if (!this.auth.key() || this.busy() || this.scanning()) return;
    this.busy.set(true);
    try {
      for (const item of this.queue().filter((x) => x.status === "queued")) {
        item.status = "uploading";
        this.queue.update((q) => [...q]);
        try {
          if (!this.auth.key())
            throw new Error(
              "Configura una clave de acceso en Ajustes para continuar.",
            );
          const body = new FormData();
          body.append("file", item.file);
          const response = await firstValueFrom(
            this.http.post<{ success: boolean; message: string }>(
              "/api/documents",
              body,
            ),
          );
          item.status = response.success ? "success" : "error";
          item.message = response.message;
        } catch (error) {
          item.status = "error";
          item.message = errorMessage(error);
        }
        this.queue.update((q) => [...q]);
      }
    } finally {
      this.busy.set(false);
    }
  }
  async scan() {
    if (!this.auth.key() || this.busy() || this.scanning()) return;
    this.scanning.set(true);
    this.scanError.set("");
    this.result.set(null);
    try {
      this.result.set(
        await firstValueFrom(this.http.post<Scan>("/api/documents/scan", null)),
      );
    } catch (error) {
      this.scanError.set(errorMessage(error));
    } finally {
      this.scanning.set(false);
    }
  }
}
