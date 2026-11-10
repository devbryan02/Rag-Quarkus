import { Component, inject } from "@angular/core";
import { RouterLink } from "@angular/router";
import { ButtonModule } from "carbon-components-angular/button";
import { DocumentsService } from "../data-access/documents-service";
@Component({
  standalone: true,
  imports: [ButtonModule, RouterLink],
  templateUrl: "../ui/documents-page.html",
})
export class DocumentsPage {
  readonly data = inject(DocumentsService);
  readonly auth = this.data.auth;
  readonly queue = this.data.queue;
  readonly busy = this.data.busy;
  readonly scanning = this.data.scanning;
  readonly result = this.data.result;
  readonly scanError = this.data.scanError;
  readonly labels = this.data.labels;
  readonly metrics = this.data.metrics;
  hasQueued() {
    return this.data.hasQueued();
  }
  select(event: Event) {
    this.data.select(event);
  }
  formatSize(size: number) {
    return this.data.formatSize(size);
  }
  upload() {
    return this.data.upload();
  }
  scan() {
    return this.data.scan();
  }
}
