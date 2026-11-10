import { Component, inject, signal } from "@angular/core";
import { FormsModule } from "@angular/forms";
import { ButtonModule } from "carbon-components-angular/button";
import { AuthStore } from "../../../core/auth/auth";
import { ChatStore } from "../../../core/chat-history";
@Component({
  standalone: true,
  imports: [FormsModule, ButtonModule],
  templateUrl: "../ui/settings-page.html",
})
export class SettingsPage {
  readonly auth = inject(AuthStore);
  readonly history = inject(ChatStore);
  draft = "";
  feedback = signal("");
  confirm = signal(false);
  save() {
    this.auth.set(this.draft);
    this.draft = "";
    this.feedback.set("Clave guardada para esta sesión.");
  }
  remove() {
    this.auth.set("");
    this.feedback.set("Clave eliminada.");
  }
  clear() {
    this.history.clear();
    this.confirm.set(false);
    this.feedback.set("Historial local eliminado.");
  }
}
