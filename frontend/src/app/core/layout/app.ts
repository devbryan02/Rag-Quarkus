import { Component, inject, OnDestroy } from "@angular/core";
import { RouterLink, RouterLinkActive, RouterOutlet } from "@angular/router";
import { AuthStore } from "../auth/auth";
import { ChatTransport } from "../websocket/chat-transport";
@Component({
  selector: "app-root",
  standalone: true,
  imports: [RouterLink, RouterLinkActive, RouterOutlet],
  template: ` <a class="skip-link" href="#main">Saltar al contenido</a>
    <header class="topbar">
      <a routerLink="/chat" class="brand"
        ><span class="brand-mark">N</span><strong>Nexo</strong
        ><span class="brand-divider"></span><span>Asistente documental</span></a
      >
      <div class="top-status">
        <span class="status-dot" [class.active]="auth.key()"></span
        >{{ auth.key() ? "Clave configurada" : "Sin clave de acceso" }}
      </div>
    </header>
    <div class="workspace">
      <aside class="navigation">
        <div class="nav-label">ESPACIO DE TRABAJO</div>
        <nav aria-label="Navegación principal">
          <a routerLink="/chat" routerLinkActive="selected"
            ><span aria-hidden="true">◈</span> Conversaciones</a
          ><a routerLink="/documents" routerLinkActive="selected"
            ><span aria-hidden="true">▤</span> Documentos</a
          ><a routerLink="/system" routerLinkActive="selected"
            ><span aria-hidden="true">◉</span> Estado del sistema</a
          ><a routerLink="/settings" routerLinkActive="selected"
            ><span aria-hidden="true">⚙</span> Ajustes</a
          >
        </nav>
        <div class="nav-footer">
          <span class="small-label">BÚSQUEDA CON CONTEXTO</span>
          <p>
            Conocimiento de tus documentos.<br />Respuestas en una conversación.
          </p>
          <span class="version">RAG Workspace · v1.0</span>
        </div>
      </aside>
      <main id="main" tabindex="-1"><router-outlet /></main>
    </div>`,
})
export class AppComponent implements OnDestroy {
  readonly auth = inject(AuthStore);
  private transport = inject(ChatTransport);
  ngOnDestroy() {
    this.transport.disconnect();
  }
}
