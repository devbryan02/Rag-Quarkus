import { Injectable, signal } from "@angular/core";
@Injectable({ providedIn: "root" })
export class AuthStore {
  readonly key = signal("");
  set(value: string) {
    this.key.set(value.trim());
  }
}
export function ownApi(url: string, origin: string): boolean {
  try {
    const parsed = new URL(url, origin);
    return (
      parsed.origin === origin &&
      (parsed.pathname.startsWith("/api/") || parsed.pathname.startsWith("/q/"))
    );
  } catch {
    return false;
  }
}
