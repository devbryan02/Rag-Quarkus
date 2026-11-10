import {
  Component,
  inject,
  signal,
  ViewChild,
  ElementRef,
  AfterViewChecked,
} from "@angular/core";
import { FormsModule } from "@angular/forms";
import { DatePipe } from "@angular/common";
import { RouterLink } from "@angular/router";
import { ButtonModule } from "carbon-components-angular/button";
import { ChatService } from "../data-access/chat-service";
import { MarkdownComponent } from "../../../shared/ui/markdown";
@Component({
  standalone: true,
  imports: [FormsModule, DatePipe, RouterLink, ButtonModule, MarkdownComponent],
  templateUrl: "../ui/chat-page.html",
})
export class ChatPage implements AfterViewChecked {
  readonly chat = inject(ChatService);
  draft = signal("");
  readonly suggestions = [
    "Resume los temas principales de mis documentos",
    "¿Qué información hay sobre este proyecto?",
    "Identifica los puntos más importantes",
    "Ayúdame a comparar las fuentes disponibles",
  ];
  @ViewChild("scroll") scroll?: ElementRef<HTMLElement>;
  @ViewChild("composer") composer?: ElementRef<HTMLTextAreaElement>;
  private follow = true;
  private last = "";
  newChat() {
    this.chat.history.create();
    this.draft.set("");
    this.follow = true;
  }
  remove(id: string) {
    this.chat.history.delete(id);
  }
  send() {
    if (this.chat.busy() || !this.draft().trim() || !this.chat.auth.key())
      return;
    const question = this.draft();
    this.draft.set("");
    this.follow = true;
    void this.chat.send(question);
  }
  keyboard(event: KeyboardEvent) {
    if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
      event.preventDefault();
      this.send();
    }
  }
  focusComposer() {
    setTimeout(() => this.composer?.nativeElement.focus());
  }
  onScroll() {
    const el = this.scroll?.nativeElement;
    if (el)
      this.follow = el.scrollHeight - el.scrollTop - el.clientHeight < 100;
  }
  ngAfterViewChecked() {
    const active = this.chat.history.active();
    const marker = JSON.stringify([
      active?.id,
      active?.messages.map((x) => [x.id, x.content.length, x.status]),
    ]);
    if (this.last !== marker) {
      this.last = marker;
      const el = this.scroll?.nativeElement;
      if (el && this.follow) el.scrollTop = el.scrollHeight;
    }
  }
}
