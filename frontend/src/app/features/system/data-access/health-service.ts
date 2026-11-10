import { Injectable, inject, signal } from "@angular/core";
import { HttpClient, HttpErrorResponse } from "@angular/common/http";
import { firstValueFrom } from "rxjs";
import { Health } from "../models/health";
@Injectable({ providedIn: "root" })
export class HealthService {
  private http = inject(HttpClient);
  loading = signal(false);
  updated = signal("");
  checks = signal<Health[]>([
    { name: "Estado general", path: "/q/health", status: "…", detail: "" },
    { name: "Vitalidad", path: "/q/health/live", status: "…", detail: "" },
    {
      name: "Disponibilidad",
      path: "/q/health/ready",
      status: "…",
      detail: "",
    },
    { name: "Inicio", path: "/q/health/started", status: "…", detail: "" },
  ]);
  async refresh() {
    if (this.loading()) return;
    this.loading.set(true);
    await Promise.all(
      this.checks().map(async (item) => {
        try {
          const data = await firstValueFrom(this.http.get<any>(item.path));
          this.update(item.path, data);
        } catch (error) {
          if (error instanceof HttpErrorResponse && error.status === 503)
            this.update(item.path, error.error);
          else this.update(item.path, { status: "SIN CONEXIÓN", checks: [] });
        }
      }),
    );
    this.updated.set(new Date().toLocaleTimeString("es-PE"));
    this.loading.set(false);
  }
  private update(path: string, data: any) {
    this.checks.update((items) =>
      items.map((item) =>
        item.path === path
          ? {
              ...item,
              status: data?.status ?? "DESCONOCIDO",
              detail: Array.isArray(data?.checks)
                ? data.checks
                    .map((x: any) => `${x.name}: ${x.status}`)
                    .join(" · ")
                : "",
            }
          : item,
      ),
    );
  }
}
