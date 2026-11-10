import { Component, inject, OnInit } from "@angular/core";
import { ButtonModule } from "carbon-components-angular/button";
import { HealthService } from "../data-access/health-service";
@Component({
  standalone: true,
  imports: [ButtonModule],
  templateUrl: "../ui/system-page.html",
})
export class SystemPage implements OnInit {
  readonly data = inject(HealthService);
  readonly loading = this.data.loading;
  readonly updated = this.data.updated;
  readonly checks = this.data.checks;
  ngOnInit() {
    void this.refresh();
  }
  refresh() {
    return this.data.refresh();
  }
}
