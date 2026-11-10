import { bootstrapApplication } from "@angular/platform-browser";
import { provideRouter } from "@angular/router";
import { provideHttpClient, withInterceptors } from "@angular/common/http";
import { AppComponent } from "./app/core/layout/app";
import { apiKeyInterceptor } from "./app/core/http/api";
bootstrapApplication(AppComponent, {
  providers: [
    provideRouter([
      {
        path: "chat",
        loadComponent: () =>
          import("./app/features/chat/pages/chat-page").then((m) => m.ChatPage),
      },
      {
        path: "documents",
        loadComponent: () =>
          import("./app/features/documents/pages/documents-page").then(
            (m) => m.DocumentsPage,
          ),
      },
      {
        path: "system",
        loadComponent: () =>
          import("./app/features/system/pages/system-page").then(
            (m) => m.SystemPage,
          ),
      },
      {
        path: "settings",
        loadComponent: () =>
          import("./app/features/settings/pages/settings-page").then(
            (m) => m.SettingsPage,
          ),
      },
      { path: "**", redirectTo: "chat" },
    ]),
    provideHttpClient(withInterceptors([apiKeyInterceptor])),
  ],
}).catch(console.error);
