import { Component, Input, signal, computed } from "@angular/core";
import { marked } from "marked";
const renderer = new marked.Renderer();
renderer.html = () => "";
@Component({
  selector: "app-markdown",
  standalone: true,
  template: `<div class="message-body" [innerHTML]="html()"></div>`,
})
export class MarkdownComponent {
  private readonly source = signal("");
  @Input() set content(value: string) {
    this.source.set(value);
  }
  readonly html = computed(() =>
    marked.parse(this.source(), { async: false, renderer, breaks: true }),
  );
}
